package io.github.chelin1974.spanhome.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.chelin1974.spanhome.SpanHomeBridge;
import io.github.chelin1974.spanhome.SpanHomeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The routes the Breaker Board page calls, and the guard in front of them.
 *
 * <p>The page is the same file the CLI flavour serves and already knows what to expect, so
 * what matters here is that the paths, statuses and body shapes match what the bridge's own
 * HTTP server answered.
 *
 * <p>The bridge is a stub that has not discovered any panels, which is deliberately the
 * awkward state: the service comes up before the panels are read, and the routes have to say
 * so rather than serve an empty page.
 */
@WebMvcTest(
    controllers = {DashboardController.class, HealthzController.class, RelayController.class})
@Import({LocalNetworkFilter.class, DashboardRoutesTest.Fixtures.class})
class DashboardRoutesTest {

  @TestConfiguration
  static class Fixtures {

    @Bean
    SpanHomeConfig spanHomeConfig() throws Exception {
      java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("span-web-test");
      java.nio.file.Path file = dir.resolve("span-home.config.json");
      java.nio.file.Files.writeString(
          file,
          """
          {
            "panels": [{ "serial": "nj-0000-00000", "name": "Main" }],
            "dashboardPasscode": "letmein",
            "dashboardPort": 8089
          }
          """);
      return SpanHomeConfig.load(file);
    }

    @Bean
    SpanHomeBridge spanHomeBridge(SpanHomeConfig config) {
      return new SpanHomeBridge(config);
    }

    /** No Prometheus, so the history routes answer with a reason rather than failing. */
    @Bean
    HistoryService historyService() {
      return new HistoryService(null);
    }
  }

  @Autowired MockMvc mvc;

  // ----------------------------------------------------------------- the page

  @Test
  @DisplayName("the dashboard page is served from the classpath")
  void page() throws Exception {
    mvc.perform(get("/").with(request -> {
          request.setRemoteAddr("192.168.7.20");
          return request;
        }))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("<html")));
  }

  // -------------------------------------------------------- the local gate

  /**
   * The dashboard can switch circuits in the house and binds the host's network, so it would
   * be reachable from the internet if a router ever forwarded the port by mistake.
   */
  @Test
  @DisplayName("a request from outside the home network is refused")
  void refusesTheInternet() throws Exception {
    mvc.perform(get("/api/state").with(request -> {
          request.setRemoteAddr("203.0.113.7");
          return request;
        }))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value(
            "This dashboard only answers devices on the home network."));
  }

  /**
   * Answered before that check, on purpose: a liveness probe that got a 403 would fail forever
   * and restart the container in a loop, which is worse than disclosing a status word.
   */
  @Test
  @DisplayName("healthz answers even from outside the home network")
  void healthzIsExemptFromTheLocalCheck() throws Exception {
    mvc.perform(get("/healthz").with(request -> {
          request.setRemoteAddr("203.0.113.7");
          return request;
        }))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.discoverable").value(true));
  }

  // ------------------------------------------------------------------- state

  @Test
  @DisplayName("state says so while the panels are still being read")
  void stateBeforeDiscovery() throws Exception {
    mvc.perform(from(get("/api/state"), "192.168.7.20"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  @DisplayName("history explains itself when no Prometheus is configured")
  void historyWithoutPrometheus() throws Exception {
    mvc.perform(from(get("/api/history"), "192.168.7.20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.reason").value(
            "No Prometheus is configured, so there is no history to show."));
  }

  @Test
  @DisplayName("an unknown circuit is a 404")
  void unknownCircuit() throws Exception {
    mvc.perform(from(get("/api/circuits/nope"), "192.168.7.20"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("No such circuit."));
  }

  // ------------------------------------------------------------------- relay

  @Test
  @DisplayName("switching without the passcode is refused")
  void relayWithoutPasscode() throws Exception {
    mvc.perform(from(post("/api/circuits/abc/relay"), "192.168.7.31")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"on\":true}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Wrong passcode."));
  }

  @Test
  @DisplayName("switching with the wrong passcode is refused")
  void relayWithWrongPasscode() throws Exception {
    mvc.perform(from(post("/api/circuits/abc/relay"), "192.168.7.32")
            .header("X-Passcode", "guess")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"on\":true}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("a body that is not {\"on\": true|false} is a 400")
  void relayWithABadBody() throws Exception {
    mvc.perform(from(post("/api/circuits/abc/relay"), "192.168.7.33")
            .header("X-Passcode", "letmein")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"state\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Send {\"on\": true} or {\"on\": false}."));
  }

  /** The passcode is right, so the only thing left in the way is discovery. */
  @Test
  @DisplayName("the right passcode gets past the guard")
  void relayWithTheRightPasscode() throws Exception {
    mvc.perform(from(post("/api/circuits/abc/relay"), "192.168.7.34")
            .header("X-Passcode", "letmein")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"on\":true}"))
        .andExpect(status().isServiceUnavailable());
  }

  /** Five wrong tries lock the address out, so the passcode cannot be guessed at LAN speed. */
  @Test
  @DisplayName("five wrong passcodes lock the address out, beating even a correct one")
  void relayLocksOut() throws Exception {
    for (int attempt = 0; attempt < RelayController.MAX_FAILURES; attempt++) {
      mvc.perform(from(post("/api/circuits/abc/relay"), "192.168.7.35")
              .header("X-Passcode", "guess")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"on\":true}"))
          .andExpect(status().isUnauthorized());
    }

    mvc.perform(from(post("/api/circuits/abc/relay"), "192.168.7.35")
            .header("X-Passcode", "letmein")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"on\":true}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error").value(
            "Too many wrong passcodes. Try again in a few minutes."));
  }

  /** Every request needs an address the local-network filter accepts. */
  private static <T extends org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>
      T from(T builder, String address) {
    builder.with(request -> {
      request.setRemoteAddr(address);
      return request;
    });
    return builder;
  }
}
