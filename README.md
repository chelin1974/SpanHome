# SpanHome

Puts SPAN smart panels into Apple Home: every circuit as an outlet you can see and (if you
allow it) switch, live power readings, and battery backup with a grid-outage sensor.

```
SPAN Panel  →  local SPAN API (MQTT over TLS)  →  this bridge  →  Apple Home / Siri
```

No cloud. The bridge talks to each panel on your home network through
[SPAN API](https://github.com/spanio/SPAN-API-Client-Docs), the official local interface SPAN
released in 2026, and speaks the HomeKit Accessory Protocol directly via
[HAP-Java](https://github.com/hap-java/HAP-Java). Changes show up in seconds, not minutes.
Several panels share one bridge.

## What shows up in Home

| In Home | What it is | From the panel |
| --- | --- | --- |
| An **outlet** per circuit | On = relay closed (powered). "In use" = drawing ≥ 5 W. | `switch/relay`, `meter/active-power` |
| **Watts** per circuit, in the Home app | A "<circuit> Power" tile next to each switch, reading in "lux": 1,450 W shows as 1450 lux (see below) | `meter/active-power` |
| **Panel and house power** | A "Main Power" and "Second Power" sensor per panel, plus "House Power" for all panels together, also in "lux" | sum of circuit draw |
| **Watts, kWh, volts, amps**, properly labelled | In the free **Eve** app | `meter/*`, panel voltage |
| A **"Grid"** contact sensor per panel with a battery | *Closed* on grid, *open* during an outage, so you can build "when the power goes out" automations | MID `grid/islanding-state` |
| **Battery level** on that accessory | Charge %, charging state, low-battery alert below 20 % | BESS `soc/soc`, `power-flows/battery` |

### Why watts show as "lux"

Apple Home has no characteristic for power, so it cannot show watts anywhere. The common
workaround, used here, is a light sensor whose reading is the wattage: Home shows the number
with a "lux" label. It also works in automations ("when Pool Power rises above 1000").

Each circuit's reading is its own accessory, "<circuit> Power", so it gets a tile of its own
next to the circuit's switch. (A reading attached to the switch's accessory would be folded into
the switch's tile.) New power tiles land in your default room; move them once. Turn all of this
off with `"showWattsInHome": false` and `"showPanelPower": false`, and use the Eve app instead.
With both on, a bridge carries about two accessories per circuit, well inside HomeKit's limit
of 149.

### Switching circuits

Every circuit the panel allows switching can be switched from Home. Turning one off cuts its
power, so list any that Home must never touch in `readOnlyCircuits`:

```json
"readOnlyCircuits": ["Furnace", "Main: Sub Zero"]
```

Those still show their state and power, and snap back if tapped. To go the other way and allow
only a few, set `switchCircuits` to just those, e.g. `["Pool", "Barn"]`.

Match by circuit name, `"Panel: Circuit"` for one panel's circuit, or the circuit's ID;
`switchCircuits` also takes `"*"` (the default). Circuits the panel itself won't switch (the
commissioned solar and battery circuits, for instance) are always read-only.

## Requirements

- **SPAN firmware with SPAN API.** Built against `spanos3/r202633` (the parent/child data
  model). The SPAN API beta started on the MAIN 32. `--list` prints each panel's model and
  firmware.
- **Java 21 or newer** to run the bridge. Maven to build, or Docker (see below).
- **Docker** (optional) for Prometheus, Grafana and the build. The bridge itself runs outside
  Docker: on a Mac, Docker Desktop's VM doesn't pass the Bonjour traffic HomeKit and the
  panels' `.local` names depend on.
- **A machine that stays on**, on the same network as the panels and your Home hub (Apple TV
  or HomePod). HomeKit finds the bridge over Bonjour, so no guest Wi-Fi or VLAN between them.

## Setup

**1. Build.**

```bash
mvn package
```

Or without Maven installed, in Docker:

```bash
docker compose run --rm build
```

**2. Configure.** Copy the example and set your panel serials (shown in the SPAN Home app):

```bash
cp span-home.config.example.json span-home.config.json
```

Each panel is reached at `span-<serial>.local`. If `.local` names don't resolve on your
network, add `"host": "192.168.x.x"` to that panel. Give panels a static DHCP lease: the panel
re-issues its TLS certificate whenever its IP changes.

**3. Register with each panel.** This pairs the bridge with the panel's SPAN API and saves
the broker credentials to `span-home.credentials.json` (owner-only).

```bash
java -jar target/span-home.jar --register
```

For each panel, it waits while you **open the panel door and press the door switch twice
more** (opening it counts as the first press); the breaker LEDs flash to confirm. Or skip the
door with the passphrase from the SPAN Home app; you'll be prompted for it:

```bash
java -jar target/span-home.jar --register --passphrase
```

Use `--panel nj-2410-00h35` to register just one.

**4. Look before you switch anything on.**

```bash
java -jar target/span-home.jar --list
```

```
Panel Main (nj-2410-00h2v) MAIN_32, firmware spanos3/r202633/04, 121 V
  Spaces  Circuit                         Watts   Relay  In Home as
  3       Kitchen Fridge                    142  CLOSED  read-only (panel will not switch it)
  17,19   Hot Tub                          1451  CLOSED  SWITCHABLE
  Battery: 76%, discharging, on grid, ~10h12m backup
```

Use the names exactly as listed in `readOnlyCircuits`, `switchCircuits` and `hiddenCircuits`.

**5. Run and pair.**

```bash
java -jar target/span-home.jar
```

The log prints the address the bridge is advertised on and a setup code. In the Home app:
**+ → Add Accessory → More options… → SPAN**, enter the code, and accept the "uncertified
accessory" warning.

For a permanent install, `span-home.service` is a systemd unit: copy it to
`/etc/systemd/system/`, adjust the paths, and `systemctl enable --now span-home`.

### Run at login on a Mac (launchd)

To keep the bridge running in the background, starting at login and restarting if it crashes,
install it as a launchd agent. Stop any copy running in a terminal first: two can't share the
HomeKit port.

**1. Create the agent.** From the repo folder, after `mvn package`, paste this whole block.
It records where your Java is and writes the agent definition:

```bash
JAVA="$(which java)"
DIR="$(pwd)"
cat > ~/Library/LaunchAgents/io.github.chelin1974.spanhome.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>io.github.chelin1974.spanhome</string>
  <key>ProgramArguments</key>
  <array>
    <string>$JAVA</string>
    <string>-jar</string>
    <string>$DIR/target/span-home.jar</string>
    <string>--config</string>
    <string>$DIR/span-home.config.json</string>
  </array>
  <key>WorkingDirectory</key><string>$DIR</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HOME/Library/Logs/span-home.log</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/span-home.log</string>
</dict>
</plist>
EOF
```

**2. Start it.**

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/io.github.chelin1974.spanhome.plist
```

**3. Pair, if you haven't yet.** The setup code is in the log:

```bash
tail -f ~/Library/Logs/span-home.log
```

Press Ctrl-C to stop watching; the agent keeps running.

| To | Run |
| --- | --- |
| Restart (after a config change or `mvn package`) | `launchctl kickstart -k gui/$(id -u)/io.github.chelin1974.spanhome` |
| Stop and remove | `launchctl bootout gui/$(id -u)/io.github.chelin1974.spanhome` |
| Check it's running | `launchctl print gui/$(id -u)/io.github.chelin1974.spanhome \| grep state` |
| Read the log | `tail -50 ~/Library/Logs/span-home.log` |

The agent runs only while you're logged in and the Mac is awake. A sleeping laptop, lid
closed included, takes the accessories to "No Response" until it wakes. For an always-on
setup, use a Mac mini or a Linux box with the systemd unit above.

## Breaker Board dashboard

The bridge also serves a dashboard on your home network: live watts for every breaker, panel
and house totals, the battery, a 24-hour graph behind each breaker, and switching.

- **All circuits** shows every circuit from both panels in one list, sorted by **usage**
  (biggest draw first), **name**, or **room**. Rooms come from `rooms` in the config: the Home
  app doesn't share its rooms with accessories, so the bridge can't read them from there.
- **As wired** draws each panel the way it is wired.
- **Tap a circuit** for its details: the last 24 hours against its 30-day average for each time
  of day, its peaks, average draw, hours running, energy used, and the hours it's busiest.
  These need Prometheus (see below). **Tap its switch** to turn it on or off.

Open `http://<this-machine>.local:8089` (the exact address is in the startup log) from any phone
or computer on your Wi-Fi. In Safari on iPhone, **Share → Add to Home Screen** makes it open like
an app.

- **Switching** needs the household passcode you set as `dashboardPasscode`. Each device asks
  once and remembers it. Without a passcode the dashboard is view-only. Five wrong passcodes lock
  that device out for five minutes. Switching goes through the same path as HomeKit, so
  `readOnlyCircuits` and the panel's own locks apply, and the Home app updates too.
- **Turning a circuit off takes two taps**, because it cuts the power at the breaker.
- **Home network only.** The dashboard answers private, link-local and loopback addresses and
  refuses everything else, even if a router forwards the port by mistake. It has no HTTPS: use
  it on networks you trust.
- `"dashboardPort": 0` turns it off.

## History with Prometheus (OpenTelemetry)

The bridge doesn't keep history itself. It publishes its readings as OpenTelemetry metrics over
OTLP/HTTP, and Prometheus 3 stores them. The dashboard's 24-hour graphs read them back with
PromQL, and you can use the same data in Grafana, alerts, or anything else that speaks PromQL.

**1. Run Prometheus 3 with its OTLP receiver.** From the repo folder, with Docker:

```bash
docker compose up -d                      # Prometheus on http://localhost:9090
docker compose --profile grafana up -d    # also Grafana on http://localhost:3000 (admin / admin)
```

`docker-compose.yml` runs Prometheus 3.5 with the OTLP receiver on, 400 days of retention, and
`prometheus.yml` from this repo (no scraping, OTLP settings, an out-of-order window). Data lives
in a Docker volume, so it survives restarts and upgrades; containers come back on their own
after a reboot once Docker Desktop starts. Prometheus listens on this machine only.

**Grafana** comes with Prometheus as its data source and a **SPAN Home** dashboard
(`grafana/dashboards/span-home.json`): house, panel, battery and grid right now; house and panel
power over time; the circuits you pick, stacked; what's drawing now; the biggest energy users and
kWh per day; today against the same time a week ago; battery charge and power, panel voltage and
grid outages. Pick panels and circuits at the top, and the time range at the top right. A
**Daikin Home** dashboard (`grafana/dashboards/daikin-home.json`) shows the thermostats from
[DaikinHome](https://github.com/chelin1974/DaikinHome) when it sends to the same Prometheus:
temperatures against setpoints, humidity, what the equipment is doing, heating and cooling hours,
and the HVAC circuits' power from SPAN. Energy
comes from each circuit's energy counter where the panel reports one, and otherwise from its
watts. Changes you save in Grafana last until this repo's copy of the dashboard changes; to keep one
for good, export the dashboard's JSON over `grafana/dashboards/span-home.json`.

`docker compose down` stops them and keeps the data; `docker compose down -v` deletes it.
Without Docker, `brew install prometheus` and run it with the same flags as the compose file.

**2. Point the bridge at it** in `span-home.config.json`, and restart the bridge:

```json
"prometheusUrl": "http://localhost:9090"
```

The bridge then pushes every 15 seconds to `<prometheusUrl>/api/v1/otlp/v1/metrics`. To send
somewhere else, such as an OpenTelemetry Collector, set `otlpEndpoint` to its OTLP/HTTP metrics
URL; the dashboard still reads history from `prometheusUrl`. If Prometheus is down, the bridge
logs it once and keeps working, and the graphs show a gap for that time.

**What Prometheus receives.** OTLP names are translated: dots become underscores and the unit
becomes a suffix.

| In Prometheus | What it is | Labels |
| --- | --- | --- |
| `span_circuit_power_watts` | Watts the circuit is drawing | `panel`, `circuit`, `circuit_id`, `circuit_spaces` |
| `span_circuit_relay` | 1 = on, 0 = off | same |
| `span_circuit_energy_joules_total` | Lifetime energy drawn, in joules (counter; ÷ 3,600,000 for kWh) | same |
| `span_panel_power_watts` | Sum of the panel's circuits | `panel`, `panel_serial` |
| `span_panel_voltage_volts` | Average of the two legs | `panel`, `panel_serial` |
| `span_house_power_watts` | Sum of every panel | |
| `span_battery_charge_percent` | Battery state of charge | `panel` |
| `span_battery_power_watts` | Battery power, + while charging | `panel` |
| `span_grid_connected` | 1 on grid, 0 during an outage | `panel` |

Some queries to start with:

```promql
topk(5, avg_over_time(span_circuit_power_watts[1h]))      # biggest loads, last hour
sum(increase(span_circuit_energy_joules_total[24h])) / 3.6e6     # kWh used in the last day
min_over_time(span_grid_connected[7d]) == 0               # any outage this week
```

## Configuration

| Setting | Default | Notes |
| --- | --- | --- |
| `panels[].serial` | — | Required. |
| `panels[].host` | `span-<serial>.local` | An IP works too. |
| `panels[].name` | the serial | Short label, used for `"Panel: Circuit"` matching and to tell same-named circuits apart. |
| `switchCircuits` | `["*"]` (all) | Circuits Home may switch. See above. |
| `readOnlyCircuits` | none | Circuits Home must never switch. Overrides `switchCircuits`. No `"*"`. |
| `hiddenCircuits` | none | Circuits left out of Home. No `"*"` here. |
| `combinedCircuits` | none | Circuits that feed one load, shown and switched as one: `{"Garage": ["Second: Garage", "Second: Garage 2"]}`. See below. |
| `rooms` | none | Room name to its circuits, for the dashboard's room sort, e.g. `{"Kitchen": ["Oven", "Dishwasher"]}`. Matched like the lists above. |

**Combined circuits.** A 240 V load wired through two single-pole breakers shows up as two
circuits, because that's how the panel reports it. `combinedCircuits` makes it one: one outlet
and one Power tile in Home, one breaker on the dashboard. Its watts are the sum of its legs, it's
on while any leg is on, and switching it switches every leg. If a leg can't be switched, the
bridge switches the others back, so the load is never left on one leg. It's switchable only if
every leg is. In Home it keeps the first leg's accessory, with its room and scenes; the other
legs' accessories go away. Prometheus still records each leg on its own.

In all of these lists a name ending in `*` matches every circuit whose name starts with the rest,
which helps with long names: `"Master bed*"`, or `"Second: Lights*"` for one panel only.
| `showBattery` | `true` | Publish the Grid/battery accessory when a battery is commissioned. |
| `showWattsInHome` | `true` | A "<circuit> Power" tile for each circuit, so Home shows its draw. |
| `showPanelPower` | `true` | Per-panel and whole-house power sensors. |
| `bridgeName` | `SPAN` | Shown while pairing. |
| `port` | `51828` | HomeKit port. Different from DaikinHome's, so both can run on one machine. |
| `bindAddress` | chosen automatically | LAN IP to advertise on. Set it if the Home app can't find the bridge. |
| `dashboardPort` | `8089` | Breaker Board dashboard port. `0` turns it off. |
| `dashboardPasscode` | none | Household passcode for switching from the dashboard. Without it the dashboard is view-only. |
| `prometheusUrl` | none | Prometheus 3 base URL. Turns on metrics export and the dashboard's 24 h graphs. |
| `otlpEndpoint` | `<prometheusUrl>/api/v1/otlp/v1/metrics` | Where OTLP metrics go, if not straight to Prometheus. |
| `metricsIntervalSeconds` | `15` | Seconds between metric pushes. |
| `storagePath`, `credentialsPath`, `accessoryIdsPath` | next to the config | See below. |

## Files to keep

| File | Holds | If lost |
| --- | --- | --- |
| `span-home.state.json` | HomeKit pairing | Every accessory goes "No Response"; remove and re-add the bridge. |
| `span-home.accessories.json` | A stable HomeKit ID per circuit | Rooms, names and automations are reset. |
| `span-home.credentials.json` | Each panel's broker password and CA | Run `--register` again. |
| `span-home.layout.json` | HomeKit's configuration number, raised when accessories change so iOS reloads them | Nothing: it is rebuilt on the next start. |

All of these are in `.gitignore`. The credentials file controls every breaker in the house:
keep it owner-only.

## How it works

- **Discovery.** The panel publishes a Homie 5 device tree: the panel, then each circuit,
  lugs and battery as children, and the battery's MID as a grandchild. The bridge subscribes to
  `ebus/5/#`, waits until every device has described itself, then publishes accessories.
  Devices are identified by their `type`, never by parsing IDs.
- **Security.** At registration the bridge downloads the panel's self-signed CA and pins it.
  The passphrase is sent over HTTPS verified against that CA, and every MQTT connection is TLS
  with hostname verification against it. A different device answering on the panel's address
  is refused.
- **Switching** publishes `OPEN`/`CLOSED` to `ebus/5/<circuit>/switch/relay/set`. Home shows the
  new state at once; the panel's own report then takes over.
- **Sign conventions.** A circuit's `active-power` is negative while it consumes, so the bridge
  shows `-active-power` as consumption, and a circuit backfeeding solar shows 0 W. Charging
  state comes from the panel's `power-flows/battery` (positive = charging), because the BESS
  `meter/active-power` sign flipped in firmware r202639.
- **Reconnects** are automatic after a network drop or panel reboot.

## Known gaps

- **Watts in Home are labelled "lux".** See above; Eve labels them properly. Eve shows live
  values only: its history graphs need Eve's proprietary history service, which this bridge
  doesn't implement.
- **The house total assumes neither panel feeds the other through a breaker.** It sums every
  circuit's draw, which is exact when the second panel is fed from the first's feed-through lugs
  or from the meter. If a breaker on one panel feeds the other, that panel's load counts twice
  in "House Power".
- **Battery has no Home tile of its own.** The level appears in the Grid accessory's details,
  and low-battery alerts work. Eve shows the level too.
- **New circuits need a restart.** Accessories are built at startup.
- **SPAN Drive and load-shed priorities** aren't exposed, though the API allows both.
- **SPAN API is a beta** and community-supported. SPAN's notes on each firmware release are in
  the [changelog](https://github.com/spanio/SPAN-API-Client-Docs/blob/main/CHANGELOG.md);
  breaking changes have happened (r202633, r202639).

## Development

```bash
mvn test
```

| Path | What it does |
| --- | --- |
| `span/HomieTree` | Pure model of one panel's device tree, fed MQTT messages. **Start here.** |
| `span/SpanReadings` | What the properties mean: signs, units, battery and grid state |
| `span/SpanPanelConnection` | TLS MQTT client per panel |
| `span/SpanRegistration` | `--register`: CA download and `auth/register` |
| `homekit/CircuitOutlet` | A circuit as an outlet, with Eve power characteristics |
| `homekit/BackupBattery` | Grid contact sensor and battery service |
| `metrics/OtlpMetrics` | Readings to an OTLP metrics request (pure) |
| `metrics/OtlpExporter` | Pushes OTLP/HTTP JSON on a timer, on its own thread |
| `web/DashboardServer` | The dashboard page, its API, passcode and home-network checks |
| `web/PrometheusHistory` | 24 h of 15-minute averages back from Prometheus |
| `src/main/resources/dashboard/index.html` | The Breaker Board page |
| `SpanHomeBridge` | Startup, discovery, wiring panels to accessories |

## License

MIT
