#!/bin/sh
# Opt-in supervisor for the native image; never starts/stops/restarts containers.
set -efu
CONTAINER_NAME="${CONTAINER_NAME:-ESurfingDialerNative}"
case "$CONTAINER_NAME" in ''|*[!a-zA-Z0-9_.-]*) exit 1 ;; esac
CHECK_INTERVAL_SECONDS="${CHECK_INTERVAL_SECONDS:-60}"
FAIL_THRESHOLD="${FAIL_THRESHOLD:-3}"
RESTART_COOLDOWN_SECONDS="${RESTART_COOLDOWN_SECONDS:-300}"
STATE_FILE="${STATE_FILE:-/tmp/esurfing-native-watchdog-${CONTAINER_NAME}.state}"
PAUSE_FILE="${PAUSE_FILE:-${STATE_FILE}.paused}"
LOCK_DIR="${LOCK_DIR:-${STATE_FILE}.lock}"
EXPECTED_RUNNING="${EXPECTED_RUNNING:-0}"
for value in "$CHECK_INTERVAL_SECONDS" "$FAIL_THRESHOLD" "$RESTART_COOLDOWN_SECONDS"; do
  case "$value" in ''|*[!0-9]*|0?*) exit 1 ;; esac
  [ "${#value}" -le 4 ] && [ "$value" -gt 0 ] || exit 1
done
case "$EXPECTED_RUNNING" in 0|1) ;; *) exit 1 ;; esac
command -v timeout >/dev/null 2>&1 || exit 1
mkdir -p "$(dirname -- "$STATE_FILE")"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  owner="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
  case "$owner" in ''|*[!0-9]*) exit 0 ;; esac
  kill -0 "$owner" 2>/dev/null && exit 0
  rm -f "$LOCK_DIR/pid"
  rmdir "$LOCK_DIR" 2>/dev/null || exit 0
  mkdir "$LOCK_DIR" 2>/dev/null || exit 0
fi
printf '%s\n' "$$" > "$LOCK_DIR/pid"
release_lock() { rm -f "$LOCK_DIR/pid"; rmdir "$LOCK_DIR" 2>/dev/null || true; }
trap release_lock EXIT
trap 'exit 0' INT TERM
container_state() {
  timeout 10 docker inspect -f '{{.Id}} {{.State.Running}} {{.State.Paused}} {{.State.Restarting}} {{.HostConfig.RestartPolicy.Name}} {{index .Config.Labels "org.esurfing.implementation"}} {{.State.StartedAt}} {{.Path}}' "$CONTAINER_NAME" 2>/dev/null || true
}
fail_count=0
owner_id=''
while :; do
  state="$(container_state)"
  set -- $state
  if [ "$EXPECTED_RUNNING" = 0 ] || [ -f "$PAUSE_FILE" ] || [ "$#" != 8 ]; then
    fail_count=0; sleep "$CHECK_INTERVAL_SECONDS"; continue
  fi
  id="$1"
  case "$id" in ''|*[!a-f0-9]*) fail_count=0; sleep "$CHECK_INTERVAL_SECONDS"; continue ;; esac
  if [ "${#id}" != 64 ] || [ "$2 $3 $4 $5 $6" != 'true false false unless-stopped cpp' ] || [ "$8" != /app/esurfing-native ]; then
    fail_count=0; sleep "$CHECK_INTERVAL_SECONDS"; continue
  fi
  if [ "$owner_id" != "$state" ]; then owner_id="$state"; fail_count=0; fi
  result=0
  # Bind all later actions to immutable container ID, PID1 start ticks, and instance ID.
  observation="$(timeout 10 docker exec "$id" sh -c '
    awk "{print \$22}" /proc/1/stat
    /app/healthcheck.sh --watchdog
  ' 2>/dev/null)" || result="$?"
  if [ "$result" = 0 ] || [ "$result" = 2 ]; then
    fail_count=0; sleep "$CHECK_INTERVAL_SECONDS"; continue
  fi
  set -- $observation
  start="${1:-}"
  instance="${2:--}"
  case "$start" in ''|*[!0-9]*) fail_count=0; sleep "$CHECK_INTERVAL_SECONDS"; continue ;; esac
  case "$instance" in ''|*[!A-Za-z0-9_-]*) fail_count=0; sleep "$CHECK_INTERVAL_SECONDS"; continue ;; esac
  [ "${#start}" -le 20 ] && [ "${#instance}" -le 128 ] || exit 1
  fail_count=$((fail_count + 1))
  if [ "$fail_count" -lt "$FAIL_THRESHOLD" ]; then sleep "$CHECK_INTERVAL_SECONDS"; continue; fi
  now="$(date +%s)"
  last="$(cat "$STATE_FILE" 2>/dev/null || echo 0)"
  case "$last" in ''|*[!0-9]*|0?*) last=0 ;; esac
  [ "${#last}" -le 12 ] || last=0
  if [ "$last" -gt "$now" ]; then
    printf '%s\n' "$now" > "${STATE_FILE}.$$"; mv -f "${STATE_FILE}.$$" "$STATE_FILE"; last="$now"
  fi
  if [ $((now - last)) -ge "$RESTART_COOLDOWN_SECONDS" ] && [ ! -f "$PAUSE_FILE" ] && [ "$(container_state)" = "$state" ]; then
    printf '%s\n' "$now" > "${STATE_FILE}.$$"; mv -f "${STATE_FILE}.$$" "$STATE_FILE"
    logger -t esurfing-native-watchdog 'native progress stale; requesting same-user process exit' || true
    # Default image UID owns PID1 even with cap_drop ALL. --user 0 cannot signal it.
    # A stop race makes exec fail; a replacement/restarted process fails identity checks.
    timeout 10 docker exec "$id" sh -c '
      [ "$(awk "{print \$22}" /proc/1/stat)" = "$1" ] || exit 2
      if [ "$2" != - ]; then
        [ "$(jq -er .instanceId "${STATE_DIR:-/data}/health.json")" = "$2" ] || exit 2
      fi
      kill -TERM 1
    ' native-watchdog "$start" "$instance" >/dev/null 2>&1 || true
  fi
  fail_count=0
  sleep "$CHECK_INTERVAL_SECONDS"
done
