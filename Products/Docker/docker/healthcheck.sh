#!/bin/sh
set -eu
[ ! -e "${CORE_STATE_CONFLICT_FILE:-/tmp/esurfing-core-state-conflict}" ] || exit 1
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$script_dir/health-state.sh"
file="${STATE_DIR:-/data}/health.json"
[ -s "$file" ] || exit 1
[ "$(wc -c < "$file")" -le 32768 ] || exit 1
health_json="$(cat "$file")"
health_now="$(date +%s)"
health_max_age="${HEALTH_MAX_AGE_SECONDS:-120}"
auth_max_age="${HEALTH_AUTH_MAX_AGE_SECONDS:-900}"
case "$health_max_age:$auth_max_age" in *[!0-9:]*) exit 1 ;; esac
[ "${#health_max_age}" -le 4 ] && [ "${#auth_max_age}" -le 5 ] || exit 1
[ "$health_max_age" -gt 0 ] && [ "$auth_max_age" -gt 0 ] || exit 1
health_liveness || exit 1
# Network availability is separate from ownership of an authenticated session.
# Only an explicit, fresh unowned-online state is usable without authentication.
if health_true networkReachable &&
   [ "$(printf '%s\n' "$health_json" | grep -c '^[[:space:]]*"authenticated"[[:space:]]*:[[:space:]]*false[[:space:]]*[,}]*[[:space:]]*$')" = 1 ] &&
   [ "$(printf '%s\n' "$health_json" | grep -c '^[[:space:]]*"connectionState"[[:space:]]*:[[:space:]]*"network-online-unowned"[[:space:]]*[,}]*[[:space:]]*$')" = 1 ]; then
  successful_probe="$(health_number lastNetworkCheckSuccessAt)" || exit 1
  health_recent "$successful_probe" "$probe_age" || exit 1
  exit 0
fi
health_true authenticated || exit 1
last_login="$(health_number lastLoginSuccessAt)" || exit 1
last_heartbeat="$(health_number lastHeartbeatSuccessAt)" || exit 1
last_auth="$last_heartbeat"
[ "$last_auth" -ge "$last_login" ] || last_auth="$last_login"
health_recent "$last_auth" "$auth_max_age" || exit 1
