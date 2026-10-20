package io.github.chelin1974.spanhome;

import static io.github.chelin1974.spanhome.PanelFixture.HOT_TUB;
import static io.github.chelin1974.spanhome.PanelFixture.PANEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.spanhome.homekit.CircuitOutlet;
import io.github.chelin1974.spanhome.span.HomieTree;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CircuitOutletTest {

  private HomieTree tree;
  private ScheduledExecutorService scheduler;
  private List<String> commands;

  @BeforeEach
  void setUp() {
    tree = PanelFixture.tree();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    commands = new ArrayList<>();
  }

  @AfterEach
  void tearDown() {
    scheduler.shutdownNow();
  }

  private CircuitOutlet outlet(boolean switchable) {
    return new CircuitOutlet(
        2, HOT_TUB, "Hot Tub", PANEL, tree, switchable,
        (id, closed) -> commands.add(id + "=" + (closed ? "CLOSED" : "OPEN")),
        scheduler);
  }

  @Test
  @DisplayName("on reflects the relay, and in-use reflects the load")
  void reads() throws Exception {
    CircuitOutlet outlet = outlet(true);
    assertTrue(outlet.getPowerState().get());
    assertTrue(outlet.getOutletInUse().get());

    tree.accept("ebus/5/" + HOT_TUB + "/switch/relay", "OPEN");
    tree.accept("ebus/5/" + HOT_TUB + "/meter/active-power", "-1");
    assertFalse(outlet.getPowerState().get());
    assertFalse(outlet.getOutletInUse().get());
  }

  @Test
  @DisplayName("a switchable circuit sends OPEN to turn off, and shows the change at once")
  void switchesOff() throws Exception {
    CircuitOutlet outlet = outlet(true);
    outlet.setPowerState(false);

    assertEquals(List.of(HOT_TUB + "=OPEN"), commands);
    assertFalse(outlet.getPowerState().get(), "optimistic until the panel confirms");
  }

  @Test
  @DisplayName("the panel's own report replaces the optimistic value")
  void panelReportWins() throws Exception {
    CircuitOutlet outlet = outlet(true);
    outlet.setPowerState(false);
    // The panel refused, say, and republished CLOSED.
    tree.accept("ebus/5/" + HOT_TUB + "/switch/relay", "OPEN");
    tree.accept("ebus/5/" + HOT_TUB + "/switch/relay", "CLOSED");
    outlet.propertyChanged("switch", "relay");
    assertTrue(outlet.getPowerState().get());
  }

  @Test
  @DisplayName("a read-only circuit never sends a command, and snaps back")
  void readOnly() throws Exception {
    CircuitOutlet outlet = outlet(false);
    AtomicInteger refreshes = new AtomicInteger();
    outlet.subscribePowerState(refreshes::incrementAndGet);

    outlet.setPowerState(false);
    Thread.sleep(900);

    assertTrue(commands.isEmpty());
    assertTrue(outlet.getPowerState().get(), "still on");
    assertEquals(1, refreshes.get(), "Home is told to re-read the real state");
  }

  @Test
  @DisplayName("a failed command reverts to the real state")
  void failedCommand() throws Exception {
    CircuitOutlet outlet =
        new CircuitOutlet(2, HOT_TUB, "Hot Tub", PANEL, tree, true,
            (id, closed) -> { throw new IllegalStateException("broker down"); }, scheduler);
    outlet.setPowerState(false);
    assertTrue(outlet.getPowerState().get());
  }

  @Test
  @DisplayName("in-use changes are pushed at once; watt updates are rate-limited")
  void meterEvents() {
    CircuitOutlet outlet = outlet(true);
    AtomicInteger inUse = new AtomicInteger();
    outlet.subscribeOutletInUse(inUse::incrementAndGet);

    tree.accept("ebus/5/" + HOT_TUB + "/meter/active-power", "-0.5");
    outlet.propertyChanged("meter", "active-power");
    tree.accept("ebus/5/" + HOT_TUB + "/meter/active-power", "-0.4");
    outlet.propertyChanged("meter", "active-power");

    assertEquals(1, inUse.get(), "only the on-to-idle transition");
  }

  @Test
  @DisplayName("exposes the outlet plus Eve's four power characteristics")
  void services() {
    CircuitOutlet outlet = outlet(true);
    // On, In Use, Name, then Consumption, Total Consumption, Voltage, Current.
    assertEquals(7, outlet.getServices().get(0).getCharacteristics().size());
    assertTrue(outlet.getServices() == outlet.getServices(), "built once");
  }
}
