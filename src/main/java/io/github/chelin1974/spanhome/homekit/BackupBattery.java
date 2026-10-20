package io.github.chelin1974.spanhome.homekit;

import io.github.chelin1974.spanhome.span.HomieTree;
import io.github.chelin1974.spanhome.span.SpanReadings;
import io.github.chelin1974.spanhome.span.SpanReadings.BatteryFlow;
import io.github.chelin1974.spanhome.span.SpanReadings.Grid;
import io.github.hapjava.accessories.BatteryAccessory;
import io.github.hapjava.accessories.ContactSensorAccessory;
import io.github.hapjava.characteristics.HomekitCharacteristicChangeCallback;
import io.github.hapjava.characteristics.impl.battery.ChargingStateEnum;
import io.github.hapjava.characteristics.impl.battery.StatusLowBatteryEnum;
import io.github.hapjava.characteristics.impl.contactsensor.ContactStateEnum;
import io.github.hapjava.services.Service;
import io.github.hapjava.services.impl.BatteryService;
import io.github.hapjava.services.impl.ContactSensorService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A panel's backup battery, as one HomeKit accessory with two services.
 *
 * <ul>
 *   <li>A <b>contact sensor</b> for the grid connection: <i>closed</i> while on the grid,
 *       <i>open</i> during an outage. This is the tile the Home app shows, and the useful part:
 *       "when the grid goes down" becomes a normal Home automation trigger.
 *   <li>A <b>battery service</b> with charge level, charging state and a low-battery flag. The
 *       Home app shows it in the accessory's details and raises low-battery alerts; Apple Home has
 *       no battery tile of its own.
 * </ul>
 *
 * <p>The grid state comes from the MID's {@code grid/islanding-state}, the relay position. The
 * MID's {@code grid-state} is always {@code UNKNOWN} on SPAN hardware and is not used.
 */
public final class BackupBattery implements ContactSensorAccessory, BatteryAccessory {

  private final int id;
  private final String name;
  private final HomieTree tree;
  private final String bessId;

  private final AtomicReference<HomekitCharacteristicChangeCallback> onGrid = new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onLevel = new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onLow = new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onCharging = new AtomicReference<>();

  private volatile List<Service> services;
  private volatile BatteryFlow lastFlow;

  public BackupBattery(int id, String name, HomieTree tree, String bessId) {
    this.id = id;
    this.name = name;
    this.tree = tree;
    this.bessId = bessId;
    this.lastFlow = SpanReadings.batteryFlow(tree);
  }

  /** Called for every property change anywhere in this panel's tree. */
  public void propertyChanged(String deviceId, String node, String property) {
    if (node.equals("grid") && property.equals("islanding-state")) {
      fire(onGrid);
    } else if (deviceId.equals(bessId) && node.equals("soc") && property.equals("soc")) {
      fire(onLevel);
      fire(onLow);
    } else if (deviceId.equals(tree.rootId()) && node.equals("power-flows") && property.equals("battery")) {
      BatteryFlow flow = SpanReadings.batteryFlow(tree);
      if (flow != lastFlow) {
        lastFlow = flow;
        fire(onCharging);
      }
    }
  }

  // -------------------------------------------------------------------- grid

  @Override
  public CompletableFuture<ContactStateEnum> getCurrentState() {
    // DETECTED is "closed". Unknown reads as on-grid so a gap in data is not an outage alarm.
    return CompletableFuture.completedFuture(
        SpanReadings.grid(tree) == Grid.OFF_GRID ? ContactStateEnum.NOT_DETECTED : ContactStateEnum.DETECTED);
  }

  @Override
  public void subscribeContactState(HomekitCharacteristicChangeCallback callback) {
    onGrid.set(callback);
  }

  @Override
  public void unsubscribeContactState() {
    onGrid.set(null);
  }

  // ----------------------------------------------------------------- battery

  @Override
  public CompletableFuture<Integer> getBatteryLevel() {
    return CompletableFuture.completedFuture(SpanReadings.stateOfCharge(tree, bessId).orElse(0));
  }

  @Override
  public CompletableFuture<StatusLowBatteryEnum> getLowBatteryState() {
    boolean low =
        SpanReadings.stateOfCharge(tree, bessId).map(soc -> soc < SpanReadings.LOW_BATTERY_PERCENT).orElse(false);
    return CompletableFuture.completedFuture(low ? StatusLowBatteryEnum.LOW : StatusLowBatteryEnum.NORMAL);
  }

  @Override
  public CompletableFuture<ChargingStateEnum> getChargingState() {
    return CompletableFuture.completedFuture(
        SpanReadings.batteryFlow(tree) == BatteryFlow.CHARGING ? ChargingStateEnum.CHARGING : ChargingStateEnum.NOT_CHARGING);
  }

  @Override
  public void subscribeBatteryLevel(HomekitCharacteristicChangeCallback callback) {
    onLevel.set(callback);
  }

  @Override
  public void subscribeLowBatteryState(HomekitCharacteristicChangeCallback callback) {
    onLow.set(callback);
  }

  @Override
  public void subscribeBatteryChargingState(HomekitCharacteristicChangeCallback callback) {
    onCharging.set(callback);
  }

  @Override
  public void unsubscribeBatteryLevel() {
    onLevel.set(null);
  }

  @Override
  public void unsubscribeLowBatteryState() {
    onLow.set(null);
  }

  @Override
  public void unsubscribeBatteryChargingState() {
    onCharging.set(null);
  }

  // ---------------------------------------------------------------- services

  @Override
  public synchronized List<Service> getServices() {
    if (services == null) {
      ContactSensorService grid = new ContactSensorService(this);
      BatteryService battery = new BatteryService(this);
      grid.addLinkedService(battery);
      services = List.of(grid, battery);
    }
    return services;
  }

  // ---------------------------------------------------------------- identity

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
    return CompletableFuture.completedFuture(
        tree.value(bessId, "info", "serial-number").filter(s -> !s.isBlank()).orElse(bessId));
  }

  @Override
  public CompletableFuture<String> getModel() {
    return CompletableFuture.completedFuture(
        tree.value(bessId, "info", "model").filter(s -> !s.isBlank()).orElse("Backup battery"));
  }

  @Override
  public CompletableFuture<String> getManufacturer() {
    return CompletableFuture.completedFuture(
        tree.value(bessId, "info", "vendor-name").filter(s -> !s.isBlank()).orElse("SPAN"));
  }

  @Override
  public CompletableFuture<String> getFirmwareRevision() {
    return CompletableFuture.completedFuture(
        HomeKitNames.firmware(tree.value(bessId, "info", "firmware-version").orElse(null)));
  }

  private static void fire(AtomicReference<HomekitCharacteristicChangeCallback> callback) {
    HomekitCharacteristicChangeCallback current = callback.get();
    if (current != null) {
      current.changed();
    }
  }
}
