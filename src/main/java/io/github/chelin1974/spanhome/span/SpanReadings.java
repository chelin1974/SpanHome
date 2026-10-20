package io.github.chelin1974.spanhome.span;

import io.github.chelin1974.spanhome.span.HomieTree.Device;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What the SPAN properties mean, on top of {@link HomieTree}'s raw values.
 *
 * <p>Pure functions over a tree. The sign conventions here follow SPAN's
 * {@code power-and-energy-conventions.md}, which differ by surface, so they live in one place:
 *
 * <ul>
 *   <li>A circuit's {@code meter/active-power} is from the panel's side: <b>negative</b> while
 *       the circuit consumes, positive when it backfeeds (a solar inverter).
 *   <li>A circuit's consumption accumulates in {@code meter/exported-energy} (panel to circuit).
 *   <li>The panel's {@code power-flows/battery} is positive while the battery charges. It is the
 *       one battery-direction signal that did not change sign between firmware releases (the
 *       BESS {@code meter/active-power} flipped in r202639), so charging state is read from it.
 * </ul>
 */
public final class SpanReadings {

  /** Below this, a circuit reads as idle rather than "in use". Filters meter noise. */
  public static final double IN_USE_WATTS = 5.0;

  /** Below this, the battery reads as neither charging nor discharging. */
  public static final double BATTERY_IDLE_WATTS = 50.0;

  /** Battery below this percentage reports as low in HomeKit. */
  public static final double LOW_BATTERY_PERCENT = 20.0;

  public enum Relay {
    OPEN,
    CLOSED,
    UNKNOWN;

    static Relay parse(String value) {
      return switch (value == null ? "" : value.trim()) {
        case "OPEN" -> OPEN;
        case "CLOSED" -> CLOSED;
        default -> UNKNOWN;
      };
    }
  }

  public enum BatteryFlow {
    CHARGING,
    DISCHARGING,
    IDLE,
    UNKNOWN
  }

  public enum Grid {
    ON_GRID,
    OFF_GRID,
    UNKNOWN
  }

  private SpanReadings() {}

  // ----------------------------------------------------------------- circuits

  /** Circuits in panel order (by first breaker space), so the Home app lists them sensibly. */
  public static List<Device> circuits(HomieTree tree) {
    return tree.ofType(HomieTree.TYPE_CIRCUIT).stream()
        .sorted(Comparator.comparingInt((Device d) -> firstSpace(tree, d.id())).thenComparing(d -> name(tree, d)))
        .toList();
  }

  /** The user-assigned circuit name, falling back to the description name, then the ID. */
  public static String name(HomieTree tree, Device device) {
    return tree.value(device.id(), "info", "name")
        .map(String::trim)
        .filter(n -> !n.isEmpty())
        .or(() -> Optional.of(device.name().trim()).filter(n -> !n.isEmpty()))
        .orElse(device.id());
  }

  public static String spaces(HomieTree tree, String circuitId) {
    return tree.value(circuitId, "info", "spaces").orElse("");
  }

  static int firstSpace(HomieTree tree, String circuitId) {
    String spaces = spaces(tree, circuitId);
    try {
      return Integer.parseInt(spaces.split(",")[0].trim());
    } catch (RuntimeException e) {
      return Integer.MAX_VALUE;
    }
  }

  public static Relay relay(HomieTree tree, String circuitId) {
    return Relay.parse(tree.value(circuitId, "switch", "relay").orElse(null));
  }

  /**
   * Whether the panel will accept a relay command for this circuit.
   *
   * <p>Both signals must agree: {@code relay-controllable} and the per-circuit {@code $settable}
   * in the description. SPAN gates them together, and the commissioned PV and backup circuits
   * report neither from r202639.
   */
  public static boolean relayControllable(HomieTree tree, Device circuit) {
    boolean controllable =
        tree.value(circuit.id(), "switch", "relay-controllable").map(v -> v.trim().equals("true")).orElse(false);
    return controllable && circuit.isSettable("switch", "relay");
  }

  /** Watts the circuit is drawing. Never negative: a backfeeding circuit reads as 0. */
  public static double consumptionWatts(HomieTree tree, String circuitId) {
    return tree.number(circuitId, "meter", "active-power").map(p -> Math.max(0.0, -p)).orElse(0.0);
  }

  /** Total energy the circuit has drawn, in kWh. */
  public static double consumedKilowattHours(HomieTree tree, String circuitId) {
    return tree.number(circuitId, "meter", "exported-energy").map(wh -> Math.max(0.0, wh) / 1000.0).orElse(0.0);
  }

  public static double currentAmps(HomieTree tree, String circuitId) {
    return tree.number(circuitId, "meter", "current").map(Math::abs).orElse(0.0);
  }

  /** Average of the panel's two line voltages, or 0 when unknown. */
  public static double panelVoltage(HomieTree tree) {
    Optional<Double> a = tree.number(tree.rootId(), "meter", "voltage-a");
    Optional<Double> b = tree.number(tree.rootId(), "meter", "voltage-b");
    if (a.isPresent() && b.isPresent()) {
      return (a.get() + b.get()) / 2.0;
    }
    return a.or(() -> b).orElse(0.0);
  }

  // ------------------------------------------------------------------ battery

  public static Optional<Device> battery(HomieTree tree) {
    return tree.ofType(HomieTree.TYPE_BESS).stream().findFirst();
  }

  public static Optional<Device> mid(HomieTree tree) {
    return tree.ofType(HomieTree.TYPE_MID).stream().findFirst();
  }

  /** State of charge as a whole percentage, clamped to 0–100. */
  public static Optional<Integer> stateOfCharge(HomieTree tree, String bessId) {
    return tree.number(bessId, "soc", "soc").map(soc -> (int) Math.round(Math.max(0, Math.min(100, soc))));
  }

  public static BatteryFlow batteryFlow(HomieTree tree) {
    return tree.number(tree.rootId(), "power-flows", "battery")
        .map(watts -> {
          if (watts > BATTERY_IDLE_WATTS) {
            return BatteryFlow.CHARGING;
          }
          if (watts < -BATTERY_IDLE_WATTS) {
            return BatteryFlow.DISCHARGING;
          }
          return BatteryFlow.IDLE;
        })
        .orElse(BatteryFlow.UNKNOWN);
  }

  /** The MID's relay position: whether the home is connected to the grid. */
  public static Grid grid(HomieTree tree) {
    return mid(tree)
        .flatMap(mid -> tree.value(mid.id(), "grid", "islanding-state"))
        .map(value -> switch (value.trim()) {
          case "ON_GRID" -> Grid.ON_GRID;
          case "OFF_GRID" -> Grid.OFF_GRID;
          default -> Grid.UNKNOWN;
        })
        .orElse(Grid.UNKNOWN);
  }

  /** Minutes of backup left at the current draw, when the panel forecasts it. */
  public static Optional<Integer> backupMinutesRemaining(HomieTree tree) {
    return tree.number(tree.rootId(), "shed-forecast", "total-time-remaining").map(Double::intValue);
  }
}
