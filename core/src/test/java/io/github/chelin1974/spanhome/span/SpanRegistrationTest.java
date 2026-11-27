package io.github.chelin1974.spanhome.span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.chelin1974.spanhome.span.SpanRegistration.RegistrationException;
import io.github.chelin1974.spanhome.span.api.SpanPanelApi;
import io.github.chelin1974.spanhome.span.api.model.AuthIn;
import io.github.chelin1974.spanhome.span.api.model.AuthOut;
import io.github.chelin1974.spanhome.span.api.model.StatusV2Out;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

/** The generated SPAN API client and SpanRegistration's use of it, against a fake panel. */
class SpanRegistrationTest {

  private HttpServer server;
  private String host;
  private SpanPanelApi api;
  private final AtomicReference<String> lastBody = new AtomicReference<>();

  @BeforeEach
  void startFakePanel() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    host = "127.0.0.1:" + server.getAddress().getPort();
    api = SpanRegistration.api("http://" + host, HttpClient.newHttpClient());
  }

  @AfterEach
  void stopFakePanel() {
    server.stop(0);
  }

  private void answer(String path, int status, String contentType, String body) {
    server.createContext(path, exchange -> {
      lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      reply(exchange, status, contentType, body);
    });
  }

  private static void reply(HttpExchange exchange, int status, String contentType, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @Test
  void statusIsReadIntoItsRecord() {
    answer("/api/v2/status", 200, "application/json",
        "{\"serialNumber\":\"nj-2316-005k6\",\"firmwareVersion\":\"spanos2/r202645/04\","
            + "\"proximityProven\":true,\"hardwareVersion\":\"MAIN 32\",\"addedLater\":1}");

    StatusV2Out status = api.status();

    assertEquals("nj-2316-005k6", status.serialNumber());
    assertEquals("MAIN 32", status.hardwareVersion());
    assertTrue(status.proximityProven());
    assertTrue(SpanRegistration.proximityProven(api, host));
  }

  @Test
  void anErrorFromStatusCountsAsNoDoorPressesYet() {
    answer("/api/v2/status", 500, "application/json", "{\"detail\":\"busy\"}");

    assertFalse(SpanRegistration.proximityProven(api, host));
  }

  @Test
  void registerSendsOnlyTheFieldsItHasAndReadsAMillisecondTimestamp() {
    answer("/api/v2/auth/register", 200, "application/json",
        "{\"accessToken\":\"t\",\"tokenType\":\"bearer\",\"iatMs\":1795000000123,\"hostname\":\"span\","
            + "\"serialNumber\":\"nj-2316-005k6\",\"hopPassphrase\":null,\"ebusBrokerUsername\":\"nj-2316-005k6\","
            + "\"ebusBrokerPassword\":\"secret\",\"ebusBrokerHost\":\"span.local\",\"ebusBrokerMqttsPort\":8883,"
            + "\"ebusBrokerWsPort\":9001,\"ebusBrokerWssPort\":9002}");

    AuthOut reply = api.register(new AuthIn("span-home-test", "SpanHome HomeKit bridge", null, null, "pass phrase"));

    assertEquals(1795000000123L, reply.iatMs());
    assertEquals("secret", reply.ebusBrokerPassword());
    assertEquals(8883L, reply.ebusBrokerMqttsPort());
    assertNull(reply.hopPassphrase());
    String sent = lastBody.get();
    assertTrue(sent.contains("\"name\":\"span-home-test\""), sent);
    assertTrue(sent.contains("\"hopPassphrase\":\"pass phrase\""), sent);
    assertFalse(sent.contains("otp"), sent);
    assertFalse(sent.contains("null"), sent);
  }

  @Test
  void registerWithoutAPassphraseLeavesTheFieldOut() {
    answer("/api/v2/auth/register", 422, "application/json", "{\"detail\":\"no proof of proximity\"}");

    RestClientResponseException e = assertThrows(RestClientResponseException.class,
        () -> api.register(new AuthIn("span-home-test", null, null, null, null)));

    assertEquals("{\"name\":\"span-home-test\"}", lastBody.get());
    assertEquals(422, e.getStatusCode().value());
    assertEquals("registration failed with HTTP 422: no proof of proximity",
        SpanRegistration.describeFailure(e.getStatusCode().value(), e.getResponseBodyAsString(), false));
  }

  @Test
  void aRejectedPassphraseIsExplained() {
    assertEquals(
        "the panel rejected the passphrase (HTTP 401: bad passphrase). Copy it again from the SPAN Home app.",
        SpanRegistration.describeFailure(401, "{\"detail\":\"bad passphrase\"}", true));
    assertEquals(
        "the panel did not accept the door-press proof (HTTP 403). Press the door switch three times and retry.",
        SpanRegistration.describeFailure(403, "not json", false));
  }

  @Test
  void theCaCertificateIsDownloadedAsText() {
    String pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";
    answer(SpanRegistration.CA_PATH, 200, "application/x-pem-file", pem);

    assertEquals(pem, new SpanRegistration().downloadCa(host));
  }

  @Test
  void aRateLimitedCaDownloadSaysToWait() {
    answer(SpanRegistration.CA_PATH, 429, "application/json", "{\"detail\":\"Too Many Requests\"}");

    RegistrationException e = assertThrows(RegistrationException.class, () -> new SpanRegistration().downloadCa(host));

    assertTrue(e.getMessage().contains("rate-limited"), e.getMessage());
  }

  @Test
  void anUnreachablePanelIsReportedAsSuch() {
    server.stop(0);

    RegistrationException e = assertThrows(RegistrationException.class, () -> new SpanRegistration().downloadCa(host));

    assertTrue(e.getMessage().startsWith("could not reach " + host), e.getMessage());
  }
}
