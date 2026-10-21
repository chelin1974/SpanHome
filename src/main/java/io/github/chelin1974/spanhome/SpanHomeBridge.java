package io.github.chelin1974.spanhome;

import io.github.chelin1974.spanhome.SpanHomeConfig.ConfigException;
import io.github.chelin1974.spanhome.SpanHomeConfig.PanelEntry;
import io.github.chelin1974.spanhome.homekit.AccessoryIds;
import io.github.chelin1974.spanhome.homekit.BackupBattery;
import io.github.chelin1974.spanhome.homekit.CircuitOutlet;
import io.github.chelin1974.spanhome.homekit.FileAuthInfo;
import io.github.chelin1974.spanhome.homekit.HomeKitNames;
import io.github.chelin1974.spanhome.homekit.LayoutVersion;
import io.github.chelin1974.spanhome.homekit.NetworkAddresses;
import io.github.chelin1974.spanhome.homekit.PowerSensor;
import io.github.chelin1974.spanhome.span.HomieTree;
import io.github.chelin1974.spanhome.span.HomieTree.Device;
import io.github.chelin1974.spanhome.span.PanelCredentials;
import io.github.chelin1974.spanhome.span.SpanPanelConnection;
import io.github.chelin1974.spanhome.span.SpanReadings;
import io.github.hapjava.accessories.HomekitAccessory;
import io.github.hapjava.server.HomekitAccessoryCategories;
import io.github.hapjava.server.impl.HomekitRoot;
import io.github.hapjava.server.impl.HomekitServer;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connects to every configured panel, publishes their circuits and batteries to HomeKit as one
 * bridge, and keeps them live from MQTT.
 */
