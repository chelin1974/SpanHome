package io.github.chelin1974.spanhome;

import io.github.chelin1974.spanhome.span.HomieTree;

/**
 * A small panel in the r202633 parent/child model: two circuits, lugs, a battery and its MID
 * (a grandchild of the panel). Messages arrive in a scrambled order, as they do on subscribe.
 */
final class PanelFixture {

  static final String PANEL = "nj-2410-00h2v";
  static final String HOT_TUB = "ac3dccda46a94b98878a227df6fed588";
  static final String FRIDGE = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";
  static final String PV = "11112222333344445555666677778888";
  static final String BESS = PANEL + "-tg121153003k7g";
  static final String MID = BESS + "-mid";

  private PanelFixture() {}

  static HomieTree tree() {
    HomieTree tree = new HomieTree(PANEL);
    for (String[] message : messages()) {
      tree.accept(message[0], message[1]);
    }
    return tree;
  }

  static String[][] messages() {
    return new String[][] {
      // Values before descriptions: retained messages arrive in any order.
      {"ebus/5/" + HOT_TUB + "/meter/active-power", "-1450.5"},
      {"ebus/5/" + HOT_TUB + "/info/name", "Hot Tub"},
      {"ebus/5/" + HOT_TUB + "/info/spaces", "17,19"},
      {"ebus/5/" + HOT_TUB + "/switch/relay", "CLOSED"},
      {"ebus/5/" + HOT_TUB + "/switch/relay-controllable", "true"},
      {"ebus/5/" + HOT_TUB + "/meter/exported-energy", "123456.0"},
      {"ebus/5/" + HOT_TUB + "/meter/current", "-6.2"},
      {"ebus/5/" + HOT_TUB + "/$description", circuit("Hot Tub", true)},
      {"ebus/5/" + HOT_TUB + "/$state", "ready"},

      {"ebus/5/" + FRIDGE + "/$description", circuit("Kitchen Fridge", false)},
      {"ebus/5/" + FRIDGE + "/$state", "ready"},
      {"ebus/5/" + FRIDGE + "/info/name", "Kitchen Fridge"},
      {"ebus/5/" + FRIDGE + "/info/spaces", "3"},
      {"ebus/5/" + FRIDGE + "/switch/relay", "CLOSED"},
      {"ebus/5/" + FRIDGE + "/switch/relay-controllable", "false"},
      {"ebus/5/" + FRIDGE + "/meter/active-power", "-2.1"},

      {"ebus/5/" + PV + "/$description", circuit("Commissioned PV System", false)},
      {"ebus/5/" + PV + "/info/name", "Commissioned PV System"},
      {"ebus/5/" + PV + "/info/spaces", "1,3"},
      {"ebus/5/" + PV + "/meter/active-power", "4200"},

      {"ebus/5/" + PANEL + "/$state", "ready"},
      {"ebus/5/" + PANEL + "/$description",
          "{\"name\":\"SPAN Panel\",\"type\":\"energy.ebus.device.distribution-enclosure\","
              + "\"children\":[\"" + PANEL + "-lugs-up\",\"" + HOT_TUB + "\",\"" + FRIDGE + "\",\"" + PV + "\",\"" + BESS + "\"],"
              + "\"nodes\":{\"power-flows\":{\"type\":\"energy.ebus.capability.power-flows\",\"properties\":{\"battery\":{\"datatype\":\"float\"}}}}}"},
      {"ebus/5/" + PANEL + "/info/model", "MAIN_32"},
      {"ebus/5/" + PANEL + "/info/firmware-version", "spanos3/r202633/04"},
      {"ebus/5/" + PANEL + "/meter/voltage-a", "121.0"},
      {"ebus/5/" + PANEL + "/meter/voltage-b", "119.0"},
      {"ebus/5/" + PANEL + "/power-flows/battery", "1200"},
      {"ebus/5/" + PANEL + "/shed-forecast/total-time-remaining", "754"},

      {"ebus/5/" + PANEL + "-lugs-up/$description",
          "{\"name\":\"Upstream Lugs\",\"type\":\"energy.ebus.device.lugs\",\"parent\":\"" + PANEL + "\",\"nodes\":{}}"},

      {"ebus/5/" + BESS + "/$description",
          "{\"name\":\"Energy Storage System\",\"type\":\"energy.ebus.device.bess\",\"parent\":\"" + PANEL + "\","
              + "\"children\":[\"" + MID + "\"],\"nodes\":{}}"},
      {"ebus/5/" + BESS + "/soc/soc", "87.6"},
      {"ebus/5/" + BESS + "/info/vendor-name", "Tesla"},
      {"ebus/5/" + BESS + "/info/model", "Powerwall 2 AC"},

      {"ebus/5/" + MID + "/$description",
          "{\"name\":\"MID\",\"type\":\"energy.ebus.device.mid\",\"parent\":\"" + BESS + "\",\"nodes\":{}}"},
      {"ebus/5/" + MID + "/grid/islanding-state", "ON_GRID"},

      // Noise the tree must ignore.
      {"ebus/5/" + HOT_TUB + "/switch/relay/set", "OPEN"},
      {"homeassistant/status", "online"},
    };
  }

  static String circuit(String name, boolean relaySettable) {
    return "{\"name\":\"" + name + "\",\"type\":\"energy.ebus.device.circuit\",\"root\":\"" + PANEL
        + "\",\"parent\":\"" + PANEL + "\",\"nodes\":{"
        + "\"switch\":{\"type\":\"energy.ebus.capability.switch\",\"properties\":{"
        + "\"relay\":{\"datatype\":\"enum\",\"format\":\"UNKNOWN,OPEN,CLOSED\""
        + (relaySettable ? ",\"settable\":true" : "") + "}}},"
        + "\"meter\":{\"type\":\"energy.ebus.capability.meter\",\"properties\":{"
        + "\"active-power\":{\"datatype\":\"float\",\"unit\":\"W\"}}}}}";
  }
}
