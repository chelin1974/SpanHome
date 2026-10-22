package io.github.chelin1974.spanhome.web;

import io.github.chelin1974.spanhome.homekit.CircuitOutlet;
import io.github.chelin1974.spanhome.span.HomieTree;
import io.github.chelin1974.spanhome.span.HomieTree.Device;
import io.github.chelin1974.spanhome.span.SpanReadings;
import io.github.chelin1974.spanhome.span.SpanReadings.Relay;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * What the dashboard and the metrics exporter read: every panel's circuits, totals and battery,
 * straight from the live {@link HomieTree}s, plus switching through the same {@link CircuitOutlet}s
 * HomeKit uses, so both paths obey the same switchable and read-only rules.
 */
public final class DashboardModel {

  /**
   * One panel as the bridge set it up.
   *
   * @param label the panel's short name from the config ("Main")
   * @param outlets circuit ID to its HomeKit outlet; hidden circuits have none
   * @param hidden IDs of circuits listed in {@code hiddenCircuits}
   * @param connected whether the MQTT connection to the panel is up
   */
  public record PanelSource(
      String label, HomieTree tree, Map<String, CircuitOutlet> outlets, Set<String> hidden, BooleanSupplier connected) {}

  public enum RelayResult {
    OK,
    NOT_FOUND,
    LOCKED,
    FAILED
  }

  private final List<PanelSource> panels;

  public DashboardModel(List<PanelSource> panels) {
    this.panels = List.copyOf(panels);
  }

  public List<PanelSource> panels() {
    return panels;
  }

  /** Every panel, circuit and battery reading, shaped for the page. */
  public Map<String, Object> state(boolean switchingEnabled) {
    List<Object> panelViews = new ArrayList<>();
    double house = 0;
    for (PanelSource panel : panels) {
      HomieTree tree = panel.tree();
      double draw = SpanReadings.panelConsumptionWatts(tree);
      house += draw;

      List<Object> circuits = new ArrayList<>();
      for (Device circuit : SpanReadings.circuits(tree)) {
        CircuitOutlet outlet = panel.outlets().get(circuit.id());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", circuit.id());
        c.put("name", outlet != null ? outlet.displayName() : SpanReadings.name(tree, circuit));
        c.put("spaces", spaces(SpanReadings.spaces(tree, circuit.id())));
        c.put("watts", round1(SpanReadings.consumptionWatts(tree, circuit.id())));
        c.put("on", SpanReadings.relay(tree, circuit.id()) == Relay.CLOSED);
        c.put("hidden", panel.hidden().contains(circuit.id()));
        c.put("locked", !SpanReadings.relayControllable(tree, circuit));
        c.put("switchable", outlet != null && outlet.isSwitchable());
        circuits.add(c);
      }

      Map<String, Object> p = new LinkedHashMap<>();
      p.put("label", panel.label());
      p.put("serial", tree.rootId());
      p.put("model", tree.value(tree.rootId(), "info", "model").orElse(null));
      p.put("firmware", tree.value(tree.rootId(), "info", "firmware-version").orElse(null));
      p.put("connected", panel.connected().getAsBoolean());
      p.put("volts", round1(SpanReadings.panelVoltage(tree)));
      p.put("watts", round1(draw));
      p.put("circuits", circuits);
      p.put("battery", battery(tree).orElse(null));
      panelViews.add(p);
    }

    Map<String, Object> state = new LinkedHashMap<>();
    state.put("time", System.currentTimeMillis());
    state.put("houseWatts", round1(house));
    state.put("switching", switchingEnabled);
    state.put("panels", panelViews);
    return state;
  }

  private static Optional<Map<String, Object>> battery(HomieTree tree) {
    return SpanReadings.battery(tree).map(bess -> {
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("percent", SpanReadings.stateOfCharge(tree, bess.id()).orElse(null));
      b.put("flow", SpanReadings.batteryFlow(tree).name().toLowerCase());
      b.put("grid", SpanReadings.grid(tree).name().toLowerCase());
      b.put("backupMinutes", SpanReadings.backupMinutesRemaining(tree).orElse(null));
      return b;
    });
  }

  /** Switches a circuit through its HomeKit outlet, so Home and the page stay in step. */
  public RelayResult setRelay(String circuitId, boolean on) {
    for (PanelSource panel : panels) {
      CircuitOutlet outlet = panel.outlets().get(circuitId);
      if (outlet == null) {
        continue;
      }
      if (!outlet.isSwitchable()) {
        return RelayResult.LOCKED;
      }
      try {
        outlet.setPowerState(on);
        return RelayResult.OK;
      } catch (Exception e) {
        return RelayResult.FAILED;
      }
    }
    return RelayResult.NOT_FOUND;
  }

  static List<Integer> spaces(String raw) {
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> s.matches("\\d+"))
        .map(Integer::valueOf)
        .toList();
  }

  static double round1(double value) {
    return Math.round(value * 10) / 10.0;
  }
}
