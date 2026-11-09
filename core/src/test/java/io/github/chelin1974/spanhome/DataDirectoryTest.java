package io.github.chelin1974.spanhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every file the bridge writes lands in the data directory.
 *
 * <p>This is the test that makes that a guarantee rather than a convention. Four files are
 * written - pairing state, the panel credentials earned at registration, accessory IDs, the
 * layout number - and before {@code dataDirectory} existed each had its own rule, so a
 * container could end up with some of them on its mounted volume and the rest on the writable
 * layer, where they vanish on the next recreate. Losing the pairing state un-pairs every
 * controller.
 *
 * <p>The sweep below is deliberately over {@link SpanHomeConfig#writtenPaths()} rather than a
 * hand-listed set, so a fifth written file added later is covered the day it appears - as long
 * as it is added there, which is the one thing a reviewer has to check.
 */
class DataDirectoryTest {

  @TempDir Path home;

  private SpanHomeConfig configWith(String extra) throws IOException {
    Path directory = Files.createDirectories(home.resolve("checkout"));
    Path file = directory.resolve("span-home.config.json");
    Files.writeString(
        file,
        """
        {
          "panels": [{ "serial": "nj-0000-00000", "name": "Main" }]%s
        }
        """
            .formatted(extra.isEmpty() ? "" : ",\n  " + extra));
    return SpanHomeConfig.load(file);
  }

  @Test
  @DisplayName("with no dataDirectory, everything sits beside the config file")
  void defaultsToTheConfigDirectory() throws IOException {
    SpanHomeConfig config = configWith("");
    Path expected = home.resolve("checkout");

    assertEquals(expected, config.dataDirectory());
    assertAllUnder(expected, config.writtenPaths());
  }

  /** The container case: one setting puts every written file on the mounted volume. */
  @Test
  @DisplayName("a relative dataDirectory is resolved against the config file")
  void relativeDataDirectory() throws IOException {
    SpanHomeConfig config = configWith("\"dataDirectory\": \"data\"");
    Path expected = home.resolve("checkout/data");

    assertEquals(expected, config.dataDirectory());
    assertAllUnder(expected, config.writtenPaths());
  }

  @Test
  @DisplayName("an absolute dataDirectory is taken as given")
  void absoluteDataDirectory() throws IOException {
    SpanHomeConfig config = configWith("\"dataDirectory\": \"/data\"");

    assertEquals(Path.of("/data"), config.dataDirectory());
    assertAllUnder(Path.of("/data"), config.writtenPaths());
  }

  /**
   * The per-file settings still win, since someone may deliberately keep the pairing state
   * somewhere else - a backed-up volume, say. A relative one is still inside the data
   * directory.
   */
  @Test
  @DisplayName("a per-file override wins, and a relative one stays inside the data directory")
  void perFileOverrides() throws IOException {
    SpanHomeConfig config =
        configWith(
            "\"dataDirectory\": \"data\", \"storagePath\": \"/elsewhere/state.json\", "
                + "\"credentialsPath\": \"creds.json\"");

    assertEquals(Path.of("/elsewhere/state.json"), config.statePath());
    assertEquals(home.resolve("checkout/data/creds.json"), config.credentialsFile());
    // The ones with no override follow the data directory.
    assertEquals(
        home.resolve("checkout/data/span-home.accessories.json"), config.accessoryIdsFile());
    assertEquals(home.resolve("checkout/data/span-home.layout.json"), config.layoutFile());
  }

  /**
   * The layout file has no per-file setting at all, which is why anchoring everything to the
   * config file used to be the only way to move it. It follows the data directory now.
   */
  @Test
  @DisplayName("the layout file, which has no override, follows the data directory")
  void layoutFileFollowsTheDataDirectory() throws IOException {
    SpanHomeConfig config = configWith("\"dataDirectory\": \"/data\"");

    assertEquals(Path.of("/data/span-home.layout.json"), config.layoutFile());
  }

  @Test
  @DisplayName("every written path is listed for the startup log")
  void writtenPathsCoversEveryFile() throws IOException {
    Map<String, Path> paths = configWith("").writtenPaths();

    assertEquals(
        java.util.List.of("pairing state", "panel credentials", "accessory IDs", "layout number"),
        java.util.List.copyOf(paths.keySet()));
  }

  private static void assertAllUnder(Path directory, Map<String, Path> paths) {
    paths.forEach(
        (what, where) ->
            assertTrue(
                where.toAbsolutePath().startsWith(directory.toAbsolutePath()),
                what + " should be under " + directory + " but was " + where));
  }
}
