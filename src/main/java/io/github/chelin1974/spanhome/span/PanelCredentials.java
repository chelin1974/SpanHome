package io.github.chelin1974.spanhome.span;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What the bridge needs to reach one panel's MQTT broker, saved by {@code --register}.
 *
 * <p>The file holds the broker password, which grants full control of every circuit, so it is
 * written owner-only and never logged. The CA certificate is the one the panel served at
 * registration; the bridge verifies the broker against it on every connect (trust on first use).
 */
public final class PanelCredentials {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** One panel's entry. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Panel {
    public String serial;
    public String host;
    public int mqttsPort = 8883;
    public String brokerUsername;
    public String brokerPassword;
    public String caCertificate;
    public String clientName;

    /** Redacted, so an entry can be logged. */
    @Override
    public String toString() {
      return "Panel[serial=" + serial + ", host=" + host + ", port=" + mqttsPort + "]";
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class FileShape {
    public Map<String, Panel> panels = new TreeMap<>();
  }

  private final Path file;
  private final Map<String, Panel> panels;

  private PanelCredentials(Path file, Map<String, Panel> panels) {
    this.file = file;
    this.panels = panels;
  }

  public static PanelCredentials load(Path file) {
    if (!Files.exists(file)) {
      return new PanelCredentials(file, new LinkedHashMap<>());
    }
    try {
      FileShape shape = MAPPER.readValue(Files.readString(file), FileShape.class);
      return new PanelCredentials(file, new LinkedHashMap<>(shape.panels));
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + file.toAbsolutePath(), e);
    }
  }

  /**
   * The entry for {@code serial}, ignoring case: a panel can report its serial in a different
   * case from the one printed in the SPAN app or typed into the config.
   */
  public Optional<Panel> get(String serial) {
    String wanted = key(serial);
    return panels.entrySet().stream()
        .filter(e -> key(e.getKey()).equals(wanted))
        .map(Map.Entry::getValue)
        .findFirst();
  }

  /** Registered serials, for an error message. Never includes secrets. */
  public List<String> serials() {
    return List.copyOf(panels.keySet());
  }

  private static String key(String serial) {
    return serial == null ? "" : serial.trim().toLowerCase(Locale.ROOT);
  }

  public Path file() {
    return file;
  }

  /** Adds or replaces one panel and writes the file atomically, owner-only. */
  public synchronized void put(Panel panel) {
    // Replace any entry for the same panel saved under a different case.
    panels.keySet().removeIf(existing -> key(existing).equals(key(panel.serial)));
    panels.put(panel.serial, panel);
    FileShape shape = new FileShape();
    shape.panels.putAll(panels);
    try {
      Path absolute = file.toAbsolutePath();
      Path directory = absolute.getParent();
      if (directory != null) {
        Files.createDirectories(directory);
      }
      Path temp = Files.createTempFile(directory, ".span-credentials", ".tmp");
      try {
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        // Not a POSIX filesystem; the user's own permissions apply.
      }
      Files.writeString(temp, MAPPER.writeValueAsString(shape));
      Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + file.toAbsolutePath(), e);
    }
  }
}
