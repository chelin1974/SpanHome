package io.github.chelin1974.spanhome;

import static io.github.chelin1974.spanhome.PanelFixture.HOT_TUB;
import static io.github.chelin1974.spanhome.PanelFixture.PANEL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.chelin1974.spanhome.homekit.CircuitOutlet;
import io.github.chelin1974.spanhome.homekit.PowerSensor;
import io.github.chelin1974.spanhome.span.HomieTree;
import io.github.chelin1974.spanhome.span.SpanReadings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PowerTest {

  @Test
  @DisplayName("panel draw sums the circuits, leaving out commissioned solar and battery feeds")
  void panelDraw() {
    HomieTree tree = PanelFixture.tree();
    // Hot tub 1450.5 W + fridge 2.1 W; the PV circuit is backfeeding and excluded anyway.
    assertEquals(1452.6, SpanReadings.panelConsumptionWatts(tree), 1e-9);

    tree.accept("ebus/5/" + PanelFixture.PV + "/meter/active-power", "-800");
    assertEquals(1452.6, SpanReadings.panelConsumptionWatts(tree), 1e-9, "a commissioned feed never counts");
  }

  @Test
  @DisplayName("watts become whole lux, with HomeKit's floor for idle")
  void lux() {
    assertEquals(1451.0, PowerSensor.toLux(1450.5), 1e-9);
    assertEquals(0.0001, PowerSensor.toLux(0), 1e-12);
    assertEquals(0.0001, PowerSensor.toLux(-50), 1e-12);
    assertEquals(100000.0, PowerSensor.toLux(250000), 1e-9);
  }

  @Test
  @DisplayName("a power sensor reports its source in lux and in Eve watts")
  void sensor() throws Exception {
    HomieTree tree = PanelFixture.tree();
    PowerSensor sensor = new PowerSensor(40, "Main Power", PANEL + "-power", () -> SpanReadings.panelConsumptionWatts(tree), null);
    assertEquals(1453.0, sensor.getCurrentAmbientLightLevel().get(), 1e-9);
    AccessoryListingTest.assertNoDuplicates(sensor);
  }

  @Test
  @DisplayName("a circuit's power sensor is a light sensor that follows that circuit's draw")
  void circuitPowerSensor() throws Exception {
    HomieTree tree = PanelFixture.tree();
    PowerSensor sensor =
        new PowerSensor(41, "Hot Tub Power", HOT_TUB + "-power", () -> SpanReadings.consumptionWatts(tree, HOT_TUB), null);
    assertEquals("00000084-0000-1000-8000-0026BB765291", sensor.getServices().get(0).getType());
    assertEquals(1451.0, sensor.getCurrentAmbientLightLevel().get(), 1e-9);

    tree.accept("ebus/5/" + HOT_TUB + "/meter/active-power", "-12");
    assertEquals(12.0, sensor.getCurrentAmbientLightLevel().get(), 1e-9);
  }

  @Test
  @DisplayName("the outlet itself carries only the outlet service, so its tile is just the switch")
  void outletAlone() {
    HomieTree tree = PanelFixture.tree();
    CircuitOutlet outlet = new CircuitOutlet(2, HOT_TUB, "Hot Tub", PANEL, tree, true, (id, on) -> {}, null);
    assertEquals(1, outlet.getServices().size());
  }
}
