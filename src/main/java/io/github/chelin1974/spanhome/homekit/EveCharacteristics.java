package io.github.chelin1974.spanhome.homekit;

import io.github.hapjava.characteristics.HomekitCharacteristicChangeCallback;
import io.github.hapjava.characteristics.impl.base.FloatCharacteristic;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Eve's custom power-metering characteristics.
 *
 * <p>Apple Home has no characteristic for watts or kWh. Eve defined its own for the Eve Energy
 * plug, and the Eve app (free, and it works with any HomeKit accessory) shows them on an
 * outlet. Apple Home ignores them, so they cost nothing there.
 *
 * <p>These are live readings only. Eve's history graphs need its proprietary history service,
 * which this bridge does not implement.
 */
public final class EveCharacteristics {

  public static final String CONSUMPTION = "E863F10D-079E-48FF-8F27-9C2605A29F52";
  public static final String TOTAL_CONSUMPTION = "E863F10C-079E-48FF-8F27-9C2605A29F52";
  public static final String VOLTAGE = "E863F10A-079E-48FF-8F27-9C2605A29F52";
  public static final String CURRENT = "E863F126-079E-48FF-8F27-9C2605A29F52";

  private EveCharacteristics() {}

  /** A read-only, notifying float. */
  static FloatCharacteristic readOnly(
      String type,
      String description,
      double max,
      double step,
      String unit,
      Supplier<CompletableFuture<Double>> getter,
      Consumer<HomekitCharacteristicChangeCallback> subscriber,
      Runnable unsubscriber) {
    return new FloatCharacteristic(
        type,
        description,
        0,
        max,
        step,
        unit,
        Optional.of(getter),
        Optional.empty(),
        Optional.of(subscriber),
        Optional.of(unsubscriber)) {};
  }
}
