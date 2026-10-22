package io.github.chelin1974.spanhome;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code span-home.config.json}: which panels to bridge and which circuits Home may switch.
 *
 * <pre>{@code
 * {
 *   "panels": [
 *     { "serial": "nj-2410-00h2v", "name": "Main" },
 *     { "serial": "nj-2410-00h35", "name": "Garage" }
 *   ],
 *   "readOnlyCircuits": ["Furnace", "Main: Sub Zero"],
 *   "hiddenCircuits": ["Spare"]
 * }
 * }</pre>
 *
 * <p>Every circuit the panel allows switching is <b>switchable by default</b>. Turning one off
 * from Home cuts its power, so list any that Home must never switch (a fridge, the furnace) in
 * {@code readOnlyCircuits}. To go the other way and allow only a few, set
 * {@code switchCircuits} to just those.
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
  public List<String> switchCircuits;

  /** Circuits Home must never switch, even when {@code switchCircuits} would allow it. */
  public List<String> readOnlyCircuits = new ArrayList<>();

  /** Circuits left out of Home entirely, matched the same way. */
  public List<String> hiddenCircuits = new ArrayList<>();

  /**
   * Rooms for the dashboard's room sort: room name to the circuits in it, matched the same way.
   * HomeKit doesn't tell accessories which room they're in, so the dashboard can't read the Home
   * app's rooms.
   */
  public Map<String, List<String>> rooms = new LinkedHashMap<>();

  /** Publish a backup-battery accessory when a panel has a battery commissioned. */
  public Boolean showBattery = true;

  /**
   * Publish a "<circuit> Power" accessory for each circuit, so the Home app shows its watts on
   * a tile of its own (as a light sensor reading in "lux").
   */
  public Boolean showWattsInHome = true;

  /** Publish each panel's total draw, and the whole house's, as power sensors. */
  public Boolean showPanelPower = true;

  /** Port for the Breaker Board dashboard on the home network; 0 turns it off. */
  public Integer dashboardPort = 8089;

  /**
   * Household passcode for switching circuits from the dashboard. Without one the dashboard is
   * view-only. Anyone who can reach the page and knows this can cut power to a circuit.
   */
  public String dashboardPasscode;

  /**
   * Prometheus 3 base URL, e.g. {@code http://localhost:9090}. Turns on metrics export (to its
   * OTLP receiver) and the dashboard's 24 h graphs (read back with PromQL).
   */
  public String prometheusUrl;

  /**
   * OTLP/HTTP metrics endpoint. Defaults to Prometheus' receiver,
   * {@code <prometheusUrl>/api/v1/otlp/v1/metrics}; set it to send elsewhere, such as a collector.
   */
  public String otlpEndpoint;

  /** Seconds between metric pushes. */
  public Integer metricsIntervalSeconds = 15;

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
      // Not given: every circuit the panel allows switching.
      switchCircuits = new ArrayList<>(List.of("*"));
    }
    if (readOnlyCircuits == null) {
      readOnlyCircuits = new ArrayList<>();
    }
    if (showWattsInHome == null) {
      showWattsInHome = true;
    }
    if (showPanelPower == null) {
      showPanelPower = true;
    }
    if (dashboardPort == null) {
      dashboardPort = 8089;
    }
    if (metricsIntervalSeconds == null || metricsIntervalSeconds < 5) {
      metricsIntervalSeconds = 15;
    }
    prometheusUri();
    otlpUri();
    if (hiddenCircuits == null) {
      hiddenCircuits = new ArrayList<>();
    }
    if (rooms == null) {
      rooms = new LinkedHashMap<>();
    }
  }

  /** The Prometheus base URL, when configured. */
  public java.util.Optional<java.net.URI> prometheusUri() {
    return isBlank(prometheusUrl) ? java.util.Optional.empty() : java.util.Optional.of(uri("prometheusUrl", prometheusUrl));
  }

  /** Where metrics go: {@code otlpEndpoint}, else Prometheus' OTLP receiver, else nowhere. */
  public java.util.Optional<java.net.URI> otlpUri() {
    if (!isBlank(otlpEndpoint)) {
      return java.util.Optional.of(uri("otlpEndpoint", otlpEndpoint));
    }
    return prometheusUri().map(base -> java.net.URI.create(base.toString().replaceAll("/+$", "") + "/api/v1/otlp/v1/metrics"));
  }

  private static java.net.URI uri(String setting, String value) {
    try {
      java.net.URI parsed = new java.net.URI(value.trim());
      if (parsed.getScheme() == null || !parsed.getScheme().matches("https?") || parsed.getHost() == null) {
        throw new java.net.URISyntaxException(value, "needs http:// or https:// and a host");
      }
      return parsed;
    } catch (java.net.URISyntaxException e) {
      throw new ConfigException("\"" + setting + "\" is not a usable URL: " + value + " (" + e.getReason() + ")");
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

  /** HomeKit configuration number and the layout it describes. Safe to delete. */
  public Path layoutFile() {
    return resolve(null, "span-home.layout.json");
  }

  private Path resolve(String configured, String fallback) {
    return baseDirectory.resolve(isBlank(configured) ? fallback : configured.trim());
  }

  // ------------------------------------------------------------- matching

  public boolean isSwitchable(String panelLabel, String circuitName, String circuitId) {
    return matches(switchCircuits, panelLabel, circuitName, circuitId, true)
        && !matches(readOnlyCircuits, panelLabel, circuitName, circuitId, false);
  }

  /** True when {@code switchCircuits} names this circuit itself, not just through {@code "*"}. */
  public boolean isNamedInSwitchCircuits(String panelLabel, String circuitName, String circuitId) {
    return matches(switchCircuits, panelLabel, circuitName, circuitId, false);
  }

  public boolean isHidden(String panelLabel, String circuitName, String circuitId) {
    return matches(hiddenCircuits, panelLabel, circuitName, circuitId, false);
  }

  /** The first room in {@code rooms} that lists this circuit, or null. */
  public String roomOf(String panelLabel, String circuitName, String circuitId) {
    for (Map.Entry<String, List<String>> room : rooms.entrySet()) {
      if (room.getKey() != null && !room.getKey().isBlank() && room.getValue() != null
          && matches(room.getValue(), panelLabel, circuitName, circuitId, false)) {
        return room.getKey().trim();
      }
    }
    return null;
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
