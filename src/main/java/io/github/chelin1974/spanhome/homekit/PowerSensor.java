package io.github.chelin1974.spanhome.homekit;

import io.github.hapjava.accessories.LightSensorAccessory;
import io.github.hapjava.accessories.optionalcharacteristic.AccessoryWithName;
import io.github.hapjava.characteristics.HomekitCharacteristicChangeCallback;
import io.github.hapjava.characteristics.impl.lightsensor.CurrentAmbientLightLevelCharacteristic;
import io.github.hapjava.services.Service;
import io.github.hapjava.services.impl.LightSensorService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;

/**
 * A wattage reading the Home app can display: a whole panel's draw, or the whole house's.
 *
 * <p>Apple Home has no power characteristic, so the reading is published as a light sensor:
 * the Home app shows "1450 lux" for 1,450 W. The same value also goes out as Eve's
 * Consumption characteristic, which the Eve app labels properly in watts.
 *
 * <p>Polls its source every few seconds and only notifies Home when the value moves by at
 * least a watt, so a busy panel does not flood HomeKit with events.
 */
public final class PowerSensor implements LightSensorAccessory, AccessoryWithName {

  static final long REFRESH_SECONDS = 10;

  private final int id;
  private final String name;
  private final String serial;
  private final DoubleSupplier watts;

  private final AtomicReference<HomekitCharacteristicChangeCallback> onLux = new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onWatts = new AtomicReference<>();

  private volatile double lastReported = Double.NaN;
  private volatile List<Service> services;

  public PowerSensor(int id, String name, String serial, DoubleSupplier watts, ScheduledExecutorService scheduler) {
    this.id = id;
    this.name = name;
    this.serial = serial;
    this.watts = watts;
    if (scheduler != null) {
      scheduler.scheduleWithFixedDelay(this::refresh, REFRESH_SECONDS, REFRESH_SECONDS, TimeUnit.SECONDS);
    }
  }

  /**
   * Watts as a light level. HomeKit's light level has a floor of 0.0001, so an idle reading
   * goes out as that floor (the Home app shows 0) rather than as 0, which HAP-Java would
   * clamp and warn about on every read.
   */
  public static double toLux(double watts) {
    double rounded = Math.round(Math.max(0, watts));
    return Math.min(
        CurrentAmbientLightLevelCharacteristic.DEFAULT_MAX_VALUE,
        Math.max(CurrentAmbientLightLevelCharacteristic.DEFAULT_MIN_VALUE, rounded));
  }

  public double watts() {
    return Math.max(0, watts.getAsDouble());
  }

  /** Notifies Home if the reading moved by at least a watt since the last notification. */
  void refresh() {
    try {
      double now = watts();
      if (Double.isNaN(lastReported) || Math.abs(now - lastReported) >= 1.0) {
        lastReported = now;
        fire(onLux);
        fire(onWatts);
      }
    } catch (RuntimeException ignored) {
      // A bad reading must not cancel the scheduled refresh.
    }
  }

  @Override
  public CompletableFuture<Double> getCurrentAmbientLightLevel() {
    return CompletableFuture.completedFuture(toLux(watts()));
  }

  @Override
  public void subscribeCurrentAmbientLightLevel(HomekitCharacteristicChangeCallback callback) {
    onLux.set(callback);
  }

  @Override
  public void unsubscribeCurrentAmbientLightLevel() {
    onLux.set(null);
  }

  @Override
  public synchronized List<Service> getServices() {
    if (services == null) {
      LightSensorService sensor = new LightSensorService(this);
      sensor.addCharacteristic(
          EveCharacteristics.readOnly(
              EveCharacteristics.CONSUMPTION, "Consumption", 1_000_000, 0.1, "W",
              () -> CompletableFuture.completedFuture(watts()),
              onWatts::set, () -> onWatts.set(null)));
      services = List.of(sensor);
    }
    return services;
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public CompletableFuture<String> getName() {
    return CompletableFuture.completedFuture(name);
  }

  @Override
  public void identify() {}

  @Override
  public CompletableFuture<String> getSerialNumber() {
    return CompletableFuture.completedFuture(serial);
  }

  @Override
  public CompletableFuture<String> getModel() {
    return CompletableFuture.completedFuture("Power (shown as lux)");
  }

  @Override
  public CompletableFuture<String> getManufacturer() {
    return CompletableFuture.completedFuture("SPAN");
  }

  @Override
  public CompletableFuture<String> getFirmwareRevision() {
    return CompletableFuture.completedFuture("1.0.0");
  }

  private static void fire(AtomicReference<HomekitCharacteristicChangeCallback> callback) {
    HomekitCharacteristicChangeCallback current = callback.get();
    if (current != null) {
      current.changed();
    }
  }
}
