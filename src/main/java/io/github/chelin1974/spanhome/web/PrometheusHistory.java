package io.github.chelin1974.spanhome.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the last 24 hours of wattage back from Prometheus, for the dashboard's graphs.
 *
 * <p>One range query covers every circuit, panel and the house total: 15-minute averages of the
 * {@code span_*_power} gauges the bridge exports. The metric name is matched with a pattern, so
 * it works whether Prometheus adds the {@code _watts} unit suffix (its default) or not.
 *
 * <p>Results are cached for a minute; the graphs move in 15-minute steps anyway.
 */
public final class PrometheusHistory {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static final int STEP_SECONDS = 15 * 60;
  public static final int POINTS = 24 * 60 * 60 / STEP_SECONDS + 1;
  static final String QUERY =
      "avg_over_time({__name__=~\"span_(circuit|panel|house)_power(_watts)?\"}[15m])";

  private final URI base;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private volatile Map<String, Object> cached;
  private volatile long cachedAt;
  private final Map<String, Object[]> detailCache = new java.util.concurrent.ConcurrentHashMap<>();

  /** Days of history behind a circuit's detail view. */
  public static final int DETAIL_DAYS = 30;

  public PrometheusHistory(URI base) {
    this.base = base;
  }

  public URI base() {
    return base;
  }

  /**
   * {@code {stepSeconds, start, end, series: {key: [97 values or null]}}}, where key is a
   * circuit ID, {@code panel:<serial>} or {@code house}. Throws when Prometheus can't answer.
   */
  public synchronized Map<String, Object> last24Hours(long nowMillis) throws Exception {
    if (cached != null && nowMillis - cachedAt < 60_000) {
      return cached;
    }
    // End at the current second: rounding down to the minute can fall just before the newest
    // sample and drop it.
    long end = nowMillis / 1000;
    long start = end - (long) (POINTS - 1) * STEP_SECONDS;
    String url = base.toString().replaceAll("/+$", "") + "/api/v1/query_range"
        + "?query=" + URLEncoder.encode(QUERY, StandardCharsets.UTF_8)
        + "&start=" + start + "&end=" + end + "&step=" + STEP_SECONDS;
    HttpResponse<String> response = http.send(
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("Prometheus answered HTTP " + response.statusCode());
    }
    Map<String, Object> result = parse(MAPPER.readTree(response.body()), start, end);
    cached = result;
    cachedAt = nowMillis;
    return result;
  }

  /**
   * One circuit's last 30 days, for its detail view: 15-minute average and peak draw (summed
   * across the legs of a combined circuit), and energy over the last day and the 30 days.
   * The page folds the averages into a time-of-day profile in the viewer's own time zone.
   *
   * @param legs circuit IDs; the caller checks they are real circuits, as they go into PromQL
   */
  public Map<String, Object> circuitDetail(List<String> legs, long nowMillis) throws Exception {
    String key = String.join("|", legs);
    Object[] hit = detailCache.get(key);
    if (hit != null && nowMillis - (Long) hit[0] < 60_000) {
      @SuppressWarnings("unchecked")
      Map<String, Object> cachedDetail = (Map<String, Object>) hit[1];
      return cachedDetail;
    }
    long end = nowMillis / 1000;
    int points = DETAIL_DAYS * 24 * 60 * 60 / STEP_SECONDS + 1;
    long start = end - (long) (points - 1) * STEP_SECONDS;
    String power = "sum({__name__=~\"span_circuit_power(_watts)?\",circuit_id=~\"" + key + "\"})";
    String energy = "sum(increase({__name__=~\"span_circuit_energy.*_total\",circuit_id=~\"" + key + "\"}[%s])) / 3.6e6";

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("stepSeconds", STEP_SECONDS);
    detail.put("start", start * 1000);
    detail.put("end", end * 1000);
    detail.put("avg", Arrays.asList(range("avg_over_time(" + power + "[15m:15s])", start, end, points)));
    detail.put("max", Arrays.asList(range("max_over_time(" + power + "[15m:15s])", start, end, points)));
    detail.put("kwh24h", instant(String.format(energy, "24h"), end));
    detail.put("kwh30d", instant(String.format(energy, DETAIL_DAYS + "d"), end));
    detailCache.put(key, new Object[] {nowMillis, detail});
    return detail;
  }

