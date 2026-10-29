#!/bin/sh
set -eu
binary="${1:-/app/esurfing-native}"
directory="$(mktemp -d)"
child=""
cleanup() {
  [ -z "$child" ] || kill -TERM "$child" 2>/dev/null || true
  [ -z "$child" ] || wait "$child" 2>/dev/null || true
  chmod -R u+rwX "$directory"
  rm -rf "$directory"
}
trap cleanup EXIT
trap 'exit 1' INT TERM
legacy="$directory/legacy"
native="$directory/native"
mkdir "$legacy" "$native"
printf '%s\n' '{"macAddress":"02:AA:BB:CC:DD:EE","clientId":"Synthetic-Rollback-ID"}' >"$legacy/device-state.json"
jq -n --argjson now "$(date +%s)" '{schemaVersion:1,estimatedLifetimeSeconds:3600,lastAuthSuccessAt:($now-7200),nextPlannedReauthAt:($now-3600),updatedAt:($now-3600),lastObservedLifetimeSeconds:3600,successfulAuthSamples:3}' >"$legacy/reauth-plan.json"
printf '%s\n' '{"schemaVersion":2,"authenticated":true,"fixture":"never copy old health"}' >"$legacy/health.json"
printf '%s\n' 'SYNTHETIC_SENTINEL=do-not-copy-env' >"$legacy/.env"
sha256sum "$legacy/device-state.json" "$legacy/reauth-plan.json" "$legacy/health.json" "$legacy/.env" >"$directory/legacy-before.sha256"
# The operation is a copy of two stable state files, never a shared active volume.
cp "$legacy/device-state.json" "$legacy/reauth-plan.json" "$native/"
chmod 400 "$legacy/device-state.json" "$legacy/reauth-plan.json" "$legacy/health.json" "$legacy/.env"
chmod 500 "$legacy"
NATIVE_EXPERIMENTAL=1 AUTO_REAUTH_ENABLED=1 DIALER_USER=synthetic DIALER_PASSWORD=synthetic \
STATE_DIR="$native" NETWORK_CHECK_URLS=http://192.0.2.1/probe HEALTH_WRITE_INTERVAL_SECONDS=1 \
  "$binary" >"$directory/native.log" 2>&1 &
child="$!"
attempt=0
while [ ! -s "$native/health.json" ] && [ "$attempt" -lt 50 ]; do sleep 0.1; attempt=$((attempt+1)); done
[ -s "$native/health.json" ] || { cat "$directory/native.log"; exit 1; }
jq -e '.macAddress=="02:aa:bb:cc:dd:ee" and .clientId=="synthetic-rollback-id"' "$native/device-state.json" >/dev/null
jq -e '.schemaVersion==2 and .successfulAuthSamples==0 and .lastObservedLifetimeSeconds==0' "$native/reauth-plan.json" >/dev/null
jq -e '.schemaVersion==3 and .authenticated==false and .implementation=="cpp" and .processAlive==true' "$native/health.json" >/dev/null
[ ! -e "$native/.env" ]
kill -TERM "$child"
wait "$child"
child=""
jq -e '.phase=="stopped" and .processAlive==false' "$native/health.json" >/dev/null
sha256sum -c "$directory/legacy-before.sha256" >/dev/null
echo 'synthetic state copy, native identity/plan upgrade, isolated health and byte-identical legacy fallback files passed; no Kotlin authentication or production rollback executed'
