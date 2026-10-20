package io.github.chelin1974.spanhome.span;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLContext;

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
 */
public final class SpanRegistration {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

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
    HttpClient tls = httpsClient(ca);

    boolean usePassphrase = passphrase != null && !passphrase.isBlank();
    if (!usePassphrase) {
      awaitProximity(tls, host, waitForDoor);
    }

    String clientName = "span-home-" + HexFormat.of().formatHex(randomBytes(4));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", clientName);
    body.put("description", "SpanHome HomeKit bridge");
    if (usePassphrase) {
      body.put("hopPassphrase", passphrase.trim());
    }

    HttpResponse<String> response = send(tls, post("https://" + host + "/api/v2/auth/register", body));
    if (response.statusCode() != 200) {
      throw new RegistrationException(describeFailure(response, usePassphrase));
    }

    JsonNode json = parse(response.body());
    String password = json.path("ebusBrokerPassword").asText(null);
    String serial = json.path("serialNumber").asText(null);
    if (password == null || password.isBlank() || serial == null || serial.isBlank()) {
      throw new RegistrationException(
          "the panel registered this bridge but returned no broker password. Try again with the"
              + " passphrase from the SPAN Home app (--passphrase).");
    }

    PanelCredentials.Panel panel = new PanelCredentials.Panel();
    panel.serial = serial;
    panel.host = host;
    panel.mqttsPort = json.path("ebusBrokerMqttsPort").asInt(8883);
    panel.brokerUsername = json.path("ebusBrokerUsername").asText(serial);
    panel.brokerPassword = password;
    panel.caCertificate = ca;
    panel.clientName = clientName;
    return panel;
  }

  // ------------------------------------------------------------------ steps

  private String downloadCa(String host) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://" + host + "/api/v2/certificate/ca")).timeout(TIMEOUT).GET().build();
    HttpResponse<String> response = send(plain, request);
    if (response.statusCode() == 429) {
      throw new RegistrationException("the panel rate-limited the certificate download (10 a minute). Wait a minute and retry.");
    }
    if (response.statusCode() != 200 || !response.body().contains("BEGIN CERTIFICATE")) {
      throw new RegistrationException(
          "could not download the CA certificate from " + host + " (HTTP " + response.statusCode() + ")");
    }
    return response.body();
  }

  private void awaitProximity(HttpClient tls, String host, Duration wait) {
    Instant deadline = Instant.now().plus(wait);
    boolean told = false;
    while (true) {
      HttpResponse<String> response =
          send(tls, HttpRequest.newBuilder(URI.create("https://" + host + "/api/v2/status")).timeout(TIMEOUT).GET().build());
      if (response.statusCode() == 200 && parse(response.body()).path("proximityProven").asBoolean(false)) {
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

  static String describeFailure(HttpResponse<String> response, boolean usedPassphrase) {
    String detail = parse(response.body()).path("detail").asText("");
    int status = response.statusCode();
    String suffix = detail.isBlank() ? "" : ": " + detail;
    if (status == 401 || status == 403) {
      return usedPassphrase
          ? "the panel rejected the passphrase (HTTP " + status + suffix + "). Copy it again from the SPAN Home app."
          : "the panel did not accept the door-press proof (HTTP " + status + suffix + "). Press the door switch three times and retry.";
    }
    return "registration failed with HTTP " + status + suffix;
  }

  // ---------------------------------------------------------------- helpers

  private static HttpClient httpsClient(String caPem) {
    try {
      SSLContext context = PanelTls.sslContext(caPem);
      return HttpClient.newBuilder().connectTimeout(TIMEOUT).sslContext(context).build();
    } catch (Exception e) {
      throw new RegistrationException("the panel's CA certificate could not be loaded: " + e.getMessage(), e);
    }
  }

  private static HttpRequest post(String url, Object body) {
    try {
      return HttpRequest.newBuilder(URI.create(url))
          .timeout(TIMEOUT)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
          .build();
    } catch (IOException e) {
      throw new RegistrationException("could not build the registration request", e);
    }
  }

  private static HttpResponse<String> send(HttpClient client, HttpRequest request) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new RegistrationException(
          "could not reach " + request.uri().getHost() + ": " + e.getMessage()
              + ". Check the address, and that this machine is on the same network as the panel.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RegistrationException("interrupted while talking to the panel");
    }
  }

  private static JsonNode parse(String text) {
    try {
      JsonNode node = MAPPER.readTree(text == null ? "" : text);
      return node == null ? MAPPER.createObjectNode() : node;
    } catch (Exception e) {
      return MAPPER.createObjectNode();
    }
  }

  private static byte[] randomBytes(int count) {
    byte[] bytes = new byte[count];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }
}
