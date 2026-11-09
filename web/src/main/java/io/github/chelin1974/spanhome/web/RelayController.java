package io.github.chelin1974.spanhome.web;

import io.github.chelin1974.spanhome.SpanHomeBridge;
import io.github.chelin1974.spanhome.SpanHomeConfig;
import io.github.chelin1974.spanhome.web.DashboardModel.RelayResult;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Switching a circuit from the dashboard.
 *
 * <p>This is the one route that changes something in the house, so it is guarded three ways,
 * all carried over from the bridge's own HTTP server unchanged:
 *
 * <ul>
 *   <li>the request must come from the home network, which {@link LocalNetworkFilter} does;
 *   <li>it must carry the household passcode in {@code X-Passcode}, compared in constant time;
 *   <li>five wrong passcodes from one address locks that address out for a while, so the
 *       passcode cannot be guessed at LAN speed.
 * </ul>
 *
 * <p>With no {@code dashboardPasscode} configured, switching is off entirely and the page is
 * view-only.
 */
@RestController
public class RelayController {

  private static final Logger log = LoggerFactory.getLogger(RelayController.class);

  static final int MAX_FAILURES = 5;
  static final Duration LOCKOUT = Duration.ofMinutes(5);

  private final SpanHomeBridge bridge;
  private final byte[] passcode;
  private final Map<String, Failures> failures = new ConcurrentHashMap<>();

  private static final class Failures {
    int count;
    Instant lockedUntil = Instant.EPOCH;
  }

  public RelayController(SpanHomeBridge bridge, SpanHomeConfig config) {
    this.bridge = bridge;
    String configured = config.dashboardPasscode;
    this.passcode =
        configured == null || configured.isBlank()
            ? null
            : configured.getBytes(StandardCharsets.UTF_8);
  }

  @PostMapping(
      path = "/api/circuits/{circuitId}/relay",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> relay(
      @PathVariable String circuitId,
      @RequestHeader(name = "X-Passcode", required = false) String given,
      @RequestBody(required = false) Map<String, Object> body,
      HttpServletRequest request) {

    if (passcode == null) {
      return ResponseEntity.status(403)
          .body(
              Map.of(
                  "error",
                  "Switching from the dashboard is off. Set dashboardPasscode in "
                      + "span-home.config.json to turn it on."));
    }

    String who = request.getRemoteAddr();
    Failures record = failures.computeIfAbsent(who, key -> new Failures());
    synchronized (record) {
      if (Instant.now().isBefore(record.lockedUntil)) {
        return ResponseEntity.status(429)
            .body(Map.of("error", "Too many wrong passcodes. Try again in a few minutes."));
      }
      if (given == null
          || !MessageDigest.isEqual(passcode, given.getBytes(StandardCharsets.UTF_8))) {
        record.count++;
        if (record.count >= MAX_FAILURES) {
          record.lockedUntil = Instant.now().plus(LOCKOUT);
          record.count = 0;
          log.warn(
              "dashboard: {} wrong passcodes from {}; locked out for {} minutes",
              MAX_FAILURES,
              who,
              LOCKOUT.toMinutes());
        }
        return ResponseEntity.status(401).body(Map.of("error", "Wrong passcode."));
      }
      record.count = 0;
    }

    Object on = body == null ? null : body.get("on");
    if (!(on instanceof Boolean wanted)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "Send {\"on\": true} or {\"on\": false}."));
    }

    return bridge
        .model()
        .map(
            model -> {
              RelayResult result = model.setRelay(circuitId, wanted);
              return switch (result) {
                case OK -> {
                  log.info(
                      "dashboard: {} turned circuit {} {}", who, circuitId, wanted ? "on" : "off");
                  yield ResponseEntity.ok(Map.<String, Object>of("ok", true));
                }
                case LOCKED ->
                    ResponseEntity.status(409)
                        .body(Map.<String, Object>of("error", "This circuit can't be switched."));
                case NOT_FOUND ->
                    ResponseEntity.status(404)
                        .body(Map.<String, Object>of("error", "No such circuit."));
                case FAILED ->
                    ResponseEntity.status(502)
                        .body(
                            Map.<String, Object>of(
                                "error", "The panel did not take the command."));
              };
            })
        .orElseGet(
            () ->
                ResponseEntity.status(503)
                    .body(
                        Map.of(
                            "error",
                            "The bridge has not finished reading the panels yet.")));
  }
}
