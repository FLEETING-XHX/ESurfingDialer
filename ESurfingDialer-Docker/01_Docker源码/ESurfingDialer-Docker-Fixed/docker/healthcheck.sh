#!/bin/sh
set -eu

state_dir="${STATE_DIR:-/data}"
file="$state_dir/health.json"
max_age="${HEALTH_MAX_AGE_SECONDS:-120}"
auth_max_age="${HEALTH_AUTH_MAX_AGE_SECONDS:-900}"

[ -s "$file" ] || exit 1

now="$(date +%s)"
updated="$(sed -n 's/.*"lastUpdatedAt"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$file" | head -n 1)"

[ -n "$updated" ] || exit 1

age=$((now - updated))
[ "$age" -le "$max_age" ] || exit 1

grep -q '"clientThreadAlive"[[:space:]]*:[[:space:]]*true' "$file" || exit 1
grep -q '"networkCheckThreadAlive"[[:space:]]*:[[:space:]]*true' "$file" || exit 1
grep -q '"authenticated"[[:space:]]*:[[:space:]]*true' "$file" || exit 1

last_login="$(sed -n 's/.*"lastLoginSuccessAt"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$file" | head -n 1)"
last_heartbeat="$(sed -n 's/.*"lastHeartbeatSuccessAt"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$file" | head -n 1)"
last_auth="$last_heartbeat"
[ -n "$last_auth" ] && [ "$last_auth" -gt 0 ] || last_auth="$last_login"
[ -n "$last_auth" ] && [ "$last_auth" -gt 0 ] || exit 1

auth_age=$((now - last_auth))
[ "$auth_age" -le "$auth_max_age" ] || exit 1

exit 0
