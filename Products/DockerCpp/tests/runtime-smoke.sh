#!/bin/sh
set -eu
binary="${1:-/src/build/esurfing-native}"
test_dir="$(mktemp -d)"
owned_pid=""
cleanup() {
  [ -z "$owned_pid" ] || kill -TERM "$owned_pid" 2>/dev/null || true
  [ -z "$owned_pid" ] || wait "$owned_pid" 2>/dev/null || true
  rm -rf "$test_dir"
}
trap cleanup EXIT
trap 'exit 1' INT TERM
set +e
"$binary" >"$test_dir/gate.log" 2>&1
gate_code="$?"
set -e
[ "$gate_code" = 78 ] || { echo "experimental gate failed"; exit 1; }
# Must run the test container with --network none. TEST-NET-1 is not a campus target.
mkdir "$test_dir/state"
printf '%s\n' '{"macAddress":"02:11:22:33:44:55","clientId":"Synthetic-Legacy-ID"}' >"$test_dir/state/device-state.json"
NATIVE_EXPERIMENTAL=1 AUTO_REAUTH_ENABLED=1 DIALER_USER=synthetic DIALER_PASSWORD=synthetic \
STATE_DIR="$test_dir/state" NETWORK_CHECK_URLS=http://192.0.2.1/probe \
  "$binary" >"$test_dir/run.log" 2>&1 &
owned_pid="$!"
attempt=0
while [ ! -s "$test_dir/state/health.json" ] && [ "$attempt" -lt 50 ]; do sleep 0.1; attempt=$((attempt+1)); done
[ -s "$test_dir/state/health.json" ] || { cat "$test_dir/run.log"; exit 1; }
grep -q '"authenticated": false' "$test_dir/state/health.json"
grep -q '"autoReauthEnabled": true' "$test_dir/state/health.json"
grep -q '"clientId": "synthetic-legacy-id"' "$test_dir/state/device-state.json"
grep -q '"macAddress": "02:11:22:33:44:55"' "$test_dir/state/device-state.json"
if [ -x /app/healthcheck.sh ]; then STATE_DIR="$test_dir/state" /app/healthcheck.sh; fi
# A separate real process must fail to take the same state directory.
set +e
NATIVE_EXPERIMENTAL=1 AUTO_REAUTH_ENABLED=0 DIALER_USER=synthetic DIALER_PASSWORD=synthetic STATE_DIR="$test_dir/state" "$binary" >"$test_dir/second.log" 2>&1
second_code="$?"
set -e
[ "$second_code" = 1 ] || exit 1
kill -TERM "$owned_pid"
wait "$owned_pid"
owned_pid=""
grep -q '"processAlive": false' "$test_dir/state/health.json"
grep -q '"phase": "stopped"' "$test_dir/state/health.json"
archive="$test_dir/state/logs/dialer-$(date +%F).log"
[ -f "$archive" ] && grep -q NATIVE_STOPPED "$archive" || { echo 'current-TZ file log did not drain'; exit 1; }
grep -q NATIVE_STOPPED "$test_dir/run.log"
if [ -x /app/healthcheck.sh ]; then
  if STATE_DIR="$test_dir/state" /app/healthcheck.sh; then echo "stopped health accepted"; exit 1; fi
fi
echo "runtime gate, legacy identity, enabled planner, cross-process ownership and SIGTERM cleanup passed; network disabled"
