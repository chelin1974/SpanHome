package io.github.chelin1974.spanhome.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The graphs' data, and the reason when there isn't any.
 *
 * <p>A missing or unreachable Prometheus is not an error as far as the page is concerned: the
 * bridge works perfectly well without history, so the routes answer 200 with
 * {@code {"available": false, "reason": ...}} and the page shows an explanation rather than
 * failing. That is what the bridge's own HTTP server did, and the page already understands
 * that shape, so it is kept exactly.
 *
 * <p>Wrapping it here rather than injecting a possibly-null {@link PrometheusHistory} keeps
 * the "no Prometheus" case out of the controllers, where it was duplicated once per route.
 */
public class HistoryService {

  private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

  private static final String NOT_CONFIGURED =
      "No Prometheus is configured, so there is no history to show.";

  private final PrometheusHistory history;

  /** @param base the Prometheus base URL, or null when none is configured */
  public HistoryService(URI base) {
    this.history = base == null ? null : new PrometheusHistory(base);
  }

  public boolean configured() {
    return history != null;
  }

  /** The last 24 hours across every circuit. */
  public Map<String, Object> last24Hours(long nowMillis) {
    return answer(() -> history.last24Hours(nowMillis));
  }

  /** One circuit's longer history, by the panel legs that make it up. */
  public Map<String, Object> circuitDetail(List<String> legs, long nowMillis) {
    return answer(() -> history.circuitDetail(legs, nowMillis));
  }

  private interface Query {
    Map<String, Object> run() throws Exception;
  }

  private Map<String, Object> answer(Query query) {
    if (history == null) {
      return Map.of("available", false, "reason", NOT_CONFIGURED);
    }
    try {
      Map<String, Object> body = new LinkedHashMap<>(query.run());
      body.put("available", true);
      return body;
    } catch (Exception e) {
      // Logged as well as returned: the page shows the reason to whoever is looking at it,
      // and the log is where you find out it has been failing all afternoon.
      log.warn("Prometheus at {} did not answer: {}", history.base(), e.getMessage());
      return Map.of(
          "available",
          false,
          "reason",
          "Prometheus at " + history.base() + " did not answer (" + e.getMessage() + ").");
    }
  }
}
