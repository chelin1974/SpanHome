package io.github.chelin1974.spanhome.span;

import io.github.chelin1974.spanhome.span.api.SpanPanelApi;
import io.github.chelin1974.spanhome.span.api.model.AuthIn;
import io.github.chelin1974.spanhome.span.api.model.AuthOut;
import io.github.chelin1974.spanhome.span.api.model.ErrorOut;
import io.github.chelin1974.spanhome.span.api.model.StatusV2Out;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
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
    public RegistrationException(String message) {
      super(message);
    }

    public RegistrationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final HttpClient plain = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  /**
   * @param host the panel's address: {@code span-<serial>.local} or its IP
   * @param passphrase the panel passphrase, or null to use the door-press proof
   * @param waitForDoor with no passphrase, how long to wait for the door presses
   */
  public PanelCredentials.Panel register(String host, String passphrase, Duration waitForDoor) {
    String ca = downloadCa(host);
    SpanPanelApi api = api("https://" + host, httpsClient(ca));

    boolean usePassphrase = passphrase != null && !passphrase.isBlank();
    if (!usePassphrase) {
      awaitProximity(api, host, waitForDoor);
    }

    String clientName = "span-home-" + HexFormat.of().formatHex(randomBytes(4));
    AuthIn request = new AuthIn(
        clientName, "SpanHome HomeKit bridge", null, null, usePassphrase ? passphrase.trim() : null);

    AuthOut reply;
    try {
      reply = call(host, () -> api.register(request));
    } catch (RestClientResponseException e) {
      throw new RegistrationException(
          describeFailure(e.getStatusCode().value(), e.getResponseBodyAsString(), usePassphrase));
    }

    if (reply == null || isBlank(reply.ebusBrokerPassword()) || isBlank(reply.serialNumber())) {
      throw new RegistrationException(
          "the panel registered this bridge but returned no broker password. Try again with the"
              + " passphrase from the SPAN Home app (--passphrase).");
    }

    PanelCredentials.Panel panel = new PanelCredentials.Panel();
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

  String downloadCa(String host) {
    RestClient client = restClient("http://" + host, plain);
    String pem;
    try {
      pem = call(host, () -> client.get().uri(CA_PATH).accept(MediaType.ALL).retrieve().body(String.class));
    } catch (RestClientResponseException e) {
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

  private void awaitProximity(SpanPanelApi api, String host, Duration wait) {
    Instant deadline = Instant.now().plus(wait);
    boolean told = false;
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
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RegistrationException("interrupted while waiting for the door presses");
      }
    }
  }

  /** Whether the door presses are in; an error answer counts as not yet, like a "false". */
  static boolean proximityProven(SpanPanelApi api, String host) {
    try {
      StatusV2Out status = call(host, api::status);
      return status != null && Boolean.TRUE.equals(status.proximityProven());
    } catch (RestClientResponseException e) {
      return false;
    }
  }

  static String describeFailure(int status, String body, boolean usedPassphrase) {
    String detail = detail(body);
    String suffix = detail.isBlank() ? "" : ": " + detail;
    if (status == 401 || status == 403) {
      return usedPassphrase
          ? "the panel rejected the passphrase (HTTP " + status + suffix + "). Copy it again from the SPAN Home app."
          : "the panel did not accept the door-press proof (HTTP " + status + suffix + "). Press the door switch three times and retry.";
    }
    return "registration failed with HTTP " + status + suffix;
  }

  /** The {@code detail} of the panel's error body ({@link ErrorOut}), or "" if it has none. */
  private static String detail(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    try {
      ErrorOut error = MAPPER.readValue(body, ErrorOut.class);
      return error == null || error.detail() == null ? "" : error.detail();
    } catch (JacksonException e) {
      return "";
    }
  }

  // ---------------------------------------------------------------- helpers

  /** The generated SPAN API client for {@code baseUrl}, sending through {@code http}. */
  static SpanPanelApi api(String baseUrl, HttpClient http) {
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient(baseUrl, http)))
        .build()
        .createClient(SpanPanelApi.class);
  }

  private static RestClient restClient(String baseUrl, HttpClient http) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(TIMEOUT);
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  private static HttpClient httpsClient(String caPem) {
    try {
      SSLContext context = PanelTls.sslContext(caPem);
      return HttpClient.newBuilder().connectTimeout(TIMEOUT).sslContext(context).build();
    } catch (Exception e) {
      throw new RegistrationException("the panel's CA certificate could not be loaded: " + e.getMessage(), e);
    }
  }

  /**
   * Runs one request. An HTTP error status comes back as {@link RestClientResponseException} for
   * the caller to read; anything else that goes wrong becomes a {@link RegistrationException}.
   */
  private static <T> T call(String host, Supplier<T> request) {
    try {
      return request.get();
    } catch (RestClientResponseException e) {
      throw e;
    } catch (ResourceAccessException e) {
      Throwable cause = e.getMostSpecificCause();
      throw new RegistrationException(
          "could not reach " + host + ": " + (cause.getMessage() == null ? cause : cause.getMessage())
              + ". Check the address, and that this machine is on the same network as the panel.", e);
    } catch (RestClientException e) {
      throw new RegistrationException("the panel at " + host + " sent a reply this bridge could not read: " + e.getMessage(), e);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static byte[] randomBytes(int count) {
    byte[] bytes = new byte[count];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }
}
