# Deploying the SPAN bridge to Kubernetes

A complete walkthrough: register with your panels, build an image, get it onto the cluster,
deploy, pair with the Home app, and run it day to day. If you only want the short version,
jump to [The whole thing, in order](#the-whole-thing-in-order).

> **Read [Before you start](#before-you-start) first.** This bridge needs the local network
> for two separate reasons, and a normal pod has neither. That decides whether this can work
> on your cluster at all, and it is better to find out now than after three failed deploys.

---

## Before you start

### This needs a node on your home LAN

Two independent reasons, and both are hard requirements:

1. **HomeKit discovery and resolution are mDNS** — multicast to `224.0.0.251:5353` on the
   local link. A pod on a CNI overlay can neither send nor receive it, so the Home app would
   never find the bridge, and a bridge that was already paired would stop resolving and show
   every circuit as "No Response".
2. **The bridge talks MQTT directly to your SPAN panels** on the local network. There is no
   cloud in the middle to reach instead.

So the pod runs with `hostNetwork: true`, directly on a node's LAN interface. Three things
follow:

1. **A node must be on the same LAN/VLAN as your panels, your Apple TV and your HomePod.**
   mDNS does not cross subnets. A cloud cluster cannot host this at all.
2. **That node's ports 8089 and 51828 must be free.**
3. **The namespace must allow `hostNetwork`.** The `baseline` and `restricted` Pod Security
   Standards both forbid it; `base.yaml` labels the namespace `enforce: privileged` for this
   reason.

Check you have a suitable node:

```bash
kubectl get nodes -o wide
```

Look for a node whose `INTERNAL-IP` is on your home subnet (`192.168.x.x`, `10.x.x.x`), and
confirm it can reach a panel:

```bash
# from that node
ping -c1 <panel-ip>
```

If no node qualifies, stop here — the honest options are a Pi or mini PC joined to the
cluster, or running the bridge outside Kubernetes with `span-home.service`.

### What you need

- `kubectl` pointing at the cluster, and permission to create namespaces.
- A default `StorageClass` (`kubectl get storageclass`). On k3s that is `local-path`.
- A `span-home.config.json` listing your panels.
- **Panel credentials from `--register`** — see the next step; this one is easy to miss.
- Somewhere to publish a container image.

---

## Step 1 — register with your panels, outside the cluster

Registering is a one-off interactive step: the panel wants either three presses of its door
switch or the passphrase from the SPAN Home app. **You cannot do this from inside a pod**, so
do it once on a machine on your LAN and keep the result.

```bash
mvn package
java -jar target/span-home.jar --register                  # then press the door switch
java -jar target/span-home.jar --register --passphrase     # or use the app passphrase
```

That writes `span-home.credentials.json` beside your config. Treat it like a password file:
it is the bridge's standing authorisation to your electrical panel.

Step 3 loads it into the cluster, and the init container seeds it onto the volume **only if
the volume has none** — so once the bridge owns that file, re-applying a stale Secret cannot
clobber it.

## Step 2 — get an image onto the cluster

### Option A: let CI build it (recommended)

`.github/workflows/image.yml` builds `linux/amd64` and `linux/arm64` and pushes to
`ghcr.io/chelin1974/span-home` on every push to `main`, and on a `v*` tag.

```bash
git push origin main            # builds and pushes :latest
git tag v1.0.0 && git push origin v1.0.0   # builds and pushes :1.0.0
```

### Option B: build it yourself

Build for the architecture your **node** runs, not your laptop — a Raspberry Pi is `arm64`:

```bash
echo "$GITHUB_PAT" | docker login ghcr.io -u chelin1974 --password-stdin
docker buildx build --platform linux/arm64,linux/amd64 \
  -t ghcr.io/chelin1974/span-home:latest --push .
```

The PAT needs the `write:packages` scope.

### Then make the image pullable

**GitHub Container Registry packages are private by default**, and a private package fails
with `ImagePullBackOff` and `denied`. Pick one:

- **Make the package public** — Profile → Packages → `span-home` → Package settings →
  Change visibility → Public. The image contains nothing secret.
- **Or create a pull secret:**

  ```bash
  kubectl -n span-home create secret docker-registry ghcr-pull \
    --docker-server=ghcr.io \
    --docker-username=chelin1974 \
    --docker-password="$GITHUB_PAT"
  ```

  Then uncomment the `imagePullSecrets` block in `deployment.yaml`.

## Step 3 — label the node

The pod is pinned to the node on your LAN. Nothing schedules until this label exists:

```bash
kubectl label node <node-name> homekit=true
```

Use one node only. Two labelled nodes and a rescheduled pod means a bridge that moves house,
which breaks both pairing and the panel registration.

## Step 4 — create the namespace, config and credentials

```bash
kubectl apply -f deploy/k8s/base.yaml
./deploy/k8s/create-secret.sh                   # add --dry-run first if you like
```

The script reads your `span-home.config.json`, picks up `span-home.credentials.json` from
beside it automatically, and adjusts two things for the cluster:

- **It drops `storagePath`, `credentialsPath` and `accessoryIdsPath`.** The bridge resolves
  everything it writes relative to its config file, and the layout file has no override
  setting at all — so in the cluster the config lives on the volume with them. A path from
  your laptop would send some files somewhere that does not exist, or worse, somewhere
  ephemeral.
- **It warns if `prometheusUrl` points at localhost**, which in a `hostNetwork` pod means the
  node. Fine if Prometheus runs there; otherwise point it at your cluster's.

`--bind-address <node-ip>` sets the LAN address HomeKit is advertised on, which is worth doing
if the Home app cannot find the bridge.

## Step 5 — deploy

```bash
kubectl apply -k deploy/k8s
kubectl -n span-home rollout status deploy/span-home
```

If you do not run an ingress controller, drop `ingress.yaml` from `kustomization.yaml` first.

First boot is slower than a restart — the bridge connects to every panel before the dashboard
comes up — which is why the startup probe allows three minutes.

## Step 6 — pair with the Home app

The setup code is printed once at startup, and only while the bridge is unpaired:

```bash
kubectl -n span-home logs deploy/span-home | grep -A2 'setup code'
```

In the Home app: **Add Accessory → More options… → pick the bridge → enter the code**. Every
circuit, the panel power sensors and the battery arrive together.

## Step 7 — check it

```bash
curl -s http://<node-ip>:8089/healthz      # {"status":"ok","discoverable":true}
curl -s http://<node-ip>:8089/api/state    # circuits and power
open http://<node-ip>:8089/                # the dashboard
```

`discoverable: true` means the mDNS advertisement is alive, which is what HomeKit actually
depends on.

---

## The whole thing, in order

```bash
java -jar target/span-home.jar --register       # once, on your LAN, outside the cluster
kubectl get nodes -o wide                       # find a node on your home LAN
kubectl apply -f deploy/k8s/base.yaml
kubectl label node <node-name> homekit=true
./deploy/k8s/create-secret.sh                   # config + the credentials from --register
kubectl apply -k deploy/k8s
kubectl -n span-home rollout status deploy/span-home
kubectl -n span-home logs deploy/span-home | grep -A2 'setup code'
```

---

## Running it day to day

### Reaching the dashboard

With `hostNetwork` it is already on the node: `http://<node-ip>:8089`. You do not need a
Service or an Ingress for that; they exist for a stable in-cluster name
(`http://span-home.span-home.svc:8089`) and for a hostname via `ingress.yaml`.

| | |
| --- | --- |
| `GET /` | the dashboard |
| `GET /api/state` | circuits, power and battery as JSON |
| `GET /api/history` | power history, read through Prometheus |
| `GET /healthz` | 200, or 503 once the mDNS responder has died |

`dashboardPasscode` guards switching circuits, but the page and the power history are
readable by anyone who can reach the port. Keep it on your LAN and off any public ingress.

### Upgrading

```bash
kubectl -n span-home set image deploy/span-home \
  span-home=ghcr.io/chelin1974/span-home:1.1.0 \
  install-config=ghcr.io/chelin1974/span-home:1.1.0
kubectl -n span-home rollout status deploy/span-home
```

Note the init container uses the same image, so set both — or let kustomize's `images:` entry
do it for you, which rewrites every reference at once.

On `:latest` the tag does not change, so force a re-pull:

```bash
kubectl -n span-home rollout restart deploy/span-home
```

Expect a few seconds of downtime: the strategy is `Recreate`, not `RollingUpdate`, because two
pods would fight over the HomeKit port. Pairing and panel registration survive — both live on
the PVC.

### Changing configuration

```bash
$EDITOR span-home.config.json
./deploy/k8s/create-secret.sh                   # updates the Secret in place
kubectl -n span-home rollout restart deploy/span-home
```

The init container refreshes the config on the volume on every start, so the restart is what
applies it.

### Back up the volume

`span-home-state` holds four things you do not want to recreate:

| File | Losing it means |
| --- | --- |
| `span-home.state.json` | re-pairing in the Home app, re-doing rooms and automations |
| `span-home.credentials.json` | registering with every panel again, door switch and all |
| `span-home.accessories.json` | HomeKit sees new accessories, so automations break |
| `span-home.layout.json` | harmless; a cache of the configuration number |

```bash
kubectl -n span-home exec deploy/span-home -- sh -c 'cd /data && tar cf - *.json' > span-home-data.tar
```

Keep that somewhere private: it contains the HomeKit setup code and the panel token.

To restore, scale to zero, write the files back, and scale up:

```bash
kubectl -n span-home scale deploy/span-home --replicas=0
# restore the files on the PVC, then:
kubectl -n span-home scale deploy/span-home --replicas=1
```

### Logs

```bash
kubectl -n span-home logs -f deploy/span-home
kubectl -n span-home logs deploy/span-home -c install-config   # the config copy
kubectl -n span-home logs deploy/span-home --previous          # after a restart
```

---

## Metrics and dashboards

This bridge has its own metrics stack, in this repository: `prometheus.yml`, and Grafana
provisioning plus a dashboard under `grafana/`. It shares nothing with the DaikinHome or
CellGateHome bridges — separate config, separate storage, separate ports — so any one of them
runs with or without the others, and each Grafana shows exactly one bridge.

Under plain Docker:

```bash
docker compose up -d                       # Prometheus only
docker compose --profile grafana up -d     # Prometheus and Grafana
```

The ports are allocated so all three bridges can also run side by side on one host:

| Bridge | Prometheus | Grafana |
| --- | --- | --- |
| SpanHome | 9090 | 3000 |
| DaikinHome | 9091 | 3001 |
| CellGateHome | 9092 | 3002 |

The bridge **pushes** OTLP metrics rather than being scraped, which is why Prometheus runs
with `--web.enable-otlp-receiver`. The dashboard's power history also reads back through
Prometheus, so `prometheusUrl` is worth setting even if you never open Grafana.

In Kubernetes, point `prometheusUrl` at whatever Prometheus you already run; an in-cluster one
resolves by service name, because `dnsPolicy: ClusterFirstWithHostNet` keeps cluster DNS
working in a `hostNetwork` pod. There are no Prometheus or Grafana manifests in `deploy/k8s`
on purpose: most clusters already run one, and installing a second would be worse than
useless. The config files above are the source of truth either way.

---

## Why the manifests look like this

**`replicas: 1` and `strategy: Recreate`.** Two pods would both bind the HomeKit port, both
advertise the same bridge over mDNS, both write the same state files, and both open MQTT
sessions to the same panels. A `RollingUpdate` starts the new pod before the old exits, so it
would fail to bind the port and sit in `CrashLoopBackOff` while the old pod carried on — the
update would never complete.

**There is an init container.** The bridge resolves every file it writes relative to its config
file, and one of those four has no override setting at all, so pointing each at `/data`
individually is not possible — the config has to live in `/data` with them. A Secret mount is
read-only, so the config is copied onto the volume instead: refreshed on every start so an
edited Secret takes effect, while the panel credentials are seeded only if absent so the
bridge's own copy is never overwritten.

**`dnsPolicy: ClusterFirstWithHostNet`.** Without it a `hostNetwork` pod inherits the node's
`resolv.conf` and loses cluster DNS, so it could not resolve an in-cluster Prometheus by
service name.

**`/healthz` is answered before the home-network check.** The dashboard 403s anything that is
not on a private network, and a liveness probe that got a 403 would fail forever and restart
the container in a loop — worse than disclosing a status word. Kubernetes probes arrive from
the node, whose address is usually RFC1918 and would pass the check anyway, but a cluster on
CGNAT space (`100.64/10`) would not.

---

## When the mDNS responder dies

JmDNS can die under a running bridge — a network change on the node is enough — and when it
does it logs `Could not recover we are Down!` and never comes back. The bridge used to carry on
reading the panels, serving the dashboard and passing its liveness probe while being completely
invisible to Apple Home.

It no longer does. The bridge owns its JmDNS instance and registers for the library's
`cannotRecoverFromIOError` callback, and separately probes every 60 seconds that multicast to
`224.0.0.251` still works at all (three consecutive failures count as dead, so a DHCP renew
does not trigger anything). When either fires:

- `GET /healthz` turns **503** with the reason, which is what the liveness probe watches.
- The process logs the reason and exits 70, so the pod restarts immediately — a Kubernetes
  restart and `Restart=on-failure` under systemd both do the right thing.
- The dashboard itself keeps serving, so the reason stays readable right up to the restart.

Recovery is a restart, not an in-process repair: JmDNS does not come back, so rebuilding it
underneath a live HAP server would be a lot of machinery for a fault whose honest answer is
"start again".

```bash
kubectl -n span-home logs deploy/span-home --previous | grep -i "advertisement is dead"
```

---

## Running the other bridges on the same node

The sibling bridges are built the same way and are designed to share a node — their ports do
not collide:

| Bridge | HTTP | HomeKit | Namespace |
| --- | --- | --- | --- |
| DaikinHome | 8080 | 51826 | `daikin-home` |
| CellGateHome | 8090 | 51829 | `cellgate-home` |
| SpanHome | 8089 | 51828 | `span-home` |

Give them all the same `homekit=true` node label. Each is a separate bridge in the Home app
with its own setup code and its own pairing state.

---

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `Pod ... violates PodSecurity "restricted"` | The namespace does not allow `hostNetwork`. Apply `base.yaml`, which labels it `enforce: privileged`. |
| Pod `Pending`, `node(s) didn't match selector` | No node has `homekit=true`. |
| `ImagePullBackOff` with `denied` | The GHCR package is private. Make it public or add the pull secret (Step 2). |
| `ImagePullBackOff` with `no match for platform` | The image was not built for the node's architecture. Rebuild with `--platform linux/arm64`. |
| Init container fails on `cp` | The Secret has no `span-home.config.json` key. Re-run `create-secret.sh`. |
| Pod `Pending` on the volume | No default `StorageClass`, or a node-local volume bound to a different node. `kubectl -n span-home describe pvc span-home-state`. |
| Logs show a registration or auth failure against a panel | No `span-home.credentials.json` on the volume. Register outside the cluster (Step 1) and re-run `create-secret.sh`. |
| Panels unreachable | The node is not on the panels' network, or a firewall is in the way. Test from the node itself. |
| Bridge never appears in the Home app | mDNS is not reaching the phone. Confirm the node is on the same subnet, not a guest VLAN. |
| Circuits say "No Response", `/healthz` is 200 | A stale pairing state, or the node moved. Check the logs. |
| Circuits say "No Response", `/healthz` is 503 | The mDNS responder died. The pod should restart itself; see the section above. |
| Pairing or registration lost on every restart | The volume is not mounted, or the config is not the copy in `/data`. Check `args` on the container. |
| Dashboard history is empty | `prometheusUrl` is unset or points somewhere unreachable from the pod. |
| Dashboard 403s from your laptop | Your laptop is not on a private network as the bridge sees it — check for a VPN. |
