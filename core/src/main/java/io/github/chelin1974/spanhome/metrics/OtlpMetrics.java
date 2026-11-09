package io.github.chelin1974.spanhome.metrics;

import io.github.chelin1974.spanhome.span.HomieTree;
import io.github.chelin1974.spanhome.span.HomieTree.Device;
import io.github.chelin1974.spanhome.span.SpanReadings;
import io.github.chelin1974.spanhome.span.SpanReadings.BatteryFlow;
import io.github.chelin1974.spanhome.span.SpanReadings.Grid;
import io.github.chelin1974.spanhome.span.SpanReadings.Relay;
import io.github.chelin1974.spanhome.web.DashboardModel;
import io.github.chelin1974.spanhome.web.DashboardModel.PanelSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the panels' live readings into an OTLP metrics request (the JSON encoding of
 * {@code ExportMetricsServiceRequest}).
 *
 * <p>Pure: no network, no clock of its own, so the exact payload is easy to test. Metric names
 * follow OpenTelemetry's dotted style; Prometheus' OTLP receiver turns {@code span.circuit.power}
 * with unit {@code W} into {@code span_circuit_power_watts}, and attribute {@code circuit.id}
 * into label {@code circuit_id}. The two on/off gauges carry no unit: Prometheus would turn the
 * dimensionless unit {@code 1} into a misleading {@code _ratio} suffix. Energy goes out in
 * joules, the base unit: Prometheus does not translate {@code Wh} and would put it in the name raw.
 *
 * <table>
 *   <caption>Metrics</caption>
 *   <tr><th>Name</th><th>Kind</th><th>Unit</th><th>Attributes</th></tr>
 *   <tr><td>span.circuit.power</td><td>gauge</td><td>W</td><td>panel, circuit, circuit.id, circuit.spaces</td></tr>
 *   <tr><td>span.circuit.relay</td><td>gauge (1 = on)</td><td>none</td><td>same</td></tr>
 *   <tr><td>span.circuit.energy</td><td>cumulative sum</td><td>J</td><td>same</td></tr>
 *   <tr><td>span.panel.power</td><td>gauge</td><td>W</td><td>panel, panel.serial</td></tr>
 *   <tr><td>span.panel.voltage</td><td>gauge</td><td>V</td><td>panel, panel.serial</td></tr>
 *   <tr><td>span.house.power</td><td>gauge</td><td>W</td><td>none</td></tr>
 *   <tr><td>span.battery.charge</td><td>gauge</td><td>%</td><td>panel</td></tr>
 *   <tr><td>span.battery.power</td><td>gauge, + charging</td><td>W</td><td>panel</td></tr>
 *   <tr><td>span.grid.connected</td><td>gauge (1 = on grid)</td><td>none</td><td>panel</td></tr>
 * </table>
 */
public final class OtlpMetrics {

  /** OTLP's AGGREGATION_TEMPORALITY_CUMULATIVE. */
  static final int CUMULATIVE = 2;

  /** One reading. {@code cumulative} marks a monotonic sum rather than a gauge. */
  public record Point(String name, String unit, String description, boolean cumulative, double value, Map<String, String> attributes) {}

  private OtlpMetrics() {}

