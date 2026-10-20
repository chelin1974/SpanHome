package io.github.chelin1974.spanhome.span;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One panel's Homie 5 device tree, rebuilt from the MQTT messages the panel publishes.
 *
 * <p>Pure state: feed it {@link #accept(String, String)} with every message from
 * {@code ebus/5/#} and read devices and property values back. No network, so the awkward parts
 * (the parent/child model, descriptions arriving after values, retained messages) are easy to
 * test.
 *
 * <p>Follows the r202633 parent/child data model: the panel is the root device, every circuit,
 * lugs and DER is a child, and a battery's MID is a grandchild. Devices are classified by the
 * {@code type} in their {@code $description}, never by parsing their IDs.
 *
 * <p>Thread-safe: the MQTT client delivers on its own thread while HomeKit reads on others.
 */
public final class HomieTree {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String PREFIX = "ebus/5/";

  public static final String TYPE_PANEL = "energy.ebus.device.distribution-enclosure";
  public static final String TYPE_CIRCUIT = "energy.ebus.device.circuit";
  public static final String TYPE_BESS = "energy.ebus.device.bess";
  public static final String TYPE_MID = "energy.ebus.device.mid";

  /** Called after a property value changes. */
  @FunctionalInterface
  public interface Listener {
    void propertyChanged(String deviceId, String node, String property, String value);
  }

  /** A device as declared by its {@code $description}. */
  public record Device(
      String id, String type, String name, String parent, List<String> children, Set<String> settable) {

    public boolean isSettable(String node, String property) {
      return settable.contains(node + "/" + property);
    }
  }

  private final String rootId;
  private final Map<String, Device> devices = new ConcurrentHashMap<>();
  private final Map<String, String> states = new ConcurrentHashMap<>();
  private final Map<String, String> values = new ConcurrentHashMap<>();
  private final List<Listener> listeners = new CopyOnWriteArrayList<>();

  /** @param rootId the panel serial, which is the root device's ID */
  public HomieTree(String rootId) {
    this.rootId = rootId;
  }

  public String rootId() {
    return rootId;
  }

  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  /**
   * Applies one MQTT message. Messages outside {@code ebus/5/}, {@code /set} echoes and
   * malformed descriptions are ignored rather than thrown, so one bad message cannot stall the
   * client's delivery thread.
   */
  public void accept(String topic, String payload) {
    if (topic == null || !topic.startsWith(PREFIX)) {
      return;
    }
    String[] parts = topic.substring(PREFIX.length()).split("/");
    if (parts.length < 2) {
      return;
    }
    String deviceId = parts[0];
    String value = payload == null ? "" : payload;

    if (parts.length == 2) {
      switch (parts[1]) {
        case "$state" -> states.put(deviceId, value);
        case "$description" -> parseDescription(deviceId, value).ifPresent(d -> devices.put(deviceId, d));
        default -> {
          // Other device attributes are not used.
        }
      }
      return;
    }

    // <device>/<node>/<property>; longer topics are /set commands or Homie attributes.
    if (parts.length != 3 || parts[1].startsWith("$") || parts[2].startsWith("$")) {
      return;
    }
    String key = deviceId + "/" + parts[1] + "/" + parts[2];
    String previous = values.put(key, value);
    if (!value.equals(previous)) {
      for (Listener listener : listeners) {
        try {
          listener.propertyChanged(deviceId, parts[1], parts[2], value);
        } catch (RuntimeException ignored) {
          // A listener bug must not stop the tree from updating.
        }
      }
    }
  }

  static Optional<Device> parseDescription(String deviceId, String json) {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (Exception e) {
      return Optional.empty();
    }
    if (root == null || !root.isObject()) {
      return Optional.empty();
    }

    List<String> children = new ArrayList<>();
    root.path("children").forEach(child -> {
      if (child.isTextual() && !child.asText().isBlank()) {
        children.add(child.asText());
      }
    });

    Set<String> settable = new HashSet<>();
    root.path("nodes").fields().forEachRemaining(node ->
        node.getValue().path("properties").fields().forEachRemaining(property -> {
          if (property.getValue().path("settable").asBoolean(false)) {
            settable.add(node.getKey() + "/" + property.getKey());
          }
        }));

    return Optional.of(
        new Device(
            deviceId,
            root.path("type").asText(""),
            root.path("name").asText(""),
            root.path("parent").asText(null),
            List.copyOf(children),
            Set.copyOf(settable)));
  }

  // -------------------------------------------------------------------- reads

  public Optional<Device> device(String deviceId) {
    return Optional.ofNullable(devices.get(deviceId));
  }

  public Optional<String> value(String deviceId, String node, String property) {
    return Optional.ofNullable(values.get(deviceId + "/" + node + "/" + property));
  }

  public Optional<Double> number(String deviceId, String node, String property) {
    return value(deviceId, node, property).flatMap(HomieTree::parseNumber);
  }

  /** The device's own {@code $state}, or empty if none has arrived. */
  public Optional<String> state(String deviceId) {
    return Optional.ofNullable(states.get(deviceId));
  }

  /**
   * Homie 5 effective state: when the root is {@code lost} or {@code disconnected}, every
   * child is too, whatever its own {@code $state} says.
   */
  public boolean isOnline(String deviceId) {
    String root = states.get(rootId);
    if (root != null && !root.equals("ready")) {
      return false;
    }
    return "ready".equals(states.get(deviceId));
  }

  /** Every device reachable from the root by following {@code children}, root first. */
  public List<Device> reachable() {
    List<Device> found = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    Deque<String> queue = new ArrayDeque<>(List.of(rootId));
    while (!queue.isEmpty()) {
      String id = queue.poll();
      if (!seen.add(id)) {
        continue;
      }
      Device device = devices.get(id);
      if (device != null) {
        found.add(device);
        queue.addAll(device.children());
      }
    }
    return found;
  }

  public List<Device> ofType(String type) {
    return reachable().stream().filter(d -> d.type().equals(type)).toList();
  }

  /**
   * True once the root and every device it names, recursively, has published a description.
   *
   * <p>The panel republishes its whole tree when a client connects, but descriptions arrive in
   * no particular order; accessories are built only after this is true, so none are missing.
   */
  public boolean isDiscoveryComplete() {
    Device root = devices.get(rootId);
    if (root == null) {
      return false;
    }
    Deque<String> queue = new ArrayDeque<>(root.children());
    Set<String> seen = new HashSet<>();
    while (!queue.isEmpty()) {
      String id = queue.poll();
      if (!seen.add(id)) {
        continue;
      }
      Device device = devices.get(id);
      if (device == null) {
        return false;
      }
      queue.addAll(device.children());
    }
    return true;
  }

  /** IDs named as children whose descriptions have not arrived, for a timeout message. */
  public List<String> missingDescriptions() {
    List<String> missing = new ArrayList<>();
    Device root = devices.get(rootId);
    if (root == null) {
      return List.of(rootId);
    }
    Deque<String> queue = new ArrayDeque<>(root.children());
    Set<String> seen = new HashSet<>();
    while (!queue.isEmpty()) {
      String id = queue.poll();
      if (!seen.add(id)) {
        continue;
      }
      Device device = devices.get(id);
      if (device == null) {
        missing.add(id);
      } else {
        queue.addAll(device.children());
      }
    }
    return missing;
  }

  static Optional<Double> parseNumber(String value) {
    try {
      double parsed = Double.parseDouble(value.trim());
      return Double.isFinite(parsed) ? Optional.of(parsed) : Optional.empty();
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