  private Double[] range(String query, long start, long end, int points) throws Exception {
    JsonNode body = get("/api/v1/query_range?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
        + "&start=" + start + "&end=" + end + "&step=" + STEP_SECONDS);
    return values(body, start, points);
  }

  private Double instant(String query, long time) throws Exception {
    JsonNode result = get("/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&time=" + time)
        .path("data").path("result");
    if (result.isEmpty()) {
      return null;
    }
    try {
      double v = Double.parseDouble(result.get(0).path("value").get(1).asText());
      return Double.isFinite(v) ? Math.round(Math.max(0, v) * 100) / 100.0 : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private JsonNode get(String pathAndQuery) throws Exception {
    HttpResponse<String> response = http.send(
        HttpRequest.newBuilder(URI.create(base.toString().replaceAll("/+$", "") + pathAndQuery))
            .timeout(Duration.ofSeconds(20)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("Prometheus answered HTTP " + response.statusCode());
    }
    JsonNode body = MAPPER.readTree(response.body());
    if (!"success".equals(body.path("status").asText())) {
      throw new IllegalStateException("Prometheus query failed: " + body.path("error").asText("unknown error"));
    }
    return body;
  }

  /** The first series of a query_range matrix as a fixed-length array, null where empty. Pure. */
  static Double[] values(JsonNode body, long start, int points) {
    Double[] out = new Double[points];
    JsonNode result = body.path("data").path("result");
    if (result.isEmpty()) {
      return out;
    }
    for (JsonNode pair : result.get(0).path("values")) {
      int index = (int) Math.round(((long) pair.get(0).asDouble() - start) / (double) STEP_SECONDS);
      if (index < 0 || index >= points) {
        continue;
      }
      try {
        double v = Double.parseDouble(pair.get(1).asText());
        if (Double.isFinite(v)) {
          out[index] = round1(Math.max(0, v));
        }
      } catch (NumberFormatException e) {
        // skip
      }
    }
    return out;
  }

  /** Turns a query_range matrix into fixed-length series keyed for the page. Pure, for tests. */
  static Map<String, Object> parse(JsonNode body, long start, long end) {
    if (!"success".equals(body.path("status").asText())) {
      throw new IllegalStateException("Prometheus query failed: " + body.path("error").asText("unknown error"));
    }
    Map<String, Double[]> series = new LinkedHashMap<>();
    for (JsonNode result : body.path("data").path("result")) {
      JsonNode labels = result.path("metric");
      String key = labels.hasNonNull("circuit_id") ? labels.get("circuit_id").asText()
          : labels.hasNonNull("panel_serial") ? "panel:" + labels.get("panel_serial").asText()
          : "house";
      Double[] values = series.computeIfAbsent(key, k -> new Double[POINTS]);
      for (JsonNode pair : result.path("values")) {
        long t = (long) pair.get(0).asDouble();
        int index = (int) Math.round((t - start) / (double) STEP_SECONDS);
        if (index < 0 || index >= POINTS) {
          continue;
        }
        double v;
        try {
          v = Double.parseDouble(pair.get(1).asText());
        } catch (NumberFormatException e) {
          continue;
        }
        if (Double.isFinite(v)) {
          // A renamed circuit can briefly appear as two series; keep the larger reading.
          values[index] = values[index] == null ? round1(v) : Math.max(values[index], round1(v));
        }
      }
    }
    Map<String, List<Double>> out = new LinkedHashMap<>();
    series.forEach((k, v) -> out.put(k, new ArrayList<>(Arrays.asList(v))));
    Map<String, Object> shaped = new LinkedHashMap<>();
    shaped.put("stepSeconds", STEP_SECONDS);
    shaped.put("start", start * 1000);
    shaped.put("end", end * 1000);
    shaped.put("series", out);
    return shaped;
  }

  private static double round1(double v) {
    return Math.round(v * 10) / 10.0;
  }
}
