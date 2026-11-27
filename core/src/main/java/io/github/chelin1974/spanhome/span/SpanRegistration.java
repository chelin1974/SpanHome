package io.github.chelin1974.spanhome.span;

import io.github.chelin1974.spanhome.span.api.SpanPanelApi;
import io.github.chelin1974.spanhome.span.api.model.AuthIn;
import io.github.chelin1974.spanhome.span.api.model.AuthOut;
import io.github.chelin1974.spanhome.span.api.model.ErrorOut;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Registers this bridge with a panel's SPAN API and returns the broker credentials.
 *
 * <ol>
 *   <li>Downloads the panel's CA certificate ({@code GET /api/v2/certificate/ca}, unauthenticated,
 *       over HTTP: you must be on your home network).
 *   <li>Registers over HTTPS, verified against that CA, so the passphrase never crosses the LAN in
 *       the clear ({@code POST /api/v2/auth/register}).
 * </ol>
 *
 * <p>Either pass the panel passphrase from the SPAN Home app, or omit it and press the panel's
 * door switch three times first (opening the door counts as the first press). The door-press
 * proof is single-use: a failed registration consumes it, so this checks
 * {@code GET /api/v2/status} for {@code proximityProven} before spending it.
 *
 * <p>The status and register calls go through {@link SpanPanelApi}, generated at build time from
 * SPAN's OpenAPI spec (see {@code core/src/main/openapi}). The CA download stays a plain request:
 * it returns a PEM file, which the spec describes as binary, not JSON.
 */
public final class SpanRegistration {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  static final String CA_PATH = "/api/v2/certificate/ca";

  /** Raised with a message fit to show the user. */
  public static final class RegistrationException extends RuntimeException {
    public RegistrationException(final String message) {
      super(message);
    }

    public RegistrationException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }

  private final HttpClient plain = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  /**
   * @param host the panel's address: {@code span-<serial>.local} or its IP
   * @param passphrase the panel passphrase, or null to use the door-press proof
   * @param waitForDoor with no passphrase, how long to wait for the door presses
   */
  public PanelCredentials.Panel register(final String host, final String passphrase, final Duration waitForDoor) {
    final var ca = downloadCa(host);
    final var api = api("https://" + host, httpsClient(ca));

    final var usePassphrase = passphrase != null && !passphrase.isBlank();
    if (!usePassphrase) {
      awaitProximity(api, host, waitForDoor);
    }

    final var clientName = "span-home-" + HexFormat.of().formatHex(randomBytes(4));
    final var request = new AuthIn(
        clientName, "SpanHome HomeKit bridge", null, null, usePassphrase ? passphrase.trim() : null);

    final AuthOut reply;
    try {
      reply = call(host, () -> api.register(request));
    } catch (final RestClientResponseException e) {
      throw new RegistrationException(
          describeFailure(e.getStatusCode().value(), e.getResponseBodyAsString(), usePassphrase));
    }

    if (reply == null || isBlank(reply.ebusBrokerPassword()) || isBlank(reply.serialNumber())) {
      throw new RegistrationException(
          "the panel registered this bridge but returned no broker password. Try again with the"
              + " passphrase from the SPAN Home app (--passphrase).");
    }

    final var panel = new PanelCredentials.Panel();
    panel.serial = reply.serialNumber();
    panel.host = host;
    panel.mqttsPort = reply.ebusBrokerMqttsPort() == null ? 8883 : Math.toIntExact(reply.ebusBrokerMqttsPort());
    panel.brokerUsername = reply.ebusBrokerUsername() == null ? reply.serialNumber() : reply.ebusBrokerUsername();
    panel.brokerPassword = reply.ebusBrokerPassword();
    panel.caCertificate = ca;
    panel.clientName = clientName;
    return panel;
  }

  // ------------------------------------------------------------------ steps

