#!/bin/sh
# Offline substitutes for Docker/time/process signals. Never calls the host Docker daemon.
set -eu
source_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
mkdir "$root/bin" "$root/data"
export HEALTH_SCRIPT="$source_dir/docker/healthcheck.sh" STATE_DIR="$root/data"
export WATCH_CALLS="$root/calls" WATCH_SLEEPS="$root/sleeps" WATCH_INSPECTS="$root/inspects"
export STATE_FILE="$root/cooldown" LOCK_DIR="$root/lock" PAUSE_FILE="$root/paused"
export TEST_NOW=1793275200 TEST_MODE=running EXPECTED_RUNNING=1
export CHECK_INTERVAL_SECONDS=1 FAIL_THRESHOLD=3 RESTART_COOLDOWN_SECONDS=300
export CONTAINER_NAME=NativeTest
export TEST_ID=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
cat >"$root/bin/date" <<'SH'
#!/bin/sh
echo "$TEST_NOW"
SH
cat >"$root/bin/timeout" <<'SH'
#!/bin/sh
shift
exec "$@"
SH
cat >"$root/bin/logger" <<'SH'
#!/bin/sh
exit 0
SH
cat >"$root/bin/sleep" <<'SH'
#!/bin/sh
n="$(cat "$WATCH_SLEEPS" 2>/dev/null || echo 0)"
n=$((n+1)); echo "$n" >"$WATCH_SLEEPS"
[ "$n" -lt 4 ] || exit 7
SH
cat >"$root/bin/readlink" <<'SH'
#!/bin/sh
if [ "$TEST_MODE" = wrong-exe ]; then echo /usr/bin/other; else echo /app/esurfing-native; fi
SH
cat >"$root/bin/awk" <<'SH'
#!/bin/sh
if [ "${TEST_STAGE:-read}" = signal ] && [ "$TEST_MODE" = process-race ]; then echo 456; else echo 123; fi
SH
cat >"$root/bin/docker" <<'SH'
#!/bin/sh
[ "${1:-}" != --fixture-probe ] || exit 0
printf '%s\n' "$*" >>"$WATCH_CALLS"
case "$1" in
inspect)
  n="$(cat "$WATCH_INSPECTS" 2>/dev/null || echo 0)"; n=$((n+1)); echo "$n" >"$WATCH_INSPECTS"
  id="$TEST_ID"; running=true; paused=false; restarting=false; policy=unless-stopped; impl=cpp; started=2026-10-29T00:00:00Z; program=/app/esurfing-native
  case "$TEST_MODE" in
    stopped) running=false ;; missing) exit 1 ;; paused) paused=true ;; restarting) restarting=true ;;
    no-policy) policy=no ;; foreign) impl=kotlin ;; wrong-exe) program=/bin/sh ;;
    stop-race) [ "$n" -lt 4 ] || running=false ;;
    name-race) [ "$n" -lt 4 ] || id=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb ;;
    restart-race) [ "$n" -lt 4 ] || started=2026-10-29T01:00:00Z ;;
  esac
  echo "$id $running $paused $restarting $policy $impl $started $program" ;;
exec)
  [ "$2" = "$TEST_ID" ] || exit 99
  [ "$3" = sh ] && [ "$4" = -c ] || exit 99
  script="$5"; shift 5
  case "$script" in
    *'kill -TERM 1'*) export TEST_STAGE=signal ;;
    *) export TEST_STAGE=read ;;
  esac
  # Run the real guard expressions, but replace absolute health path and signal primitive.
  script="$(printf '%s\n' "$script" | sed 's|/app/healthcheck.sh|sh "$HEALTH_SCRIPT"|g')"
  if [ "$TEST_STAGE" = signal ] && [ "$TEST_MODE" = instance-race ]; then
    jq '.instanceId="replacement-owner"' "$STATE_DIR/health.json" >"$STATE_DIR/new.json"; mv "$STATE_DIR/new.json" "$STATE_DIR/health.json"
  fi
  sh -c 'kill() { printf "SIGNAL\n" >>"$WATCH_CALLS"; }; script=$1; shift; [ "$#" = 0 ] || shift; eval "$script"' native-fixture "$script" "$@" ;;
