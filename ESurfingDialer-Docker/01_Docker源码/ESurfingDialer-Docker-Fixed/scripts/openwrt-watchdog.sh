#!/bin/sh
set -eu

CONTAINER_NAME="${CONTAINER_NAME:-ESurfingDialer}"
CHECK_INTERVAL_SECONDS="${CHECK_INTERVAL_SECONDS:-60}"
FAIL_THRESHOLD="${FAIL_THRESHOLD:-3}"
HEALTH_MAX_AGE_SECONDS="${HEALTH_MAX_AGE_SECONDS:-180}"
RESTART_COOLDOWN_SECONDS="${RESTART_COOLDOWN_SECONDS:-300}"
STATE_FILE="${STATE_FILE:-/tmp/esurfing-watchdog.state}"

fail_count=0

container_health_ok() {
  running="$(docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null || true)"
  [ "$running" = "true" ] || return 1

  health="$(docker exec "$CONTAINER_NAME" sh -c 'cat "${STATE_DIR:-/data}/health.json"' 2>/dev/null || true)"
  [ -n "$health" ] || return 1

  now="$(date +%s)"
  updated="$(printf '%s\n' "$health" | sed -n 's/.*"lastUpdatedAt"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -n 1)"
  network_checked="$(printf '%s\n' "$health" | sed -n 's/.*"lastNetworkCheckAt"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -n 1)"
  [ -n "$updated" ] || return 1
  [ -n "$network_checked" ] || return 1
  [ $((now - updated)) -le "$HEALTH_MAX_AGE_SECONDS" ] || return 1
  [ $((now - network_checked)) -le "$HEALTH_MAX_AGE_SECONDS" ] || return 1
  printf '%s\n' "$health" | grep -q '"clientThreadAlive"[[:space:]]*:[[:space:]]*true' || return 1
  printf '%s\n' "$health" | grep -q '"networkCheckThreadAlive"[[:space:]]*:[[:space:]]*true' || return 1
}

while true; do
  # Do not probe a public URL here.  A captive-portal redirect is expected
  # during campus re-authentication and must be handled by the dialer itself.
  # The old URL-based watchdog restarted the container after three redirects,
  # interrupting recovery and producing the observed "Shutting down..." log.
  if container_health_ok; then
    fail_count=0
    sleep "$CHECK_INTERVAL_SECONDS"
    continue
  fi

  fail_count=$((fail_count + 1))
  if [ "$fail_count" -lt "$FAIL_THRESHOLD" ]; then
    sleep "$CHECK_INTERVAL_SECONDS"
    continue
  fi

  now="$(date +%s)"
  last_restart="$(cat "$STATE_FILE" 2>/dev/null || echo 0)"
  if [ $((now - last_restart)) -ge "$RESTART_COOLDOWN_SECONDS" ]; then
    echo "$now" > "$STATE_FILE"
    logger -t esurfing-watchdog "dialer health stale or thread dead, restarting $CONTAINER_NAME"
    docker restart "$CONTAINER_NAME" >/dev/null 2>&1 || true
  else
    logger -t esurfing-watchdog "dialer unhealthy, restart cooldown active"
  fi

  fail_count=0
  sleep "$CHECK_INTERVAL_SECONDS"
done
