package io.github.chelin1974.spanhome;

import static io.github.chelin1974.spanhome.PanelFixture.HOT_TUB;
import static io.github.chelin1974.spanhome.PanelFixture.PANEL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.chelin1974.spanhome.homekit.CircuitOutlet;
import io.github.chelin1974.spanhome.homekit.PowerSensor;
import io.github.chelin1974.spanhome.span.HomieTree;
import io.github.chelin1974.spanhome.span.SpanReadings;
import java.util.concurrent.Executors;
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
  @DisplayName("with watts in Home, a circuit adds a light sensor and still registers cleanly")
  void circuitLightSensor() throws Exception {
    HomieTree tree = PanelFixture.tree();
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      CircuitOutlet outlet = new CircuitOutlet(2, HOT_TUB, "Hot Tub", PANEL, tree, true, (id, on) -> {}, scheduler, true);
      assertEquals(2, outlet.getServices().size());
      assertEquals("00000084-0000-1000-8000-0026BB765291", outlet.getServices().get(1).getType());
      AccessoryListingTest.assertNoDuplicates(outlet);
    } finally {
      scheduler.shutdownNow();
    }
  }
}
