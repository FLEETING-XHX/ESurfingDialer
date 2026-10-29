#!/bin/sh
# Shared schema-2 health validation. health_json and health_now belong to the caller.
health_number() {
  values="$(printf '%s\n' "$health_json" | sed -n 's/^[[:space:]]*"'"$1"'"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\)[[:space:]]*[,}]*[[:space:]]*$/\1/p')"
  [ -n "$values" ] || return 1
  case "$values" in *[!0-9]*) return 1 ;; esac
  [ "${#values}" -le 12 ] || return 1
  case "$values" in 0?*) return 1 ;; esac
  [ "$values" -le 253402300799 ] || return 1
  printf '%s\n' "$values"
}

health_true() {
  [ "$(printf '%s\n' "$health_json" | grep -c '^[[:space:]]*"'"$1"'"[[:space:]]*:[[:space:]]*true[[:space:]]*[,}]*[[:space:]]*$')" = 1 ]
}

health_past() { [ "$1" -ge 0 ] && [ "$1" -le "$health_now" ]; }
health_recent() { health_past "$1" && [ "$1" -gt 0 ] && [ $((health_now - $1)) -le "$2" ]; }

health_liveness() {
  first="$(printf '%s\n' "$health_json" | sed '/^[[:space:]]*$/d' | head -n 1 | tr -d '[:space:]')"
  last="$(printf '%s\n' "$health_json" | sed '/^[[:space:]]*$/d' | tail -n 1 | tr -d '[:space:]')"
  [ "$first" = '{' ] && [ "$last" = '}' ] || return 1
  schema="$(health_number schemaVersion)" || return 2
  [ "$schema" = 2 ] || return 2
  health_true processAlive && health_true clientThreadAlive && health_true networkCheckThreadAlive || return 1
  updated="$(health_number lastUpdatedAt)" || return 1
  completed="$(health_number lastNetworkCheckCompletedAt)" || return 1
  progress="$(health_number lastClientProgressAt)" || return 1
  deadline="$(health_number clientStageDeadlineAt)" || return 1
  stage_timeout="$(health_number clientStageTimeoutSeconds)" || return 1
  interval="$(health_number networkCheckIntervalSeconds)" || return 1
  probe_budget="$(health_number networkProbeBudgetSeconds)" || return 1
  write_interval="$(health_number healthWriteIntervalSeconds)" || return 1
  [ "$stage_timeout" -ge 5 ] && [ "$stage_timeout" -le 600 ] || return 1
  [ "$interval" -ge 1 ] && [ "$interval" -le 300 ] || return 1
  [ "$probe_budget" -ge 1 ] && [ "$probe_budget" -le 180 ] || return 1
  [ "$write_interval" -ge 1 ] && [ "$write_interval" -le 300 ] || return 1
  report_age="$health_max_age"
  [ "$report_age" -ge $((write_interval * 3)) ] || report_age=$((write_interval * 3))
  probe_age="$health_max_age"
  [ "$probe_age" -ge $((interval + probe_budget + 15)) ] || probe_age=$((interval + probe_budget + 15))
  health_recent "$updated" "$report_age" && health_recent "$completed" "$probe_age" || return 1
  health_past "$progress" && [ "$progress" -gt 0 ] || return 1
  [ "$deadline" -eq $((progress + stage_timeout)) ] && [ "$health_now" -le "$deadline" ] || return 1
  for field in lastNetworkCheckAt lastNetworkCheckSuccessAt lastLoginSuccessAt lastHeartbeatSuccessAt lastPortalReauthRequestAt; do
    timestamp="$(health_number "$field")" || return 1
    health_past "$timestamp" || return 1
  done
}
