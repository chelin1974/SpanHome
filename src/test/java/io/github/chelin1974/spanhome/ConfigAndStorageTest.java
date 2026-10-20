package io.github.chelin1974.spanhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.spanhome.SpanHomeConfig.ConfigException;
import io.github.chelin1974.spanhome.homekit.AccessoryIds;
import io.github.chelin1974.spanhome.homekit.HomeKitNames;
import io.github.chelin1974.spanhome.span.PanelCredentials;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigAndStorageTest {

  private static SpanHomeConfig config(String json) throws Exception {
    Path file = Files.createTempDirectory("span").resolve("span-home.config.json");
    Files.writeString(file, json);
    return SpanHomeConfig.load(file);
  }

  @Test
  @DisplayName("circuits are read-only unless named in switchCircuits")
  void readOnlyByDefault() throws Exception {
    SpanHomeConfig config = config("{\"panels\":[{\"serial\":\"nj-2410-00h2v\"}]}");
    assertFalse(config.isSwitchable("Main", "Hot Tub", "abc"));
  }

  @Test
  @DisplayName("switchCircuits matches a name, a panel-qualified name, an ID or *")
  void switchMatching() throws Exception {
    SpanHomeConfig config =
        config("{\"panels\":[{\"serial\":\"a\"}],\"switchCircuits\":[\"hot tub\",\"Garage: EV Charger\",\"ID-1\"]}");
    assertTrue(config.isSwitchable("Main", "Hot Tub", "x"));
    assertTrue(config.isSwitchable("Garage", "EV Charger", "x"));
    assertFalse(config.isSwitchable("Main", "EV Charger", "x"), "qualified to the Garage panel only");
    assertTrue(config.isSwitchable("Main", "Other", "id-1"));

    SpanHomeConfig all = config("{\"panels\":[{\"serial\":\"a\"}],\"switchCircuits\":[\"*\"]}");
    assertTrue(all.isSwitchable("Main", "Anything", "x"));
  }

  @Test
  @DisplayName("* does not hide every circuit")
  void hiddenNoWildcard() throws Exception {
    SpanHomeConfig config = config("{\"panels\":[{\"serial\":\"a\"}],\"hiddenCircuits\":[\"*\"]}");
    assertFalse(config.isHidden("Main", "Hot Tub", "x"));
  }

  @Test
  @DisplayName("the host defaults to the panel's .local name")
  void defaultHost() throws Exception {
    SpanHomeConfig config = config("{\"panels\":[{\"serial\":\"nj-2410-00h35\",\"name\":\"Garage\"}]}");
    assertEquals("span-nj-2410-00h35.local", config.panels.get(0).host());
    assertEquals("Garage", config.panels.get(0).label());
  }

  @Test
  @DisplayName("a config with no panels is rejected with a usable message")
  void noPanels() {
    ConfigException error = assertThrows(ConfigException.class, () -> config("{}"));
    assertTrue(error.getMessage().contains("serial"), error.getMessage());
  }

  @Test
  @DisplayName("accessory IDs survive a restart, and new circuits get new IDs")
  void stableIds() throws Exception {
    Path file = Files.createTempDirectory("span").resolve("ids.json");
    AccessoryIds first = AccessoryIds.load(file);
    int hotTub = first.idFor("hot-tub");
    int fridge = first.idFor("fridge");
    first.save();

    AccessoryIds second = AccessoryIds.load(file);
    assertEquals(fridge, second.idFor("fridge"));
    assertEquals(hotTub, second.idFor("hot-tub"));
    assertEquals(Math.max(hotTub, fridge) + 1, second.idFor("new-circuit"));
    assertTrue(hotTub >= 2, "1 is the bridge");
  }

  @Test
  @DisplayName("credentials are written owner-only and never printed")
  void credentials() throws Exception {
    Path file = Files.createTempDirectory("span").resolve("creds.json");
    PanelCredentials.Panel panel = new PanelCredentials.Panel();
    panel.serial = "nj-2410-00h2v";
    panel.host = "span-nj-2410-00h2v.local";
    panel.brokerUsername = "nj-2410-00h2v";
    panel.brokerPassword = "super-secret";
    panel.caCertificate = "-----BEGIN CERTIFICATE-----";
    PanelCredentials.load(file).put(panel);

    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    assertEquals("super-secret", PanelCredentials.load(file).get("nj-2410-00h2v").orElseThrow().brokerPassword);
    assertFalse(panel.toString().contains("super-secret"));
  }

  @Test
  @DisplayName("names are cleaned to what HomeKit accepts")
  void names() {
    assertEquals("Hot Tub", HomeKitNames.clean("  Hot Tub ", "x"));
    assertEquals("EV Charger 50A", HomeKitNames.clean("EV Charger (50A)", "x"));
    assertEquals("Kitchen - Fridge", HomeKitNames.clean("Kitchen / - Fridge!", "x").replaceAll("\\s+", " "));
    assertEquals("Circuit 7", HomeKitNames.clean("***", "Circuit 7"));
    assertEquals("Lights 2", HomeKitNames.unique("Lights", List.of("lights")));
  }

  @Test
  @DisplayName("SPAN firmware strings become HomeKit x.y.z versions")
  void firmware() {
    assertEquals("3.202633.4", HomeKitNames.firmware("spanos3/r202633/04"));
    assertEquals("1.0.0", HomeKitNames.firmware(null));
  }
}
