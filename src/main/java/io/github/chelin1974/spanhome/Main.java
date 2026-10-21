package io.github.chelin1974.spanhome;

import io.github.chelin1974.spanhome.SpanHomeConfig.ConfigException;
import io.github.chelin1974.spanhome.SpanHomeConfig.PanelEntry;
import io.github.chelin1974.spanhome.span.HomieTree;
import io.github.chelin1974.spanhome.span.HomieTree.Device;
import io.github.chelin1974.spanhome.span.PanelCredentials;
import io.github.chelin1974.spanhome.span.SpanPanelConnection;
import io.github.chelin1974.spanhome.span.SpanReadings;
import io.github.chelin1974.spanhome.span.SpanRegistration;
import io.github.chelin1974.spanhome.span.SpanRegistration.RegistrationException;
import java.io.Console;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <pre>
 *   java -jar span-home.jar --register                 pair with each panel (door presses)
 *   java -jar span-home.jar --register --passphrase    pair using the SPAN Home app passphrase
 *   java -jar span-home.jar --list                     show circuits, power and battery
 *   java -jar span-home.jar                            run the HomeKit bridge
 * </pre>
 */
public final class Main {

  private static final Logger log = LoggerFactory.getLogger(Main.class);

  private Main() {}

  public static void main(String[] args) {
    Map<String, String> options = parseArgs(args);
    if (options.containsKey("help") || options.containsKey("h")) {
      System.out.println(
          """
          usage: java -jar span-home.jar [options]

            --config <path>        configuration file (default: span-home.config.json)
            --register             pair this bridge with each panel in the config
              --panel <serial>     only this panel
              --passphrase [pw]    use the passphrase from the SPAN Home app instead of
                                   door presses; with no value you are prompted for it
            --list                 connect, print every circuit and battery reading, and exit
            --help                 show this message
          """);
      return;
    }

    Path configPath = Path.of(options.getOrDefault("config", "span-home.config.json"));
    try {
      SpanHomeConfig config = SpanHomeConfig.load(configPath);
      if (options.containsKey("register")) {
        System.exit(register(config, options));
      }
      if (options.containsKey("list")) {
        System.exit(list(config));
      }

      SpanHomeBridge bridge = new SpanHomeBridge(config);
      Runtime.getRuntime().addShutdownHook(new Thread(bridge::close, "span-home-shutdown"));
      bridge.start();
      bridge.awaitTermination();
    } catch (ConfigException | RegistrationException e) {
      log.error("{}", e.getMessage());
      System.exit(1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.error("failed: {}", e.getMessage(), e);
      System.exit(1);
    }
  }

  // ---------------------------------------------------------------- register

  static int register(SpanHomeConfig config, Map<String, String> options) {
    String only = options.get("panel");
    String passphrase = options.get("passphrase");
    if ("true".equals(passphrase)) {
      Console console = System.console();
      if (console == null) {
        System.err.println("No console to prompt on; pass the passphrase as --passphrase <value>.");
        return 1;
      }
      char[] typed = console.readPassword("SPAN panel passphrase (from the SPAN Home app): ");
      passphrase = typed == null ? null : new String(typed);
    }

    PanelCredentials credentials = PanelCredentials.load(config.credentialsFile());
    SpanRegistration registration = new SpanRegistration();
    int failures = 0;
    for (PanelEntry entry : config.panels) {
      if (only != null && !only.equalsIgnoreCase(entry.serial.trim())) {
        continue;
      }
      System.err.println();
      System.err.println("Registering with panel " + entry.label() + " (" + entry.host() + ")...");
      try {
        PanelCredentials.Panel panel = registration.register(entry.host(), passphrase, Duration.ofMinutes(15));
        if (!panel.serial.equalsIgnoreCase(entry.serial.trim())) {
          System.err.println("  That address answered as panel " + panel.serial + ", not " + entry.serial
              + ". Check the host in the config.");
          failures++;
          continue;
        }
        // Saved under the serial exactly as the panel reports it: its MQTT topics use that
        // spelling. Lookups ignore case, so the config may write it either way.
        credentials.put(panel);
        System.err.println("  Registered. Credentials saved to " + credentials.file().toAbsolutePath());
      } catch (RegistrationException e) {
        System.err.println("  " + e.getMessage());
        failures++;
      }
    }
    return failures == 0 ? 0 : 1;
  }

  // -------------------------------------------------------------------- list

  static int list(SpanHomeConfig config) throws Exception {
    List<SpanPanelConnection> connections = SpanHomeBridge.connectAll(config);
    try {
      for (int i = 0; i < connections.size(); i++) {
        HomieTree tree = connections.get(i).tree();
        PanelEntry entry = config.panels.get(i);
        String label = entry.label();
        System.out.println();
        System.out.printf("Panel %s (%s) %s, firmware %s, %.0f V%n",
            label,
            tree.rootId(),
            tree.value(tree.rootId(), "info", "model").orElse("?"),
            tree.value(tree.rootId(), "info", "firmware-version").orElse("?"),
            SpanReadings.panelVoltage(tree));
        System.out.printf("  %-7s %-28s %8s %7s  %s%n", "Spaces", "Circuit", "Watts", "Relay", "In Home as");
        for (Device circuit : SpanReadings.circuits(tree)) {
          String name = SpanReadings.name(tree, circuit);
          String mode;
          if (config.isHidden(label, name, circuit.id())) {
            mode = "hidden";
          } else if (!SpanReadings.relayControllable(tree, circuit)) {
            mode = "read-only (panel will not switch it)";
          } else if (config.isSwitchable(label, name, circuit.id())) {
            mode = "SWITCHABLE";
          } else {
            mode = "read-only";
          }
          System.out.printf("  %-7s %-28s %8.0f %7s  %s%n",
              SpanReadings.spaces(tree, circuit.id()),
              truncate(name, 28),
              SpanReadings.consumptionWatts(tree, circuit.id()),
              SpanReadings.relay(tree, circuit.id()),
              mode);
        }
        System.out.printf("  Panel draw: %.0f W%n", SpanReadings.panelConsumptionWatts(tree));
        SpanReadings.battery(tree).ifPresentOrElse(
            bess -> System.out.printf("  Battery: %s%%, %s, %s%s%n",
                SpanReadings.stateOfCharge(tree, bess.id()).map(String::valueOf).orElse("?"),
                SpanReadings.batteryFlow(tree).toString().toLowerCase().replace('_', ' '),
                SpanReadings.grid(tree).toString().toLowerCase().replace('_', ' '),
                SpanReadings.backupMinutesRemaining(tree).map(m -> String.format(", ~%dh%02dm backup", m / 60, m % 60)).orElse("")),
            () -> System.out.println("  No battery commissioned."));
      }
      if (connections.size() > 1) {
        System.out.println();
        System.out.printf("House draw: %.0f W%n",
            connections.stream().mapToDouble(c -> SpanReadings.panelConsumptionWatts(c.tree())).sum());
      }
    } finally {
      connections.forEach(SpanPanelConnection::close);
    }
    return 0;
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max - 1) + "…";
  }

  /** Parses {@code --key value} and {@code --flag} into a map. */
  static Map<String, String> parseArgs(String[] args) {
    Map<String, String> options = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("--")) {
        continue;
      }
      String key = args[i].substring(2);
      if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
        options.put(key, args[++i]);
      } else {
        options.put(key, "true");
      }
    }
    return options;
  }
}