  /** Every reading worth keeping history for, across all panels. */
  public static List<Point> collect(DashboardModel model) {
    List<Point> points = new ArrayList<>();
    double house = 0;
    for (PanelSource panel : model.panels()) {
      HomieTree tree = panel.tree();
      if (!panel.connected().getAsBoolean()) {
        continue; // a stale reading is worse than a gap in the graph
      }
      String label = panel.label();
      for (Device circuit : SpanReadings.circuits(tree)) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("panel", label);
        attrs.put("circuit", SpanReadings.name(tree, circuit));
        attrs.put("circuit.id", circuit.id());
        attrs.put("circuit.spaces", SpanReadings.spaces(tree, circuit.id()));
        points.add(new Point("span.circuit.power", "W", "Power the circuit is drawing", false,
            SpanReadings.consumptionWatts(tree, circuit.id()), attrs));
        points.add(new Point("span.circuit.relay", "", "Circuit relay state: 1 closed (on), 0 open (off)", false,
            SpanReadings.relay(tree, circuit.id()) == Relay.CLOSED ? 1 : 0, attrs));
        tree.number(circuit.id(), "meter", "exported-energy").ifPresent(wh ->
            points.add(new Point("span.circuit.energy", "J", "Energy the circuit has drawn", true, Math.max(0, wh) * 3600, attrs)));
      }

      double draw = SpanReadings.panelConsumptionWatts(tree);
      house += draw;
      Map<String, String> panelAttrs = Map.of("panel", label, "panel.serial", tree.rootId());
      points.add(new Point("span.panel.power", "W", "Sum of the panel's circuit draw", false, draw, panelAttrs));
      double volts = SpanReadings.panelVoltage(tree);
      if (volts > 0) {
        points.add(new Point("span.panel.voltage", "V", "Average of the panel's two line voltages", false, volts, panelAttrs));
      }

      SpanReadings.battery(tree).ifPresent(bess -> {
        Map<String, String> batteryAttrs = Map.of("panel", label);
        SpanReadings.stateOfCharge(tree, bess.id()).ifPresent(soc ->
            points.add(new Point("span.battery.charge", "%", "Backup battery state of charge", false, soc, batteryAttrs)));
        tree.number(tree.rootId(), "power-flows", "battery").ifPresent(watts ->
            points.add(new Point("span.battery.power", "W", "Battery power; positive while charging", false, watts, batteryAttrs)));
        Grid grid = SpanReadings.grid(tree);
        if (grid != Grid.UNKNOWN) {
          points.add(new Point("span.grid.connected", "", "1 while on grid, 0 during an outage", false,
              grid == Grid.ON_GRID ? 1 : 0, batteryAttrs));
        }
      });
    }
    if (points.stream().anyMatch(p -> p.name().equals("span.panel.power"))) {
      points.add(new Point("span.house.power", "W", "Sum of every panel's circuit draw", false, house, Map.of()));
    }
    return points;
  }

  /**
   * The OTLP JSON request. Points with the same name become one metric with several data points.
   *
   * @param startNanos when cumulative sums started counting (the bridge's start)
   */
  public static Map<String, Object> request(
      List<Point> points, long nowNanos, long startNanos, Map<String, String> resource, String version) {
    Map<String, Map<String, Object>> metrics = new LinkedHashMap<>();
    for (Point point : points) {
      Map<String, Object> metric = metrics.computeIfAbsent(point.name(), name -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("unit", point.unit());
        m.put("description", point.description());
        Map<String, Object> data = new LinkedHashMap<>();
        if (point.cumulative()) {
          data.put("aggregationTemporality", CUMULATIVE);
          data.put("isMonotonic", true);
          data.put("dataPoints", new ArrayList<>());
          m.put("sum", data);
        } else {
          data.put("dataPoints", new ArrayList<>());
          m.put("gauge", data);
        }
        return m;
      });
      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) metric.get(point.cumulative() ? "sum" : "gauge");
      @SuppressWarnings("unchecked")
      List<Object> dataPoints = (List<Object>) data.get("dataPoints");
      Map<String, Object> dp = new LinkedHashMap<>();
      dp.put("attributes", attributes(point.attributes()));
      if (point.cumulative()) {
        dp.put("startTimeUnixNano", Long.toString(startNanos));
      }
      // OTLP JSON carries 64-bit integers as strings.
      dp.put("timeUnixNano", Long.toString(nowNanos));
      dp.put("asDouble", point.value());
      dataPoints.add(dp);
    }

    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("scope", Map.of("name", "io.github.chelin1974.spanhome", "version", version));
    scope.put("metrics", new ArrayList<>(metrics.values()));

    Map<String, Object> resourceMetrics = new LinkedHashMap<>();
    resourceMetrics.put("resource", Map.of("attributes", attributes(resource)));
    resourceMetrics.put("scopeMetrics", List.of(scope));
    return Map.of("resourceMetrics", List.of(resourceMetrics));
  }

  private static List<Object> attributes(Map<String, String> values) {
    List<Object> out = new ArrayList<>();
    values.forEach((key, value) -> out.add(Map.of("key", key, "value", Map.of("stringValue", value))));
    return out;
  }
}
