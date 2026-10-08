#!/bin/sh
set -eu
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$script_dir/../docker/health-state.sh"
CONTAINER_NAME="${CONTAINER_NAME:-ESurfingDialer}"
case "$CONTAINER_NAME" in ''|*[!a-zA-Z0-9_.-]*) echo "Invalid container name" >&2; exit 1 ;; esac
CHECK_INTERVAL_SECONDS="${CHECK_INTERVAL_SECONDS:-60}"
FAIL_THRESHOLD="${FAIL_THRESHOLD:-3}"
HEALTH_MAX_AGE_SECONDS="${HEALTH_MAX_AGE_SECONDS:-180}"
RESTART_COOLDOWN_SECONDS="${RESTART_COOLDOWN_SECONDS:-300}"
STATE_FILE="${STATE_FILE:-/tmp/esurfing-watchdog-${CONTAINER_NAME}.state}"
PAUSE_FILE="${PAUSE_FILE:-${STATE_FILE}.paused}"
LOCK_DIR="${LOCK_DIR:-${STATE_FILE}.lock}"
EXPECTED_RUNNING="${EXPECTED_RUNNING:-1}"
for value in "$CHECK_INTERVAL_SECONDS" "$FAIL_THRESHOLD" "$HEALTH_MAX_AGE_SECONDS" "$RESTART_COOLDOWN_SECONDS"; do
  case "$value" in ''|*[!0-9]*) exit 1 ;; esac
  [ "${#value}" -le 4 ] && [ "$value" -gt 0 ] || exit 1
  case "$value" in 0?*) exit 1 ;; esac
done
case "$EXPECTED_RUNNING" in 0|1) ;; *) exit 1 ;; esac
command -v timeout >/dev/null 2>&1 || { echo "watchdog requires timeout" >&2; exit 1; }
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
  timeout 10 docker inspect -f '{{.State.Running}} {{.State.Paused}} {{.State.Restarting}} {{.HostConfig.RestartPolicy.Name}}' "$CONTAINER_NAME" 2>/dev/null || true
}
container_health_ok() {
  health_json="$(timeout 10 docker exec "$CONTAINER_NAME" sh -c 'cat "${STATE_DIR:-/data}/health.json"' 2>/dev/null || true)"
  [ -n "$health_json" ] && [ "${#health_json}" -le 32768 ] || return 1
  health_now="$(date +%s)"
  health_max_age="$HEALTH_MAX_AGE_SECONDS"
  health_liveness
}

fail_count=0
while true; do
  # Never start stopped/missing/paused/restarting containers. Docker owns restart policy.
  state="$(container_state)"
  if [ "$EXPECTED_RUNNING" = 0 ] || [ -f "$PAUSE_FILE" ] || [ "$state" != 'true false false unless-stopped' ]; then
    fail_count=0
    sleep "$CHECK_INTERVAL_SECONDS"
    continue
  fi
  result=0
  container_health_ok || result="$?"
  if [ "$result" = 0 ] || [ "$result" = 2 ]; then
    # Unknown schemas are not evidence of a hung process; don't recycle old clients.
    fail_count=0
    sleep "$CHECK_INTERVAL_SECONDS"
    continue
  fi
  fail_count=$((fail_count + 1))
  if [ "$fail_count" -lt "$FAIL_THRESHOLD" ]; then sleep "$CHECK_INTERVAL_SECONDS"; continue; fi
  now="$(date +%s)"
  last_restart="$(cat "$STATE_FILE" 2>/dev/null || echo 0)"
  case "$last_restart" in ''|*[!0-9]*) last_restart=0 ;; esac
  [ "${#last_restart}" -le 12 ] || last_restart=0
  case "$last_restart" in 0?*) last_restart=0 ;; esac
  if [ "$last_restart" -gt "$now" ]; then
    # Clock rollback: rebase the cooldown instead of repeated recycling.
    printf '%s\n' "$now" > "${STATE_FILE}.$$"
    mv -f "${STATE_FILE}.$$" "$STATE_FILE"
    last_restart="$now"
  fi
  if [ $((now - last_restart)) -ge "$RESTART_COOLDOWN_SECONDS" ] && [ ! -f "$PAUSE_FILE" ] && [ "$(container_state)" = 'true false false unless-stopped' ]; then
    printf '%s\n' "$now" > "${STATE_FILE}.$$"
    mv -f "${STATE_FILE}.$$" "$STATE_FILE"
    logger -t esurfing-watchdog "dialer progress stale, requesting process exit in $CONTAINER_NAME" || true
    # exec cannot start a stopped container, even if docker stop races this check.
    # PID 1 handles TERM; unless-stopped recovers an unexpected in-container exit.
    timeout 10 docker exec --user 0 "$CONTAINER_NAME" sh -c 'kill -TERM 1' >/dev/null 2>&1 || true
  fi
  fail_count=0
  sleep "$CHECK_INTERVAL_SECONDS"
done
