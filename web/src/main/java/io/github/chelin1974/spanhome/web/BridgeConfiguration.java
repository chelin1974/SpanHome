package io.github.chelin1974.spanhome.web;

import io.github.chelin1974.spanhome.SpanHomeBridge;
import io.github.chelin1974.spanhome.SpanHomeConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the bridge from {@code span-home.config.json} and hands it to Spring. */
@Configuration
public class BridgeConfiguration {

  private static final Logger log = LoggerFactory.getLogger(BridgeConfiguration.class);

  /** Looked at in order when {@code span.config-file} is not set. */
  static final List<String> DEFAULT_LOCATIONS =
      List.of("data/span-home.config.json", "span-home.config.json");

  /**
   * The configuration file, located the same way for every route into this service.
   *
   * @throws IllegalStateException with the candidate paths named when there is no file, since
   *     a bridge with no configuration cannot start and guessing would be worse
   */
  static Path locate(SpanProperties properties) {
    String configured = properties.getConfigFile();
    if (configured != null && !configured.isBlank()) {
      Path named = Path.of(configured.strip());
      if (!Files.isReadable(named)) {
        throw new IllegalStateException(
            "cannot read the configuration file "
                + named.toAbsolutePath()
                + ", named by span.config-file (SPAN_CONFIG_FILE)");
      }
      return named;
    }

    for (String candidate : DEFAULT_LOCATIONS) {
      Path path = Path.of(candidate);
      if (Files.isReadable(path)) {
        return path;
      }
    }

    throw new IllegalStateException(
        "no configuration file: looked for "
            + String.join(" and ", DEFAULT_LOCATIONS)
            + " under "
            + Path.of("").toAbsolutePath()
            + ". Copy span-home.config.example.json into data/ and fill it in, or set "
            + "span.config-file (SPAN_CONFIG_FILE).");
  }

  @Bean
  SpanHomeConfig spanHomeConfig(SpanProperties properties) {
    Path file = locate(properties);
    SpanHomeConfig config = SpanHomeConfig.load(file);
    log.info("configuration read from {}", file.toAbsolutePath());

    // A deployment can force where the written files go without editing the user's config.
    // The container uses this so the pairing state cannot land anywhere but the volume.
    String override = properties.getDataDirectory();
    if (override != null && !override.isBlank()) {
      config.dataDirectory = override.strip();
      log.info("writing to {} (from span.data-directory)", config.dataDirectory().toAbsolutePath());
    }

    return config;
  }

  @Bean
  SpanHomeBridge spanHomeBridge(SpanHomeConfig config) {
    SpanHomeBridge bridge = new SpanHomeBridge(config);

    // Only one of the two may bind the dashboard port. Spring is already listening on it by the
    // time the bridge starts - application.yml takes server.port from the same setting - so the
    // bridge's own small HTTP server stands down. A flag on the bridge rather than a config
    // value overwritten underneath it, matching CellGateHome, whose config is immutable.
    bridge.dashboardServedElsewhere();
    log.info("the Breaker Board dashboard is served by this service on port {}",
        config.dashboardPort);
    return bridge;
  }

  @Bean
  HistoryService historyService(SpanHomeConfig config) {
    return new HistoryService(config.prometheusUri().orElse(null));
  }
}
