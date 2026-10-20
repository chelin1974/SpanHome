package io.github.chelin1974.spanhome;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code span-home.config.json}: which panels to bridge and which circuits Home may switch.
 *
 * <pre>{@code
 * {
 *   "panels": [
 *     { "serial": "nj-2410-00h2v", "name": "Main" },
 *     { "serial": "nj-2410-00h35", "name": "Garage" }
 *   ],
 *   "switchCircuits": ["Hot Tub", "Garage: EV Charger"],
 *   "hiddenCircuits": ["Spare"]
 * }
 * }</pre>
 *
 * <p>Circuits are <b>read-only by default</b>. Turning a circuit off from Home cuts its power,
 * so a misheard Siri command or a bad automation should not be able to switch off the fridge.
 * Only circuits named in {@code switchCircuits} accept on/off commands; every other circuit
 * still shows its state and power, and snaps back if someone taps it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SpanHomeConfig {

  public static final int DEFAULT_PORT = 51828;

  /** Thrown with a message fit to show the user. */
  public static final class ConfigException extends RuntimeException {
    public ConfigException(String message) {
      super(message);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class PanelEntry {
    /** Panel serial, e.g. {@code nj-2410-00h2v}. */
    public String serial;

    /** Address; defaults to {@code span-<serial>.local}. Use an IP if mDNS is unreliable. */
    public String host;

    /** Short label used to tell same-named circuits on different panels apart. */
    public String name;

    public String host() {
      return isBlank(host) ? "span-" + serial.trim() + ".local" : host.trim();
    }

    public String label() {
      return isBlank(name) ? serial.trim() : name.trim();
    }
  }

  public List<PanelEntry> panels = new ArrayList<>();

  /**
   * Circuits Home may switch: a circuit name, {@code "Panel: Name"} for one panel's circuit,
   * a circuit ID, or {@code "*"} for every controllable circuit. Case-insensitive.
   */
  public List<String> switchCircuits = new ArrayList<>();

  /** Circuits left out of Home entirely, matched the same way. */
  public List<String> hiddenCircuits = new ArrayList<>();

  /** Publish a backup-battery accessory when a panel has a battery commissioned. */
  public Boolean showBattery = true;

  public String bridgeName = "SPAN";
  public Integer port = DEFAULT_PORT;
  public String bindAddress;

  /** HomeKit pairing state. Back it up; losing it un-pairs the bridge. */
  public String storagePath;

  /** Panel broker credentials, written by {@code --register}. Owner-only. */
  public String credentialsPath;

  /** Stable HomeKit IDs for each circuit. */
  public String accessoryIdsPath;

  /** Where relative paths resolve: the config file's directory. */
  private Path baseDirectory = Path.of(".");

  public static SpanHomeConfig load(Path file) {
    if (!Files.isReadable(file)) {
      throw new ConfigException(
          "cannot read " + file.toAbsolutePath() + ". Copy span-home.config.example.json and list your panels.");
    }
    SpanHomeConfig config;
    try {
      config = new ObjectMapper().readValue(Files.readString(file), SpanHomeConfig.class);
    } catch (IOException e) {
      throw new ConfigException("could not parse " + file.toAbsolutePath() + ": " + e.getMessage());
    }
    Path parent = file.toAbsolutePath().getParent();
    config.baseDirectory = parent == null ? Path.of(".") : parent;
    config.validate();
    return config;
  }

  void validate() {
    if (panels == null || panels.isEmpty()) {
      throw new ConfigException("list at least one panel under \"panels\", e.g. { \"serial\": \"nj-2410-00h2v\" }");
    }
    for (PanelEntry panel : panels) {
      if (panel == null || isBlank(panel.serial)) {
        throw new ConfigException("every entry under \"panels\" needs a \"serial\"");
      }
    }
    if (port == null) {
      port = DEFAULT_PORT;
    }
    if (isBlank(bridgeName)) {
      bridgeName = "SPAN";
    }
    if (switchCircuits == null) {
      switchCircuits = new ArrayList<>();
    }
    if (hiddenCircuits == null) {
      hiddenCircuits = new ArrayList<>();
    }
  }

  public Path statePath() {
    return resolve(storagePath, "span-home.state.json");
  }

  public Path credentialsFile() {
    return resolve(credentialsPath, "span-home.credentials.json");
  }

  public Path accessoryIdsFile() {
    return resolve(accessoryIdsPath, "span-home.accessories.json");
  }

  private Path resolve(String configured, String fallback) {
    return baseDirectory.resolve(isBlank(configured) ? fallback : configured.trim());
  }

  // ------------------------------------------------------------- matching

  public boolean isSwitchable(String panelLabel, String circuitName, String circuitId) {
    return matches(switchCircuits, panelLabel, circuitName, circuitId, true);
  }

  public boolean isHidden(String panelLabel, String circuitName, String circuitId) {
    return matches(hiddenCircuits, panelLabel, circuitName, circuitId, false);
  }

  static boolean matches(
      List<String> patterns, String panelLabel, String circuitName, String circuitId, boolean allowWildcard) {
    String name = normalise(circuitName);
    String qualified = normalise(panelLabel) + ":" + name;
    String id = normalise(circuitId);
    for (String pattern : patterns) {
      if (pattern == null) {
        continue;
      }
      String p = normalise(pattern);
      if (allowWildcard && p.equals("*")) {
        return true;
      }
      // "Panel: Name" is written with a space after the colon; compare without it.
      String compact = p.replaceAll("\\s*:\\s*", ":");
      if (p.equals(name) || compact.equals(qualified) || p.equals(id)) {
        return true;
      }
    }
    return false;
  }

  private static String normalise(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
