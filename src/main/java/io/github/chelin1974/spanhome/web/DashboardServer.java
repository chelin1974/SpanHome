package io.github.chelin1974.spanhome.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.chelin1974.spanhome.web.DashboardModel.RelayResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Breaker Board dashboard, served on the home network.
 *
 * <pre>
 *   GET  /                              the page
 *   GET  /api/state                     every circuit, total and battery reading, live
 *   GET  /api/history                   24 h of 15-minute averages, from Prometheus
 *   GET  /api/circuits/{id}/detail      30 days of one circuit: averages, peaks, energy
 *   POST /api/circuits/{id}/relay       {"on": true|false}, with header X-Passcode
 * </pre>
 *
 * <p>Answers only devices on the local network (private, link-local and loopback addresses),
 * even if a router forwards the port by mistake. Switching needs the household passcode from
 * {@code dashboardPasscode}; with none set, the page is view-only. Five wrong passcodes from one
 * address lock that address out for five minutes.
 */
public final class DashboardServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DashboardServer.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern DETAIL = Pattern.compile("^/api/circuits/([A-Za-z0-9._:-]{1,128})/detail$");
  private static final Pattern RELAY = Pattern.compile("^/api/circuits/([A-Za-z0-9._:-]{1,128})/relay$");

  static final int MAX_FAILURES = 5;
  static final Duration LOCKOUT = Duration.ofMinutes(5);

  private final DashboardModel model;
  private final PrometheusHistory history;
  private final byte[] passcode;
  private final HttpServer server;
  private final ExecutorService executor;
  private final Map<String, Failures> failures = new ConcurrentHashMap<>();

  static final class Failures {
    int count;
    Instant lockedUntil = Instant.EPOCH;
  }

  /**
   * @param history null when no Prometheus is configured; the page then shows live readings only
   * @param passcode null or blank for a view-only page
   */
  public DashboardServer(int port, DashboardModel model, PrometheusHistory history, String passcode) throws IOException {
    this.model = model;
    this.history = history;
    this.passcode = passcode == null || passcode.isBlank() ? null : passcode.getBytes(StandardCharsets.UTF_8);
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    this.executor = Executors.newFixedThreadPool(4, r -> {
      Thread t = new Thread(r, "span-home-web");
      t.setDaemon(true);
      return t;
    });
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public boolean switchingEnabled() {
    return passcode != null;
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      InetAddress remote = exchange.getRemoteAddress().getAddress();
      if (!isLocal(remote)) {
        send(exchange, 403, Map.of("error", "This dashboard only answers devices on the home network."));
        return;
      }
      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();

      if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
        page(exchange);
      } else if (method.equals("GET") && path.equals("/api/state")) {
        send(exchange, 200, model.state(switchingEnabled()));
      } else if (method.equals("GET") && path.equals("/api/history")) {
        history(exchange);
      } else {
        Matcher relay = RELAY.matcher(path);
        Matcher detail = DETAIL.matcher(path);
        if (relay.matches() && method.equals("POST")) {
          relay(exchange, relay.group(1), remote);
        } else if (detail.matches() && method.equals("GET")) {
          detail(exchange, detail.group(1));
        } else {
          send(exchange, 404, Map.of("error", "Not found"));
        }
      }
    } catch (RuntimeException e) {
      log.warn("dashboard request failed: {}", e.getMessage());
    }
  }

  private void page(HttpExchange exchange) throws IOException {
    byte[] html;
    try (InputStream in = DashboardServer.class.getResourceAsStream("/dashboard/index.html")) {
      if (in == null) {
        send(exchange, 500, Map.of("error", "The dashboard page is missing from the jar."));
        return;
      }
      html = in.readAllBytes();
    }
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(200, html.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(html);
    }
  }

  private void history(HttpExchange exchange) throws IOException {
    if (history == null) {
      send(exchange, 200, Map.of("available", false, "reason", "No Prometheus is configured, so there is no history to show."));
      return;
    }
    try {
      Map<String, Object> body = new java.util.LinkedHashMap<>(history.last24Hours(System.currentTimeMillis()));
      body.put("available", true);
      send(exchange, 200, body);
    } catch (Exception e) {
      send(exchange, 200, Map.of("available", false,
          "reason", "Prometheus at " + history.base() + " did not answer (" + e.getMessage() + ")."));
    }
  }

  private void detail(HttpExchange exchange, String circuitId) throws IOException {
    java.util.Optional<java.util.List<String>> legs = model.legsOf(circuitId);
    if (legs.isEmpty()) {
      send(exchange, 404, Map.of("error", "No such circuit."));
      return;
    }
    if (history == null) {
      send(exchange, 200, Map.of("available", false, "reason", "No Prometheus is configured, so there is no history to show."));
      return;
    }
    try {
      Map<String, Object> body = new java.util.LinkedHashMap<>(history.circuitDetail(legs.get(), System.currentTimeMillis()));
      body.put("available", true);
      send(exchange, 200, body);
    } catch (Exception e) {
      send(exchange, 200, Map.of("available", false,
          "reason", "Prometheus at " + history.base() + " did not answer (" + e.getMessage() + ")."));
    }
  }

  private void relay(HttpExchange exchange, String circuitId, InetAddress remote) throws IOException {
    if (passcode == null) {
      send(exchange, 403, Map.of("error", "Switching from the dashboard is off. Set dashboardPasscode in span-home.config.json to turn it on."));
      return;
    }
    String who = remote.getHostAddress();
    Failures f = failures.computeIfAbsent(who, k -> new Failures());
    synchronized (f) {
      if (Instant.now().isBefore(f.lockedUntil)) {
        send(exchange, 429, Map.of("error", "Too many wrong passcodes. Try again in a few minutes."));
        return;
      }
      String given = exchange.getRequestHeaders().getFirst("X-Passcode");
      if (given == null || !MessageDigest.isEqual(passcode, given.getBytes(StandardCharsets.UTF_8))) {
        f.count++;
        if (f.count >= MAX_FAILURES) {
          f.lockedUntil = Instant.now().plus(LOCKOUT);
          f.count = 0;
          log.warn("dashboard: {} wrong passcodes from {}; locked out for {} minutes", MAX_FAILURES, who, LOCKOUT.toMinutes());
        }
        send(exchange, 401, Map.of("error", "Wrong passcode."));
        return;
      }
      f.count = 0;
    }

    JsonNode body;
    try {
      body = MAPPER.readTree(exchange.getRequestBody().readNBytes(1024));
    } catch (IOException e) {
      send(exchange, 400, Map.of("error", "Send {\"on\": true} or {\"on\": false}."));
      return;
    }
    if (body == null || !body.path("on").isBoolean()) {
      send(exchange, 400, Map.of("error", "Send {\"on\": true} or {\"on\": false}."));
      return;
    }
    boolean on = body.get("on").asBoolean();
    RelayResult result = model.setRelay(circuitId, on);
    switch (result) {
      case OK -> {
        log.info("dashboard: {} turned circuit {} {}", who, circuitId, on ? "on" : "off");
        send(exchange, 200, Map.of("ok", true));
      }
      case LOCKED -> send(exchange, 409, Map.of("error", "This circuit can't be switched."));
      case NOT_FOUND -> send(exchange, 404, Map.of("error", "No such circuit."));
      case FAILED -> send(exchange, 502, Map.of("error", "The panel did not take the command."));
    }
  }

  /** Private IPv4, IPv6 unique-local, link-local and loopback. */
  static boolean isLocal(InetAddress address) {
    if (address == null) {
      return false;
    }
    if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
      return true;
    }
    if (address instanceof Inet6Address) {
      byte first = address.getAddress()[0];
      return (first & 0xfe) == 0xfc; // fc00::/7
    }
    return false;
  }

  private static void send(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = MAPPER.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