  String downloadCa(final String host) {
    final var client = restClient("http://" + host, plain);
    final String pem;
    try {
      pem = call(host, () -> client.get().uri(CA_PATH).accept(MediaType.ALL).retrieve().body(String.class));
    } catch (final RestClientResponseException e) {
      if (e.getStatusCode().value() == 429) {
        throw new RegistrationException("the panel rate-limited the certificate download (10 a minute). Wait a minute and retry.");
      }
      throw new RegistrationException(
          "could not download the CA certificate from " + host + " (HTTP " + e.getStatusCode().value() + ")");
    }
    if (pem == null || !pem.contains("BEGIN CERTIFICATE")) {
      throw new RegistrationException("could not download the CA certificate from " + host + " (HTTP 200)");
    }
    return pem;
  }

  private void awaitProximity(final SpanPanelApi api, final String host, final Duration wait) {
    final var deadline = Instant.now().plus(wait);
    var told = false;
    while (true) {
      if (proximityProven(api, host)) {
        System.err.println("Door presses detected.");
        return;
      }
      if (!told) {
        System.err.println(
            "Open the panel door and press the door switch twice more (opening it counts as the"
                + " first press). The breaker LEDs flash to confirm. Waiting up to "
                + wait.toMinutes() + " minutes...");
        told = true;
      }
      if (Instant.now().isAfter(deadline)) {
        throw new RegistrationException(
            "no door presses detected on " + host + ". Retry, or use --passphrase with the passphrase"
                + " from the SPAN Home app.");
      }
      try {
        Thread.sleep(3_000);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RegistrationException("interrupted while waiting for the door presses");
      }
    }
  }

  /** Whether the door presses are in; an error answer counts as not yet, like a "false". */
  static boolean proximityProven(final SpanPanelApi api, final String host) {
    try {
      final var status = call(host, api::status);
      return status != null && Boolean.TRUE.equals(status.proximityProven());
    } catch (final RestClientResponseException e) {
      return false;
    }
  }

  static String describeFailure(final int status, final String body, final boolean usedPassphrase) {
    final var detail = detail(body);
    final var suffix = detail.isBlank() ? "" : ": " + detail;
    if (status == 401 || status == 403) {
      return usedPassphrase
          ? "the panel rejected the passphrase (HTTP " + status + suffix + "). Copy it again from the SPAN Home app."
          : "the panel did not accept the door-press proof (HTTP " + status + suffix + "). Press the door switch three times and retry.";
    }
    return "registration failed with HTTP " + status + suffix;
  }

  /** The {@code detail} of the panel's error body ({@link ErrorOut}), or "" if it has none. */
  private static String detail(final String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    try {
      final var error = MAPPER.readValue(body, ErrorOut.class);
      return error == null || error.detail() == null ? "" : error.detail();
    } catch (final JacksonException e) {
      return "";
    }
  }

  // ---------------------------------------------------------------- helpers

  /** The generated SPAN API client for {@code baseUrl}, sending through {@code http}. */
  static SpanPanelApi api(final String baseUrl, final HttpClient http) {
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient(baseUrl, http)))
        .build()
        .createClient(SpanPanelApi.class);
  }

  private static RestClient restClient(final String baseUrl, final HttpClient http) {
    final var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(TIMEOUT);
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  private static HttpClient httpsClient(final String caPem) {
    try {
      final var context = PanelTls.sslContext(caPem);
      return HttpClient.newBuilder().connectTimeout(TIMEOUT).sslContext(context).build();
    } catch (final Exception e) {
      throw new RegistrationException("the panel's CA certificate could not be loaded: " + e.getMessage(), e);
    }
  }

  /**
   * Runs one request. An HTTP error status comes back as {@link RestClientResponseException} for
   * the caller to read; anything else that goes wrong becomes a {@link RegistrationException}.
   */
  private static <T> T call(final String host, final Supplier<T> request) {
    try {
      return request.get();
    } catch (final RestClientResponseException e) {
      throw e;
    } catch (final ResourceAccessException e) {
      final var cause = e.getMostSpecificCause();
      throw new RegistrationException(
          "could not reach " + host + ": " + (cause.getMessage() == null ? cause : cause.getMessage())
              + ". Check the address, and that this machine is on the same network as the panel.", e);
    } catch (final RestClientException e) {
      throw new RegistrationException("the panel at " + host + " sent a reply this bridge could not read: " + e.getMessage(), e);
    }
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }

  private static byte[] randomBytes(final int count) {
    final var bytes = new byte[count];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }
}