*) echo 'Unexpected Docker mutation' >&2; exit 99 ;;
esac
SH
chmod 755 "$root/bin/"*
# Refuse noexec temporary filesystems before modifying PATH; never fall through to real Docker.
"$root/bin/docker" --fixture-probe
export PATH="$root/bin:$PATH"
jq -n --argjson now "$TEST_NOW" '{schemaVersion:3,implementation:"cpp",processId:1,instanceId:"synthetic-owner",processAlive:true,lastUpdatedAt:$now,healthWriteIntervalSeconds:10,authenticated:false,networkOnline:true,phase:"external_online"}' >"$root/base.json"
checks=0
test_case() {
  label="$1"; mode="$2"; filter="$3"; expected="$4"
  rm -f "$WATCH_CALLS" "$WATCH_SLEEPS" "$WATCH_INSPECTS" "$STATE_FILE" "$PAUSE_FILE"
  jq "$filter" "$root/base.json" >"$STATE_DIR/health.json"
  export TEST_MODE="$mode" EXPECTED_RUNNING=1
  case "$label" in
    'manual pause') touch "$PAUSE_FILE" ;;
    'expected stopped') export EXPECTED_RUNNING=0 ;;
    'cooldown') echo $((TEST_NOW-10)) >"$STATE_FILE" ;;
    'clock rollback') echo $((TEST_NOW+300)) >"$STATE_FILE" ;;
  esac
  status=0
  sh "$source_dir/scripts/openwrt-watchdog.sh" || status="$?"
  [ "$status" = 0 ] || [ "$status" = 7 ] || { echo "watchdog failed: $label ($status)"; exit 1; }
  count="$(grep -c '^SIGNAL$' "$WATCH_CALLS" || true)"
  [ "$count" = "$expected" ] || { echo "watchdog wrong result: $label"; cat "$WATCH_CALLS"; exit 1; }
  ! grep -E '^(start |stop |restart |rm |exec --user)' "$WATCH_CALLS" || exit 1
  [ ! -e "$LOCK_DIR" ] || { echo 'lock leaked'; exit 1; }
  if [ "$label" = 'clock rollback' ]; then [ "$(cat "$STATE_FILE")" = "$TEST_NOW" ] || exit 1; fi
  checks=$((checks+1))
}
test_case 'external online no owned session' running '.' 0
test_case 'paused authentication stays healthy' running '.phase="paused"' 0
test_case 'WAN offline stays healthy' running '.phase="waiting_network" | .networkOnline=false' 0
test_case 'healthy owned session' running '.phase="online" | .authenticated=true' 0
test_case 'stale supported health' running '.lastUpdatedAt-=1000' 1
test_case 'future supported health' running '.lastUpdatedAt+=300' 1
test_case 'dead owner' running '.processAlive=false' 1
test_case 'unknown schema' running '.schemaVersion=4' 0
test_case 'old implementation' running '.schemaVersion=2 | .implementation="kotlin"' 0
for mode in stopped missing paused restarting no-policy foreign wrong-exe; do test_case "$mode" "$mode" '.lastUpdatedAt-=1000' 0; done
test_case 'manual pause' running '.lastUpdatedAt-=1000' 0
test_case 'expected stopped' running '.lastUpdatedAt-=1000' 0
test_case 'cooldown' running '.lastUpdatedAt-=1000' 0
test_case 'clock rollback' running '.lastUpdatedAt-=1000' 0
for mode in stop-race name-race restart-race process-race instance-race; do test_case "$mode" "$mode" '.lastUpdatedAt-=1000' 0; done
# The lock uses a live owner from the test shell; the second watchdog exits without Docker.
rm -f "$WATCH_CALLS"; mkdir "$LOCK_DIR"; echo $$ >"$LOCK_DIR/pid"
sh "$source_dir/scripts/openwrt-watchdog.sh"
[ ! -e "$WATCH_CALLS" ] || exit 1
rm "$LOCK_DIR/pid"; rmdir "$LOCK_DIR"; checks=$((checks+1))
echo "$checks native watchdog offline boundary cases passed; Docker and signals substituted"
