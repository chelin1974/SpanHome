package io.github.chelin1974.spanhome;

import static io.github.chelin1974.spanhome.PanelFixture.FRIDGE;
import static io.github.chelin1974.spanhome.PanelFixture.HOT_TUB;
import static io.github.chelin1974.spanhome.PanelFixture.PANEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.chelin1974.spanhome.homekit.CircuitOutlet;
import io.github.chelin1974.spanhome.metrics.OtlpExporter;
import io.github.chelin1974.spanhome.metrics.OtlpMetrics;
import io.github.chelin1974.spanhome.metrics.OtlpMetrics.Point;
import io.github.chelin1974.spanhome.span.HomieTree;
import io.github.chelin1974.spanhome.web.DashboardModel;
import io.github.chelin1974.spanhome.web.DashboardModel.PanelSource;
import io.github.chelin1974.spanhome.web.DashboardServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetricsAndDashboardTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private HomieTree tree;
  private ScheduledExecutorService scheduler;
  private List<String> commands;
  private AtomicBoolean connected;
  private DashboardModel model;

  @BeforeEach
  void setUp() {
    tree = PanelFixture.tree();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    commands = new CopyOnWriteArrayList<>();
    connected = new AtomicBoolean(true);
    CircuitOutlet hotTub = new CircuitOutlet(2, HOT_TUB, "Hot Tub", PANEL, tree, true,
        (id, on) -> commands.add(id + "=" + (on ? "CLOSED" : "OPEN")), scheduler);
    CircuitOutlet fridge = new CircuitOutlet(3, FRIDGE, "Kitchen Fridge", PANEL, tree, false,
        (id, on) -> commands.add(id + "=" + (on ? "CLOSED" : "OPEN")), scheduler);
    model = new DashboardModel(List.of(new PanelSource("Main", tree,
        Map.of(HOT_TUB, hotTub, FRIDGE, fridge), Set.of(PanelFixture.PV), connected::get)));
  }

  @AfterEach
  void tearDown() {
    scheduler.shutdownNow();
  }

  private static Point point(List<Point> points, String name, String circuitId) {
    return points.stream()
        .filter(p -> p.name().equals(name) && (circuitId == null || circuitId.equals(p.attributes().get("circuit.id"))))
        .findFirst().orElseThrow(() -> new AssertionError("no " + name + " for " + circuitId));
  }

  // ------------------------------------------------------------------- OTLP

  @Test
  @DisplayName("collects circuit, panel, house, battery and grid readings with OTel attributes")
  void collects() {
    List<Point> points = OtlpMetrics.collect(model);

    Point power = point(points, "span.circuit.power", HOT_TUB);
    assertEquals(1450.5, power.value(), 1e-9);
    assertEquals("W", power.unit());
    assertEquals("Main", power.attributes().get("panel"));
    assertEquals("Hot Tub", power.attributes().get("circuit"));
    assertEquals("17,19", power.attributes().get("circuit.spaces"));

    assertEquals(1.0, point(points, "span.circuit.relay", HOT_TUB).value(), 1e-9);
    Point energy = point(points, "span.circuit.energy", HOT_TUB);
    assertTrue(energy.cumulative());
    assertEquals("J", energy.unit());
    assertEquals(123456.0 * 3600, energy.value(), 1e-6, "Wh from the panel, sent as joules");

    assertEquals(1452.6, point(points, "span.panel.power", null).value(), 1e-9);
    assertEquals(1452.6, point(points, "span.house.power", null).value(), 1e-9);
    assertEquals(88.0, point(points, "span.battery.charge", null).value(), 1e-9);
    assertEquals(1200.0, point(points, "span.battery.power", null).value(), 1e-9);
    assertEquals(1.0, point(points, "span.grid.connected", null).value(), 1e-9);
  }

  @Test
  @DisplayName("a disconnected panel sends nothing, so graphs show a gap instead of stale values")
  void disconnectedSendsNothing() {
    connected.set(false);
    assertTrue(OtlpMetrics.collect(model).isEmpty());
  }

  @Test
  @DisplayName("the request is valid OTLP JSON: one metric per name, gauges and a cumulative sum")
  void requestShape() throws Exception {
    JsonNode json = MAPPER.valueToTree(OtlpMetrics.request(OtlpMetrics.collect(model), 2_000_000_000L, 1_000_000_000L,
        Map.of("service.name", "span-home"), "1.0.0"));
    JsonNode rm = json.path("resourceMetrics").get(0);
    assertEquals("service.name", rm.path("resource").path("attributes").get(0).path("key").asText());
    JsonNode metrics = rm.path("scopeMetrics").get(0).path("metrics");

    JsonNode power = null, energy = null;
    for (JsonNode m : metrics) {
      if (m.path("name").asText().equals("span.circuit.power")) power = m;
      if (m.path("name").asText().equals("span.circuit.energy")) energy = m;
    }
    assertEquals(3, power.path("gauge").path("dataPoints").size(), "one point per circuit");
    JsonNode dp = power.path("gauge").path("dataPoints").get(0);
    assertTrue(dp.path("timeUnixNano").isTextual(), "64-bit times go out as strings");
    assertEquals("2000000000", dp.path("timeUnixNano").asText());
    assertTrue(dp.path("asDouble").isNumber());
    assertEquals(2, energy.path("sum").path("aggregationTemporality").asInt());
    assertTrue(energy.path("sum").path("isMonotonic").asBoolean());
    assertEquals("1000000000", energy.path("sum").path("dataPoints").get(0).path("startTimeUnixNano").asText());
  }

  @Test
  @DisplayName("the exporter posts application/json to the endpoint and reports success or failure")
  void exporterPosts() throws Exception {
    List<String> bodies = new CopyOnWriteArrayList<>();
    List<String> types = new CopyOnWriteArrayList<>();
    HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicBoolean accept = new AtomicBoolean(true);
    receiver.createContext("/api/v1/otlp/v1/metrics", exchange -> {
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      types.add(exchange.getRequestHeaders().getFirst("Content-Type"));
      exchange.sendResponseHeaders(accept.get() ? 200 : 400, -1);
      exchange.close();
    });
    receiver.start();
    try {
      URI endpoint = URI.create("http://127.0.0.1:" + receiver.getAddress().getPort() + "/api/v1/otlp/v1/metrics");
      OtlpExporter exporter = new OtlpExporter(endpoint, model, Duration.ofSeconds(15), "1.0.0");
      assertTrue(exporter.push());
      assertEquals("application/json", types.get(0));
      assertTrue(MAPPER.readTree(bodies.get(0)).has("resourceMetrics"));

      accept.set(false);
      assertFalse(exporter.push(), "a rejected push is reported, not thrown");
      exporter.close();
    } finally {
      receiver.stop(0);
    }
  }

  @Test
  @DisplayName("an unreachable endpoint fails quietly")
  void exporterUnreachable() {
    OtlpExporter exporter = new OtlpExporter(URI.create("http://127.0.0.1:1/v1/metrics"), model, Duration.ofSeconds(15), "1.0.0");
    assertFalse(exporter.push());
    exporter.close();
  }

  // ------------------------------------------------------------- dashboard

  private DashboardServer server(String passcode) throws Exception {
    DashboardServer server = new DashboardServer(0, model, null, passcode);
    server.start();
    return server;
  }

  private static HttpResponse<String> get(DashboardServer s, String path) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> relay(DashboardServer s, String id, String passcode, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/api/circuits/" + id + "/relay"))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    if (passcode != null) {
      b.header("X-Passcode", passcode);
    }
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("legsOf resolves a circuit, a combined circuit's legs, and nothing for unknown IDs")
  void legsOf() {
    assertEquals(java.util.Optional.of(List.of(HOT_TUB)), model.legsOf(HOT_TUB));
    assertEquals(java.util.Optional.of(List.of(PanelFixture.PV)), model.legsOf(PanelFixture.PV));
    assertTrue(model.legsOf("x\"}) or vector(1)").isEmpty());
    CircuitOutlet garage = new CircuitOutlet(9, List.of(HOT_TUB, FRIDGE), "Garage", PANEL, tree, true, (id, on) -> {}, scheduler);
    DashboardModel combined = new DashboardModel(List.of(new PanelSource("Main", tree,
        Map.of(HOT_TUB, garage, FRIDGE, garage), Set.of(), connected::get)));
    assertEquals(java.util.Optional.of(List.of(HOT_TUB, FRIDGE)), combined.legsOf(FRIDGE));
  }

  @Test
  @DisplayName("a combined circuit is one dashboard entry with both legs' spaces and summed watts")
  void combinedEntry() throws Exception {
    CircuitOutlet garage = new CircuitOutlet(9, List.of(HOT_TUB, FRIDGE), "Garage", PANEL, tree, true,
        (id, on) -> commands.add(id + "=" + (on ? "CLOSED" : "OPEN")), scheduler);
    DashboardModel combined = new DashboardModel(List.of(new PanelSource("Main", tree,
        Map.of(HOT_TUB, garage, FRIDGE, garage), Set.of(), connected::get)));
    @SuppressWarnings("unchecked")
    Map<String, Object> panel = (Map<String, Object>) ((List<Object>) combined.state(true).get("panels")).get(0);
    List<Map<String, Object>> entries = new java.util.ArrayList<>();
    for (Object o : (List<Object>) panel.get("circuits")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> c = (Map<String, Object>) o;
      if (c.get("id").equals(HOT_TUB) || c.get("id").equals(FRIDGE)) entries.add(c);
    }
    assertEquals(1, entries.size());
    Map<String, Object> c = entries.get(0);
    assertEquals("Garage", c.get("name"));
    assertEquals(List.of(HOT_TUB, FRIDGE), c.get("legs"));
    assertEquals(Math.round(garage.watts() * 10) / 10.0, (Double) c.get("watts"), 1e-9);
    assertEquals(DashboardModel.RelayResult.OK, combined.setRelay(FRIDGE, false));
    assertEquals(List.of(HOT_TUB + "=OPEN", FRIDGE + "=OPEN"), commands);
  }

  @Test
  @DisplayName("state carries each circuit's room from the config, null when it has none")
  void rooms() {
    DashboardModel withRooms = new DashboardModel(model.panels(),
        (panel, name, id) -> id.equals(HOT_TUB) ? "Backyard" : null);
    @SuppressWarnings("unchecked")
    Map<String, Object> panel = (Map<String, Object>) ((List<Object>) withRooms.state(false).get("panels")).get(0);
    int seen = 0;
    for (Object o : (List<Object>) panel.get("circuits")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> c = (Map<String, Object>) o;
      assertTrue(c.containsKey("room"));
      assertEquals(c.get("id").equals(HOT_TUB) ? "Backyard" : null, c.get("room"));
      seen++;
    }
    assertTrue(seen > 1);
  }

  @Test
  @DisplayName("state lists every circuit with watts, relay, lock and hidden flags, plus totals and battery")
  void state() throws Exception {
    try (DashboardServer s = server("1234")) {
      JsonNode state = MAPPER.readTree(get(s, "/api/state").body());
      assertTrue(state.path("switching").asBoolean());
      assertEquals(1452.6, state.path("houseWatts").asDouble(), 1e-9);
      JsonNode panel = state.path("panels").get(0);
      assertEquals("Main", panel.path("label").asText());
      assertEquals(88, panel.path("battery").path("percent").asInt());
      assertEquals("charging", panel.path("battery").path("flow").asText());

      JsonNode hotTub = null, pv = null, fridge = null;
      for (JsonNode c : panel.path("circuits")) {
        if (c.path("id").asText().equals(HOT_TUB)) hotTub = c;
        if (c.path("id").asText().equals(PanelFixture.PV)) pv = c;
        if (c.path("id").asText().equals(FRIDGE)) fridge = c;
      }
      assertEquals(1450.5, hotTub.path("watts").asDouble(), 1e-9);
      assertEquals(List.of(17, 19), MAPPER.convertValue(hotTub.path("spaces"), List.class));
      assertTrue(hotTub.path("switchable").asBoolean());
      assertTrue(pv.path("hidden").asBoolean());
      assertTrue(fridge.path("locked").asBoolean(), "the panel will not switch the fridge");
    }
  }

  @Test
  @DisplayName("the page itself is served")
  void page() throws Exception {
    try (DashboardServer s = server(null)) {
      HttpResponse<String> page = get(s, "/");
      assertEquals(200, page.statusCode());
      assertTrue(page.body().contains("<title>Breaker Board</title>"));
    }
  }

  @Test
  @DisplayName("the right passcode switches the circuit through its HomeKit outlet")
  void switches() throws Exception {
    try (DashboardServer s = server("1234")) {
      assertEquals(200, relay(s, HOT_TUB, "1234", "{\"on\":false}").statusCode());
      assertEquals(List.of(HOT_TUB + "=OPEN"), commands);
    }
  }

  @Test
  @DisplayName("a wrong passcode is refused, and five lock that address out")
  void lockout() throws Exception {
    try (DashboardServer s = server("1234")) {
      for (int i = 0; i < 5; i++) {
        assertEquals(401, relay(s, HOT_TUB, "0000", "{\"on\":false}").statusCode());
      }
      assertEquals(429, relay(s, HOT_TUB, "1234", "{\"on\":false}").statusCode(), "locked out even with the right code");
      assertTrue(commands.isEmpty());
    }
  }

  @Test
  @DisplayName("without a passcode configured, the dashboard is view-only")
  void viewOnly() throws Exception {
    try (DashboardServer s = server(null)) {
      assertFalse(MAPPER.readTree(get(s, "/api/state").body()).path("switching").asBoolean());
      assertEquals(403, relay(s, HOT_TUB, "anything", "{\"on\":false}").statusCode());
      assertTrue(commands.isEmpty());
    }
  }

  @Test
  @DisplayName("read-only, unknown and malformed requests are refused without switching")
  void refusals() throws Exception {
    try (DashboardServer s = server("1234")) {
      assertEquals(409, relay(s, FRIDGE, "1234", "{\"on\":false}").statusCode());
      assertEquals(404, relay(s, "nope", "1234", "{\"on\":false}").statusCode());
      assertEquals(400, relay(s, HOT_TUB, "1234", "{\"on\":\"off\"}").statusCode());
      assertTrue(commands.isEmpty());
    }
  }

  @Test
  @DisplayName("history says plainly when no Prometheus is configured")
  void noHistory() throws Exception {
    try (DashboardServer s = server(null)) {
      JsonNode h = MAPPER.readTree(get(s, "/api/history").body());
      assertFalse(h.path("available").asBoolean());
      assertTrue(h.path("reason").asText().contains("Prometheus"));
    }
  }

  @Test
  @DisplayName("only home-network addresses are allowed")
  void localOnly() throws Exception {
    for (String ok : new String[] {"127.0.0.1", "192.168.7.20", "10.0.0.5", "172.20.1.1", "169.254.1.1", "::1", "fe80::1", "fd12::1"}) {
      assertTrue(isLocal(ok), ok);
    }
    for (String no : new String[] {"8.8.8.8", "100.64.0.1", "2001:4860::8888"}) {
      assertFalse(isLocal(no), no);
    }
  }

  private static boolean isLocal(String address) throws Exception {
    var m = DashboardServer.class.getDeclaredMethod("isLocal", InetAddress.class);
    m.setAccessible(true);
    return (boolean) m.invoke(null, InetAddress.getByName(address));
  }

  // ------------------------------------------------------------ Prometheus

  @Test
  @DisplayName("a query_range matrix becomes fixed-length series keyed by circuit, panel and house")
  void parsesHistory() throws Exception {
    long start = 1_800_000_000L, end = start + 96 * 900L;
    String body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
        + "{\"metric\":{\"circuit_id\":\"" + HOT_TUB + "\",\"job\":\"span-home\"},\"values\":[[" + start + ",\"1200.04\"],[" + end + ",\"10\"]]},"
        + "{\"metric\":{\"panel_serial\":\"" + PANEL + "\"},\"values\":[[" + (start + 900) + ",\"1500\"]]},"
        + "{\"metric\":{},\"values\":[[" + (start + 1800) + ",\"NaN\"],[" + (start + 2700) + ",\"2000\"]]}]}}";
    var m = Class.forName("io.github.chelin1974.spanhome.web.PrometheusHistory")
        .getDeclaredMethod("parse", JsonNode.class, long.class, long.class);
    m.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = (Map<String, Object>) m.invoke(null, MAPPER.readTree(body), start, end);
    @SuppressWarnings("unchecked")
    Map<String, List<Double>> series = (Map<String, List<Double>>) parsed.get("series");

    List<Double> tub = series.get(HOT_TUB);
    assertEquals(97, tub.size());
    assertEquals(1200.0, tub.get(0), 1e-9);
    assertEquals(10.0, tub.get(96), 1e-9);
    assertNull(tub.get(1), "no sample is a gap, not zero");
    assertEquals(1500.0, series.get("panel:" + PANEL).get(1), 1e-9);
    assertNull(series.get("house").get(2), "NaN is dropped");
    assertEquals(2000.0, series.get("house").get(3), 1e-9);
  }

  @Test
  @DisplayName("a failed Prometheus query is an error, not empty graphs")
  void historyError() throws Exception {
    var m = Class.forName("io.github.chelin1974.spanhome.web.PrometheusHistory")
        .getDeclaredMethod("parse", JsonNode.class, long.class, long.class);
    m.setAccessible(true);
    var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> m.invoke(null, MAPPER.readTree("{\"status\":\"error\",\"error\":\"bad query\"}"), 0L, 0L));
    assertTrue(error.getCause().getMessage().contains("bad query"));
  }

  // ----------------------------------------------------------------- config

  @Test
  @DisplayName("prometheusUrl turns on export to its OTLP receiver; otlpEndpoint overrides it")
  void configUrls() throws Exception {
    Path dir = Files.createTempDirectory("span");
    Files.writeString(dir.resolve("c.json"), "{\"panels\":[{\"serial\":\"a\"}],\"prometheusUrl\":\"http://localhost:9090/\"}");
    SpanHomeConfig config = SpanHomeConfig.load(dir.resolve("c.json"));
    assertEquals("http://localhost:9090/api/v1/otlp/v1/metrics", config.otlpUri().orElseThrow().toString());
    assertEquals(8089, (int) config.dashboardPort);

    Files.writeString(dir.resolve("d.json"),
        "{\"panels\":[{\"serial\":\"a\"}],\"prometheusUrl\":\"http://p:9090\",\"otlpEndpoint\":\"http://collector:4318/v1/metrics\"}");
    assertEquals("http://collector:4318/v1/metrics", SpanHomeConfig.load(dir.resolve("d.json")).otlpUri().orElseThrow().toString());

    Files.writeString(dir.resolve("e.json"), "{\"panels\":[{\"serial\":\"a\"}]}");
    assertTrue(SpanHomeConfig.load(dir.resolve("e.json")).otlpUri().isEmpty(), "no backend, no export");

    Files.writeString(dir.resolve("f.json"), "{\"panels\":[{\"serial\":\"a\"}],\"prometheusUrl\":\"localhost:9090\"}");
    assertThrows(SpanHomeConfig.ConfigException.class, () -> SpanHomeConfig.load(dir.resolve("f.json")));
  }
}
