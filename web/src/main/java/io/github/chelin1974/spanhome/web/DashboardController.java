package io.github.chelin1974.spanhome.web;

import io.github.chelin1974.spanhome.SpanHomeBridge;
import io.github.chelin1974.spanhome.SpanHomeConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Breaker Board page and the JSON it reads.
 *
 * <p>Same paths and same response shapes as the bridge's own HTTP server, because the page is
 * the same file and already knows what to expect. The page itself lives in core, on the
 * classpath at {@code /dashboard/index.html}, so both flavours serve one copy.
 */
@RestController
public class DashboardController {

  private final SpanHomeBridge bridge;
  private final SpanHomeConfig config;
  private final HistoryService history;

  public DashboardController(
      SpanHomeBridge bridge, SpanHomeConfig config, HistoryService history) {
    this.bridge = bridge;
    this.config = config;
    this.history = history;
  }

  /** Switching needs the household passcode; with none set the page is view-only. */
  private boolean switchingEnabled() {
    return config.dashboardPasscode != null && !config.dashboardPasscode.isBlank();
  }

  @GetMapping(path = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<byte[]> page() throws IOException {
    try (InputStream in = DashboardController.class.getResourceAsStream("/dashboard/index.html")) {
      if (in == null) {
        return ResponseEntity.internalServerError()
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"The dashboard page is missing from the jar.\"}"
                .getBytes(StandardCharsets.UTF_8));
      }
      return ResponseEntity.ok()
          .contentType(MediaType.TEXT_HTML)
          .cacheControl(CacheControl.noStore())
          .body(in.readAllBytes());
    }
  }

  /**
   * The current reading of every panel and circuit.
   *
   * <p>Answers 503 before the panels have been discovered, rather than an empty page: the
   * bridge starts in the background and this is the honest answer while it is still coming up.
   */
  @GetMapping(path = "/api/state", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> state() {
    return bridge
        .model()
        .map(model -> ResponseEntity.ok(model.state(switchingEnabled())))
        .orElseGet(
            () ->
                ResponseEntity.status(503)
                    .body(
                        Map.of(
                            "error",
                            "The bridge has not finished reading the panels yet. "
                                + "This page will work once it has.")));
  }

  @GetMapping(path = "/api/history", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> history() {
    return history.last24Hours(System.currentTimeMillis());
  }

  @GetMapping(path = "/api/circuits/{circuitId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> circuit(@PathVariable String circuitId) {
    Optional<List<String>> legs =
        bridge.model().flatMap(model -> model.legsOf(circuitId));
    if (legs.isEmpty()) {
      return ResponseEntity.status(404).body(Map.of("error", "No such circuit."));
    }
    return ResponseEntity.ok(history.circuitDetail(legs.get(), System.currentTimeMillis()));
  }
}