public final class SpanHomeBridge implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SpanHomeBridge.class);

  /** HomeKit allows 150 accessories per bridge, the bridge itself included. */
  static final int MAX_ACCESSORIES = 149;

  static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

  private final SpanHomeConfig config;
  private final List<SpanPanelConnection> connections = new ArrayList<>();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "span-home");
        thread.setDaemon(true);
        return thread;
      });
  private final CountDownLatch terminated = new CountDownLatch(1);

  private HomekitServer server;
  private HomekitRoot bridge;

  public SpanHomeBridge(SpanHomeConfig config) {
    this.config = config;
  }

  /** Connects to every panel, waiting for each to describe its circuits. */
  static List<SpanPanelConnection> connectAll(SpanHomeConfig config) throws Exception {
    PanelCredentials credentials = PanelCredentials.load(config.credentialsFile());
    List<SpanPanelConnection> connected = new ArrayList<>();
    for (PanelEntry entry : config.panels) {
      PanelCredentials.Panel panel =
          credentials.get(entry.serial.trim())
              .orElseThrow(() -> new ConfigException(
                  "panel " + entry.serial + " is not registered yet (" + config.credentialsFile().toAbsolutePath()
                      + (credentials.serials().isEmpty()
                          ? " has no panels"
                          : " has " + String.join(", ", credentials.serials()))
                      + "). Run: java -jar span-home.jar --register --panel " + entry.serial.trim()));
      // A host in the config overrides the one saved at registration (say, a new DHCP lease).
      panel.host = entry.host();

      SpanPanelConnection connection = new SpanPanelConnection(panel);
      log.info("connecting to panel {} at {}:{}", panel.serial, panel.host, panel.mqttsPort);
      connection.connect();
      connected.add(connection);
    }
    for (SpanPanelConnection connection : connected) {
      if (!connection.awaitDiscovery(DISCOVERY_TIMEOUT)) {
        log.warn(
            "panel {} did not describe every device within {}s; missing {}. Continuing with what arrived.",
            connection.serial(),
            DISCOVERY_TIMEOUT.toSeconds(),
            connection.tree().missingDescriptions());
      }
    }
    return connected;
  }

  public void start() throws Exception {
    connections.addAll(connectAll(config));

    FileAuthInfo authInfo = FileAuthInfo.loadOrCreate(config.statePath());
    InetAddress address = NetworkAddresses.resolve(config.bindAddress);
    if (address.isLoopbackAddress()) {
      log.warn(
          "advertising HomeKit on {}, a loopback address your iPhone cannot reach. Set \"bindAddress\" to this machine's LAN IP.",
          address.getHostAddress());
    }
    server = new HomekitServer(address, config.port);
    bridge =
        server.createBridge(
            authInfo,
            config.bridgeName,
            HomekitAccessoryCategories.BRIDGES,
            "SPAN",
            "SPAN HomeKit Bridge",
            "span-home-bridge",
            "1.0.0",
            "1.0.0");

    AccessoryIds ids = AccessoryIds.load(config.accessoryIdsFile());
    List<HomekitAccessory> accessories = new ArrayList<>();
    List<String> takenNames = new ArrayList<>();

    for (int i = 0; i < connections.size(); i++) {
      SpanPanelConnection connection = connections.get(i);
      PanelEntry entry = config.panels.get(i);
      accessories.addAll(buildPanel(connection, entry, ids, takenNames));
    }

    if (config.showPanelPower) {
      accessories.addAll(buildPowerSensors(ids, takenNames));
    }

    if (accessories.size() > MAX_ACCESSORIES) {
      log.warn(
          "{} accessories exceed HomeKit's limit of {} per bridge; the last {} are left out. Hide some with hiddenCircuits.",
          accessories.size(), MAX_ACCESSORIES, accessories.size() - MAX_ACCESSORIES);
      accessories = accessories.subList(0, MAX_ACCESSORIES);
    }
    accessories.forEach(bridge::addAccessory);
    ids.save();
    bridge.setConfigurationIndex(LayoutVersion.resolve(config.layoutFile(), accessories));

    bridge.start();
    log.info("HomeKit bridge \"{}\" advertised at {}:{} with {} accessories",
        config.bridgeName, address.getHostAddress(), config.port, accessories.size());

    if (!authInfo.hasUser()) {
      log.info("");
      log.info("  +------------------------------------------+");
      log.info("  |  Add the bridge in the Home app using     |");
      log.info("  |  setup code:  {}                 |", authInfo.getPin());
      log.info("  +------------------------------------------+");
      log.info("");
    } else {
      log.info(
          "already paired with Apple Home, so no setup code is shown. If the bridge is not in the"
              + " Home app, stop it, delete {}, and start it again for a new code.",
          config.statePath().toAbsolutePath());
    }
  }

  private List<HomekitAccessory> buildPanel(
      SpanPanelConnection connection, PanelEntry entry, AccessoryIds ids, List<String> takenNames) {
    HomieTree tree = connection.tree();
    String label = entry.label();
    Map<String, CircuitOutlet> outlets = new ConcurrentHashMap<>();
    List<HomekitAccessory> built = new ArrayList<>();
    int switchable = 0;

    for (Device circuit : SpanReadings.circuits(tree)) {
      String name = SpanReadings.name(tree, circuit);
      if (config.isHidden(label, name, circuit.id())) {
        continue;
      }
      boolean wanted = config.isSwitchable(label, name, circuit.id());
      boolean controllable = SpanReadings.relayControllable(tree, circuit);
      if (wanted && !controllable) {
        log.info("\"{}\" is in switchCircuits, but the panel does not allow switching it; it will be read-only.", name);
      }

      String display = HomeKitNames.clean(name, "Circuit " + SpanReadings.spaces(tree, circuit.id()));
      if (takenNames.stream().anyMatch(display::equalsIgnoreCase) && config.panels.size() > 1) {
        display = HomeKitNames.clean(label + " " + display, display);
      }
      display = HomeKitNames.unique(display, takenNames);
      takenNames.add(display);

      CircuitOutlet outlet =
          new CircuitOutlet(
              ids.idFor(circuit.id()),
              circuit.id(),
              display,
              tree.rootId(),
              tree,
              wanted && controllable,
              (id, closed) -> connection.set(id, "switch", "relay", closed ? "CLOSED" : "OPEN"),
              scheduler,
              config.showWattsInHome);
      outlets.put(circuit.id(), outlet);
      built.add(outlet);
      if (outlet.isSwitchable()) {
        switchable++;
      }
    }

    BackupBattery battery = null;
    if (config.showBattery == null || config.showBattery) {
      Device bess = SpanReadings.battery(tree).orElse(null);
      if (bess != null) {
        String name = HomeKitNames.unique(HomeKitNames.clean(label + " Grid", "Grid"), takenNames);
        takenNames.add(name);
        battery = new BackupBattery(ids.idFor(bess.id()), name, tree, bess.id());
        built.add(battery);
      } else {
        log.info("panel {} has no battery commissioned; no backup accessory.", label);
      }
    }

    BackupBattery finalBattery = battery;
    tree.addListener((deviceId, node, property, value) -> {
      CircuitOutlet outlet = outlets.get(deviceId);
      if (outlet != null) {
        outlet.propertyChanged(node, property);
      }
      if (finalBattery != null) {
        finalBattery.propertyChanged(deviceId, node, property);
      }
    });

    log.info("panel {}: {} circuits ({} switchable){}",
        label, outlets.size(), switchable, battery == null ? "" : ", plus battery backup");
    return built;
  }

  /**
   * One power sensor per panel, plus a house total when there is more than one panel.
   *
   * <p>Totals are sums of circuit draw (see {@link SpanReadings#panelConsumptionWatts}), so the
   * panel and house figures always agree with the per-circuit readings.
   */
  private List<HomekitAccessory> buildPowerSensors(AccessoryIds ids, List<String> takenNames) {
    List<HomekitAccessory> sensors = new ArrayList<>();
    for (int i = 0; i < connections.size(); i++) {
      HomieTree tree = connections.get(i).tree();
      String name = HomeKitNames.unique(HomeKitNames.clean(config.panels.get(i).label() + " Power", "Panel Power"), takenNames);
      takenNames.add(name);
      sensors.add(new PowerSensor(
          ids.idFor("power:" + tree.rootId()), name, tree.rootId() + "-power",
          () -> SpanReadings.panelConsumptionWatts(tree), scheduler));
    }
    if (connections.size() > 1) {
      String name = HomeKitNames.unique("House Power", takenNames);
      takenNames.add(name);
      List<HomieTree> trees = connections.stream().map(SpanPanelConnection::tree).toList();
      sensors.add(new PowerSensor(
          ids.idFor("power:house"), name, "house-power",
          () -> trees.stream().mapToDouble(SpanReadings::panelConsumptionWatts).sum(), scheduler));
    }
    return sensors;
  }

  public void awaitTermination() throws InterruptedException {
    terminated.await();
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
    if (bridge != null) {
      bridge.stop();
    }
    if (server != null) {
      server.stop();
    }
    connections.forEach(SpanPanelConnection::close);
    terminated.countDown();
  }
}
