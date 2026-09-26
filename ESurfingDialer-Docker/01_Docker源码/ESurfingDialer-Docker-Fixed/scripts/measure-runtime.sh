#!/bin/sh
set -eu
container="${CONTAINER_NAME:-ESurfingDialer-Beta2}"
samples="${1:-10}"
interval="${INTERVAL_SECONDS:-60}"
case "$samples:$interval" in ''|*[!0-9:]*) exit 2 ;; esac
[ "${#samples}" -le 2 ] && [ "$samples" -ge 1 ] && [ "$samples" -le 60 ] || exit 2
[ "${#interval}" -le 4 ] && [ "$interval" -ge 1 ] && [ "$interval" -le 3600 ] || exit 2
command -v timeout >/dev/null 2>&1 || exit 2
printf '%s\n' 'epoch_seconds,cpu_percent,memory_usage,pids'
index=0
while [ "$index" -lt "$samples" ]; do
  stats="$(timeout 15 docker stats --no-stream --format '{{.CPUPerc}},{{.MemUsage}},{{.PIDs}}' "$container")"
  [ -n "$stats" ] || exit 1
  printf '%s,%s\n' "$(date +%s)" "$stats"
  index=$((index+1))
  [ "$index" -ge "$samples" ] || sleep "$interval"
done
