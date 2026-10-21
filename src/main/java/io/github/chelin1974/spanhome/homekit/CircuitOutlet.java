package io.github.chelin1974.spanhome.homekit;

import io.github.chelin1974.spanhome.span.HomieTree;
import io.github.chelin1974.spanhome.span.SpanReadings;
import io.github.chelin1974.spanhome.span.SpanReadings.Relay;
import io.github.hapjava.accessories.OutletAccessory;
import io.github.hapjava.accessories.optionalcharacteristic.AccessoryWithName;
import io.github.hapjava.characteristics.HomekitCharacteristicChangeCallback;
import io.github.hapjava.services.Service;
import io.github.hapjava.characteristics.impl.lightsensor.CurrentAmbientLightLevelCharacteristic;
import io.github.hapjava.services.impl.LightSensorService;
import io.github.hapjava.services.impl.OutletService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One SPAN circuit, as a HomeKit outlet.
 *
 * <ul>
 *   <li><b>On</b> is the circuit relay: closed means powered.
 *   <li><b>In use</b> means the circuit is drawing more than a few watts.
 *   <li>Watts, kWh, volts and amps ride along as Eve characteristics (see
 *       {@link EveCharacteristics}), visible in the Eve app.
 *   <li>Optionally, watts also as a light sensor, so the Home app shows the number (as "lux":
 *       Apple Home has no power display). See {@link PowerSensor#toLux(double)}.
 * </ul>
 *
 * <p>A read-only circuit, one not listed in {@code switchCircuits} or one the panel will not
 * let anyone switch, ignores taps and snaps back to its real state. HAP-Java swallows setter
 * errors, so snapping back is the only feedback the Home app can show.
 *
 * <p>Reads never block: every value comes from the {@link HomieTree}, which the MQTT
 * connection keeps current.
 */
public final class CircuitOutlet implements OutletAccessory, AccessoryWithName {

  private static final Logger log = LoggerFactory.getLogger(CircuitOutlet.class);

  /** Eve does not need second-by-second power; this keeps HomeKit event traffic modest. */
  static final Duration METER_EVENT_INTERVAL = Duration.ofSeconds(10);

  /** How long Home shows the requested state before the panel's own report takes over. */
  static final Duration OPTIMISTIC_WINDOW = Duration.ofSeconds(10);

  /** Sends a relay command to the panel. */
  @FunctionalInterface
  public interface RelayControl {
    void setRelay(String circuitId, boolean closed) throws Exception;
  }

  private final int id;
  private final String circuitId;
  private final String displayName;
  private final String panelSerial;
  private final HomieTree tree;
  private final boolean switchable;
  private final RelayControl control;
  private final ScheduledExecutorService scheduler;

  private final AtomicReference<HomekitCharacteristicChangeCallback> onPower = new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onInUse = new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onWatts = new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onKwh = new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onVolts = new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onAmps = new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onLux = new AtomicReference<>();
  private final boolean wattsInHome;

  private volatile Boolean optimistic;
  private volatile Instant optimisticUntil = Instant.EPOCH;
  private volatile boolean lastInUse;
  private volatile Instant lastMeterEvent = Instant.EPOCH;

  public CircuitOutlet(
      int id,
      String circuitId,
      String displayName,
      String panelSerial,
      HomieTree tree,
      boolean switchable,
      RelayControl control,
      ScheduledExecutorService scheduler) {
    this(id, circuitId, displayName, panelSerial, tree, switchable, control, scheduler, false);
  }

  /** @param wattsInHome also publish watts as a light sensor the Home app can display */
  public CircuitOutlet(
      int id,
      String circuitId,
      String displayName,
      String panelSerial,
      HomieTree tree,
      boolean switchable,
      RelayControl control,
      ScheduledExecutorService scheduler,
      boolean wattsInHome) {
    this.wattsInHome = wattsInHome;
    this.id = id;
    this.circuitId = circuitId;
    this.displayName = displayName;
    this.panelSerial = panelSerial;
    this.tree = tree;
    this.switchable = switchable;
    this.control = control;
    this.scheduler = scheduler;
    this.lastInUse = inUse();
  }

  public String circuitId() {
    return circuitId;
  }

  public String displayName() {
    return displayName;
  }

  public boolean isSwitchable() {
    return switchable;
  }

  // ------------------------------------------------------------- panel events

  /** Called for every property change on this circuit's device. */
  public void propertyChanged(String node, String property) {
    if (node.equals("switch") && property.equals("relay")) {
      // The panel's report is the truth; drop any optimistic value.
      optimistic = null;
      fire(onPower);
      return;
    }
    if (!node.equals("meter")) {
      return;
    }
    boolean inUse = inUse();
    if (inUse != lastInUse) {
      lastInUse = inUse;
      fire(onInUse);
    }
    Instant now = Instant.now();
    if (Duration.between(lastMeterEvent, now).compareTo(METER_EVENT_INTERVAL) >= 0) {
      lastMeterEvent = now;
      fire(onWatts);
      fire(onKwh);
      fire(onAmps);
      fire(onVolts);
      fire(onLux);
    }
  }

  // ------------------------------------------------------------------ outlet

  @Override
  public CompletableFuture<Boolean> getPowerState() {
    Boolean pending = optimistic;
    if (pending != null && Instant.now().isBefore(optimisticUntil)) {
      return CompletableFuture.completedFuture(pending);
    }
    return CompletableFuture.completedFuture(SpanReadings.relay(tree, circuitId) == Relay.CLOSED);
  }

  @Override
  public CompletableFuture<Void> setPowerState(boolean on) throws Exception {
    if (!switchable) {
      log.info(
          "ignored a request to turn \"{}\" {}: it is read-only. Add it to switchCircuits to allow this.",
          displayName,
          on ? "on" : "off");
      snapBack();
      return CompletableFuture.completedFuture(null);
    }

    log.info("turning \"{}\" {}", displayName, on ? "on" : "off");
    optimistic = on;
    optimisticUntil = Instant.now().plus(OPTIMISTIC_WINDOW);
    try {
      control.setRelay(circuitId, on);
    } catch (Exception e) {
      log.warn("could not switch \"{}\": {}", displayName, e.getMessage());
      optimistic = null;
      snapBack();
      return CompletableFuture.completedFuture(null);
    }
    // If the panel never confirms, Home falls back to its real state once the window ends.
    scheduler.schedule(() -> fire(onPower), OPTIMISTIC_WINDOW.toMillis() + 500, TimeUnit.MILLISECONDS);
    return CompletableFuture.completedFuture(null);
  }

  private void snapBack() {
    scheduler.schedule(() -> fire(onPower), 500, TimeUnit.MILLISECONDS);
  }

  @Override
  public CompletableFuture<Boolean> getOutletInUse() {
    return CompletableFuture.completedFuture(inUse());
  }

  private boolean inUse() {
    return SpanReadings.consumptionWatts(tree, circuitId) >= SpanReadings.IN_USE_WATTS;
  }

  @Override
  public void subscribePowerState(HomekitCharacteristicChangeCallback callback) {
    onPower.set(callback);
  }

  @Override
  public void unsubscribePowerState() {
    onPower.set(null);
  }

  @Override
  public void subscribeOutletInUse(HomekitCharacteristicChangeCallback callback) {
    onInUse.set(callback);
  }

  @Override
  public void unsubscribeOutletInUse() {
    onInUse.set(null);
  }

  // ---------------------------------------------------------------- services

  /**
   * Built once: HAP-Java asks again whenever the accessory list is rebuilt, and fresh
   * characteristic objects each time would drop the Home app's subscriptions.
   */
  private volatile List<Service> services;

  @Override
  public synchronized List<Service> getServices() {
    if (services == null) {
      services = buildServices();
    }
    return services;
  }

  private List<Service> buildServices() {
    OutletService outlet = new OutletService(this);
    outlet.addCharacteristic(
        EveCharacteristics.readOnly(
            EveCharacteristics.CONSUMPTION, "Consumption", 100_000, 0.1, "W",
            () -> CompletableFuture.completedFuture(SpanReadings.consumptionWatts(tree, circuitId)),
            onWatts::set, () -> onWatts.set(null)));
    outlet.addCharacteristic(
        EveCharacteristics.readOnly(
            EveCharacteristics.TOTAL_CONSUMPTION, "Total Consumption", 1_000_000_000, 0.001, "kWh",
            () -> CompletableFuture.completedFuture(SpanReadings.consumedKilowattHours(tree, circuitId)),
            onKwh::set, () -> onKwh.set(null)));
    outlet.addCharacteristic(
        EveCharacteristics.readOnly(
            EveCharacteristics.VOLTAGE, "Voltage", 1_000, 0.1, "V",
            () -> CompletableFuture.completedFuture(SpanReadings.panelVoltage(tree)),
            onVolts::set, () -> onVolts.set(null)));
    outlet.addCharacteristic(
        EveCharacteristics.readOnly(
            EveCharacteristics.CURRENT, "Electric Current", 1_000, 0.01, "A",
            () -> CompletableFuture.completedFuture(SpanReadings.currentAmps(tree, circuitId)),
            onAmps::set, () -> onAmps.set(null)));
    if (!wattsInHome) {
      return List.of(outlet);
    }
    LightSensorService watts =
        new LightSensorService(
            new CurrentAmbientLightLevelCharacteristic(
                CurrentAmbientLightLevelCharacteristic.DEFAULT_MIN_VALUE,
                CurrentAmbientLightLevelCharacteristic.DEFAULT_MAX_VALUE,
                CurrentAmbientLightLevelCharacteristic.DEFAULT_STEP,
                () -> CompletableFuture.completedFuture(PowerSensor.toLux(SpanReadings.consumptionWatts(tree, circuitId))),
                onLux::set,
                () -> onLux.set(null)));
    return List.of(outlet, watts);
  }

  // --------------------------------------------------------------- identity

  @Override
  public int getId() {
    return id;
  }

  @Override
  public CompletableFuture<String> getName() {
    return CompletableFuture.completedFuture(displayName);
  }

  @Override
  public void identify() {
    // A breaker has nothing to flash.
  }

  @Override
  public CompletableFuture<String> getSerialNumber() {
    return CompletableFuture.completedFuture(circuitId);
  }

  @Override
  public CompletableFuture<String> getModel() {
    return CompletableFuture.completedFuture(switchable ? "SPAN circuit" : "SPAN circuit (read-only)");
  }

  @Override
  public CompletableFuture<String> getManufacturer() {
    return CompletableFuture.completedFuture("SPAN");
  }

  @Override
  public CompletableFuture<String> getFirmwareRevision() {
    return CompletableFuture.completedFuture(
        tree.value(panelSerial, "info", "firmware-version").map(HomeKitNames::firmware).orElse("1.0.0"));
  }

  private static void fire(AtomicReference<HomekitCharacteristicChangeCallback> callback) {
    HomekitCharacteristicChangeCallback current = callback.get();
    if (current != null) {
      current.changed();
    }
  }
}
