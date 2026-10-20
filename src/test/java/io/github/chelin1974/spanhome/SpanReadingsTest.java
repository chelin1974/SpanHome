package io.github.chelin1974.spanhome;

import static io.github.chelin1974.spanhome.PanelFixture.BESS;
import static io.github.chelin1974.spanhome.PanelFixture.FRIDGE;
import static io.github.chelin1974.spanhome.PanelFixture.HOT_TUB;
import static io.github.chelin1974.spanhome.PanelFixture.MID;
import static io.github.chelin1974.spanhome.PanelFixture.PANEL;
import static io.github.chelin1974.spanhome.PanelFixture.PV;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.spanhome.span.HomieTree;
import io.github.chelin1974.spanhome.span.SpanReadings;
import io.github.chelin1974.spanhome.span.SpanReadings.BatteryFlow;
import io.github.chelin1974.spanhome.span.SpanReadings.Grid;
import io.github.chelin1974.spanhome.span.SpanReadings.Relay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpanReadingsTest {

  private final HomieTree tree = PanelFixture.tree();

  @Test
  @DisplayName("a consuming circuit reports negative active-power; consumption is positive watts")
  void consumptionSign() {
    assertEquals(1450.5, SpanReadings.consumptionWatts(tree, HOT_TUB), 1e-9);
  }

  @Test
  @DisplayName("a backfeeding circuit (solar) reads as zero consumption")
  void backfeed() {
    assertEquals(0.0, SpanReadings.consumptionWatts(tree, PV), 1e-9);
  }

  @Test
  @DisplayName("lifetime consumption comes from exported-energy, in kWh")
  void energy() {
    assertEquals(123.456, SpanReadings.consumedKilowattHours(tree, HOT_TUB), 1e-9);
  }

  @Test
  @DisplayName("amps are reported as a magnitude")
  void current() {
    assertEquals(6.2, SpanReadings.currentAmps(tree, HOT_TUB), 1e-9);
  }

  @Test
  @DisplayName("panel voltage averages the two legs")
  void voltage() {
    assertEquals(120.0, SpanReadings.panelVoltage(tree), 1e-9);
  }

  @Test
  @DisplayName("a circuit is controllable only when relay-controllable and $settable agree")
  void controllable() {
    assertTrue(SpanReadings.relayControllable(tree, tree.device(HOT_TUB).orElseThrow()));
    assertFalse(SpanReadings.relayControllable(tree, tree.device(FRIDGE).orElseThrow()));

    tree.accept("ebus/5/" + HOT_TUB + "/switch/relay-controllable", "false");
    assertFalse(SpanReadings.relayControllable(tree, tree.device(HOT_TUB).orElseThrow()));
  }

  @Test
  @DisplayName("relay states parse, and anything unexpected is UNKNOWN")
  void relay() {
    assertEquals(Relay.CLOSED, SpanReadings.relay(tree, HOT_TUB));
    tree.accept("ebus/5/" + HOT_TUB + "/switch/relay", "OPEN");
    assertEquals(Relay.OPEN, SpanReadings.relay(tree, HOT_TUB));
    tree.accept("ebus/5/" + HOT_TUB + "/switch/relay", "STUCK");
    assertEquals(Relay.UNKNOWN, SpanReadings.relay(tree, HOT_TUB));
  }

  @Test
  @DisplayName("circuits are listed in breaker-space order")
  void order() {
    var circuits = SpanReadings.circuits(tree);
    assertEquals(PV, circuits.get(0).id()); // spaces 1,3
    assertEquals(FRIDGE, circuits.get(1).id()); // space 3
    assertEquals(HOT_TUB, circuits.get(2).id()); // spaces 17,19
  }

  @Test
  @DisplayName("the circuit name comes from info/name")
  void name() {
    assertEquals("Hot Tub", SpanReadings.name(tree, tree.device(HOT_TUB).orElseThrow()));
  }

  @Test
  @DisplayName("power-flows/battery positive means charging, on every firmware")
  void batteryFlow() {
    assertEquals(BatteryFlow.CHARGING, SpanReadings.batteryFlow(tree));
    tree.accept("ebus/5/" + PANEL + "/power-flows/battery", "-900");
    assertEquals(BatteryFlow.DISCHARGING, SpanReadings.batteryFlow(tree));
    tree.accept("ebus/5/" + PANEL + "/power-flows/battery", "12");
    assertEquals(BatteryFlow.IDLE, SpanReadings.batteryFlow(tree));
  }

  @Test
  @DisplayName("state of charge rounds and clamps to 0-100")
  void soc() {
    assertEquals(88, SpanReadings.stateOfCharge(tree, BESS).orElseThrow());
    tree.accept("ebus/5/" + BESS + "/soc/soc", "100.4");
    assertEquals(100, SpanReadings.stateOfCharge(tree, BESS).orElseThrow());
  }

  @Test
  @DisplayName("grid state follows the MID's islanding-state")
  void grid() {
    assertEquals(Grid.ON_GRID, SpanReadings.grid(tree));
    tree.accept("ebus/5/" + MID + "/grid/islanding-state", "OFF_GRID");
    assertEquals(Grid.OFF_GRID, SpanReadings.grid(tree));
  }

  @Test
  @DisplayName("reads the backup forecast in minutes")
  void forecast() {
    assertEquals(754, SpanReadings.backupMinutesRemaining(tree).orElseThrow());
  }
}
