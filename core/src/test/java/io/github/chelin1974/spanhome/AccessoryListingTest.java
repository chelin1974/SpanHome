package io.github.chelin1974.spanhome;

import static io.github.chelin1974.spanhome.PanelFixture.BESS;
import static io.github.chelin1974.spanhome.PanelFixture.HOT_TUB;
import static io.github.chelin1974.spanhome.PanelFixture.PANEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.spanhome.homekit.BackupBattery;
import io.github.chelin1974.spanhome.homekit.CircuitOutlet;
import io.github.chelin1974.spanhome.span.HomieTree;
import io.github.hapjava.accessories.HomekitAccessory;
import io.github.hapjava.services.Service;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lists services the way HAP-Java's HomekitRegistry does: each service from getServices(),
 * followed by its linked services. A service appearing twice makes the accessory listing fail
 * with "Duplicate key", and iOS then cannot load any accessory on the bridge.
 */
class AccessoryListingTest {

  static List<Service> registered(HomekitAccessory accessory) {
    List<Service> all = new ArrayList<>();
    for (Service service : accessory.getServices()) {
      all.add(service);
      all.addAll(service.getLinkedServices());
    }
    return all;
  }

  static void assertNoDuplicates(HomekitAccessory accessory) {
    Map<Service, Boolean> seen = new IdentityHashMap<>();
    for (Service service : registered(accessory)) {
      assertTrue(seen.put(service, true) == null, "service registered twice: " + service.getType());
    }
  }

  @Test
  @DisplayName("the backup accessory registers grid and battery once each")
  void backupBattery() {
    HomieTree tree = PanelFixture.tree();
    BackupBattery battery = new BackupBattery(10, "Main Grid", tree, BESS);

    assertNoDuplicates(battery);
    List<String> types = registered(battery).stream().map(Service::getType).toList();
    assertEquals(
        List.of("00000080-0000-1000-8000-0026BB765291", "00000096-0000-1000-8000-0026BB765291"),
        types,
        "contact sensor, then the linked battery");
  }

  @Test
  @DisplayName("a circuit outlet registers each service once")
  void circuitOutlet() {
    HomieTree tree = PanelFixture.tree();
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      assertNoDuplicates(new CircuitOutlet(2, HOT_TUB, "Hot Tub", PANEL, tree, true, (id, on) -> {}, scheduler));
    } finally {
      scheduler.shutdownNow();
    }
  }
}
