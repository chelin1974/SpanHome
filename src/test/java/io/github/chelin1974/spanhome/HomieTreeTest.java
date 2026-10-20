package io.github.chelin1974.spanhome;

import static io.github.chelin1974.spanhome.PanelFixture.BESS;
import static io.github.chelin1974.spanhome.PanelFixture.HOT_TUB;
import static io.github.chelin1974.spanhome.PanelFixture.MID;
import static io.github.chelin1974.spanhome.PanelFixture.PANEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.spanhome.span.HomieTree;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HomieTreeTest {

  @Test
  @DisplayName("finds the MID, which is a grandchild of the panel")
  void walksGrandchildren() {
    HomieTree tree = PanelFixture.tree();
    assertEquals(1, tree.ofType(HomieTree.TYPE_MID).size());
    assertEquals(MID, tree.ofType(HomieTree.TYPE_MID).get(0).id());
    assertEquals(3, tree.ofType(HomieTree.TYPE_CIRCUIT).size());
  }

  @Test
  @DisplayName("discovery is complete only once every device, at every depth, is described")
  void discoveryCompleteness() {
    HomieTree tree = new HomieTree(PANEL);
    assertFalse(tree.isDiscoveryComplete());
    for (String[] message : PanelFixture.messages()) {
      if (!message[0].equals("ebus/5/" + MID + "/$description")) {
        tree.accept(message[0], message[1]);
      }
    }
    assertFalse(tree.isDiscoveryComplete(), "the MID has not been described");
    assertEquals(List.of(MID), tree.missingDescriptions());

    tree.accept("ebus/5/" + MID + "/$description", "{\"type\":\"energy.ebus.device.mid\",\"nodes\":{}}");
    assertTrue(tree.isDiscoveryComplete());
  }

  @Test
  @DisplayName("reads per-circuit settable flags from the description")
  void settable() {
    HomieTree tree = PanelFixture.tree();
    assertTrue(tree.device(HOT_TUB).orElseThrow().isSettable("switch", "relay"));
    assertFalse(tree.device(PanelFixture.FRIDGE).orElseThrow().isSettable("switch", "relay"));
  }

  @Test
  @DisplayName("ignores /set echoes and topics outside ebus/5")
  void ignoresNoise() {
    HomieTree tree = PanelFixture.tree();
    assertEquals("CLOSED", tree.value(HOT_TUB, "switch", "relay").orElseThrow());
  }

  @Test
  @DisplayName("notifies listeners only when a value actually changes")
  void listenerOnChange() {
    HomieTree tree = PanelFixture.tree();
    List<String> events = new ArrayList<>();
    tree.addListener((device, node, property, value) -> events.add(node + "/" + property + "=" + value));

    tree.accept("ebus/5/" + HOT_TUB + "/switch/relay", "CLOSED");
    tree.accept("ebus/5/" + HOT_TUB + "/switch/relay", "OPEN");

    assertEquals(List.of("switch/relay=OPEN"), events);
  }

  @Test
  @DisplayName("a lost panel takes every child offline with it")
  void effectiveState() {
    HomieTree tree = PanelFixture.tree();
    assertTrue(tree.isOnline(HOT_TUB));
    tree.accept("ebus/5/" + PANEL + "/$state", "lost");
    assertFalse(tree.isOnline(HOT_TUB));
  }

  @Test
  @DisplayName("a malformed description is ignored, not fatal")
  void malformedDescription() {
    HomieTree tree = PanelFixture.tree();
    tree.accept("ebus/5/" + BESS + "/$description", "{not json");
    assertEquals(HomieTree.TYPE_BESS, tree.device(BESS).orElseThrow().type());
  }

  @Test
  @DisplayName("a listener that throws does not stop the tree updating")
  void listenerFailureIsolated() {
    HomieTree tree = PanelFixture.tree();
    tree.addListener((d, n, p, v) -> {
      throw new IllegalStateException("bug");
    });
    tree.accept("ebus/5/" + HOT_TUB + "/meter/active-power", "-10");
    assertEquals(-10.0, tree.number(HOT_TUB, "meter", "active-power").orElseThrow(), 1e-9);
  }
}
