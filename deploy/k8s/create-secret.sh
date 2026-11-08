#!/usr/bin/env bash
#
# Creates (or updates) the Kubernetes Secret this bridge reads its configuration from,
# using the span-home.config.json you already have for running it locally - and, if you
# have one, the span-home.credentials.json that registering with the panels produced.
#
#   ./deploy/k8s/create-secret.sh
#   ./deploy/k8s/create-secret.sh --dry-run
#   ./deploy/k8s/create-secret.sh --bind-address 192.168.7.10
#   ./deploy/k8s/create-secret.sh --credentials ~/span-home.credentials.json
#
# Why the credentials matter here: registering with a panel is a one-off interactive step
# (`--register`, which wants three door presses or the app passphrase) and it cannot be
# done from inside a pod. So you register once outside the cluster and seed the result.
# The init container copies it in only if the volume has none, so once the bridge owns that
# file a stale Secret will not overwrite it.
#
# The script also strips the local path overrides. The bridge resolves everything it writes
# relative to its config file, and one of those files has no override setting at all, so in
# the cluster the config lives in /data with them - a path from your laptop would send some
# files somewhere that does not exist, or worse, somewhere ephemeral.
#
# Idempotent. The config is read once at startup, so follow an update with:
#
#   kubectl -n span-home rollout restart deploy/span-home
set -euo pipefail

CONFIG="span-home.config.json"
CREDENTIALS=""
NAMESPACE="span-home"
NAME="span-home-config"
BIND_ADDRESS=""
DRY_RUN=false

usage() {
  cat <<'USAGE'
usage: create-secret.sh [options]

  --config <path>         configuration file (default: span-home.config.json)
  --credentials <path>    panel credentials from `--register`
                          (default: span-home.credentials.json beside the config, if present)
  --namespace <name>      namespace to create the Secret in (default: span-home)
  --name <name>           Secret name (default: span-home-config)
  --bind-address <ip>     LAN IP to advertise HomeKit on; set this if the Home app cannot
                          find the bridge
  --dry-run               show what would be set, send nothing, and exit
  --help                  show this message
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)       CONFIG="${2:?--config needs a path}"; shift 2 ;;
    --credentials)  CREDENTIALS="${2:?--credentials needs a path}"; shift 2 ;;
    --namespace)    NAMESPACE="${2:?--namespace needs a name}"; shift 2 ;;
    --name)         NAME="${2:?--name needs a name}"; shift 2 ;;
    --bind-address) BIND_ADDRESS="${2:?--bind-address needs an IP}"; shift 2 ;;
    --dry-run)      DRY_RUN=true; shift ;;
    --help|-h)      usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v python3 >/dev/null 2>&1 || {
  echo "error: python3 is required to read $CONFIG" >&2
  exit 1
}
$DRY_RUN || command -v kubectl >/dev/null 2>&1 || {
  echo "error: kubectl is not on PATH" >&2
  exit 1
}
[[ -r "$CONFIG" ]] || {
  echo "error: cannot read $CONFIG" >&2
  echo "Copy span-home.config.example.json and fill in your panels, or pass --config." >&2
  exit 1
}

# Default the credentials to the file sitting beside the config, which is where
# `--register` puts it.
if [[ -z "$CREDENTIALS" ]]; then
  CANDIDATE="$(dirname "$CONFIG")/span-home.credentials.json"
  [[ -r "$CANDIDATE" ]] && CREDENTIALS="$CANDIDATE"
fi

OUT_FILE="$(mktemp "${TMPDIR:-/tmp}/span-config.XXXXXX")"
chmod 600 "$OUT_FILE"
trap 'rm -f "$OUT_FILE"' EXIT INT TERM

python3 - "$CONFIG" "$OUT_FILE" "$BIND_ADDRESS" <<'PYTHON'
import json, sys

config_path, out_path, bind_address = sys.argv[1], sys.argv[2], sys.argv[3]

try:
    with open(config_path, encoding="utf-8") as handle:
        config = json.load(handle)
except json.JSONDecodeError as error:
    sys.exit(f"error: {config_path} is not valid JSON: {error}")

if not isinstance(config, dict):
    sys.exit(f"error: {config_path} should contain a JSON object")

config = {key: value for key, value in config.items() if not key.startswith("_")}

panels = config.get("panels")
if not isinstance(panels, list) or not panels:
    sys.exit(f'error: "panels" must list at least one panel in {config_path}')
for panel in panels:
    if not isinstance(panel, dict) or not str(panel.get("serial") or "").strip():
        sys.exit(f'error: every entry in "panels" needs a "serial" in {config_path}')

notes = []

# The bridge puts everything it writes next to its config file, and the layout file has no
# override at all - so in the cluster the config lives on the volume with them, and any
# path from a laptop here would be wrong.
for key in ("storagePath", "credentialsPath", "accessoryIdsPath"):
    if key in config:
        was = config.pop(key)
        notes.append(f"dropped {key} ({was}): all state goes to /data in the cluster")

if bind_address:
    config["bindAddress"] = bind_address
    notes.append(f"bindAddress -> {bind_address}")
elif not str(config.get("bindAddress") or "").strip():
    notes.append("bindAddress left unset: the bridge picks an interface. Pass "
                 "--bind-address <node-ip> if the Home app cannot find it.")

prometheus = str(config.get("prometheusUrl") or "").strip()
if "localhost" in prometheus or "127.0.0.1" in prometheus:
    notes.append(f'prometheusUrl is {prometheus}: that is the NODE in a hostNetwork pod. '
                 "Fine if Prometheus runs there; otherwise point it at your cluster's.")

with open(out_path, "w", encoding="utf-8") as handle:
    json.dump(config, handle, indent=2)
    handle.write("\n")

print(f"settings read from {config_path}:")
for key in config:
    print(f"  {key}" + ("  (secret)" if key == "dashboardPasscode" else ""))
if notes:
    print("adjusted for the cluster:")
    for note in notes:
        print(f"  {note}")
PYTHON

KUBECTL_ARGS=(--from-file=span-home.config.json="$OUT_FILE")

if [[ -n "$CREDENTIALS" ]]; then
  [[ -r "$CREDENTIALS" ]] || { echo "error: cannot read $CREDENTIALS" >&2; exit 1; }
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$CREDENTIALS" \
    || { echo "error: $CREDENTIALS is not valid JSON" >&2; exit 1; }
  KUBECTL_ARGS+=(--from-file=span-home.credentials.json="$CREDENTIALS")
  echo "including panel credentials from $CREDENTIALS (seeded only if the volume has none)"
else
  echo
  echo "No span-home.credentials.json found, so the Secret carries the config only."
  echo "Register with your panels first, outside the cluster:"
  echo "    java -jar target/span-home.jar --register"
  echo "then re-run this script to seed the result."
fi

if $DRY_RUN; then
  echo
  echo "--dry-run: nothing was sent to the cluster."
  exit 0
fi

echo
# --dry-run=client plus apply, rather than a bare create, so re-running updates the Secret
# instead of failing with AlreadyExists. The YAML goes through a pipe, never the terminal.
kubectl create secret generic "$NAME" \
  --namespace "$NAMESPACE" \
  "${KUBECTL_ARGS[@]}" \
  --dry-run=client -o yaml \
  | kubectl apply -f -

echo
echo "The bridge reads this once at startup. If it is already running:"
echo "  kubectl -n $NAMESPACE rollout restart deploy/span-home"
