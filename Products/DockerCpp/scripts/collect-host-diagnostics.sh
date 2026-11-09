#!/bin/sh
# Optional, read-only router snapshot. No account/config/body/socket mounts.
set -eu
container="${CONTAINER_NAME:-ESurfingDialerNative-v3.0.0}"
case "$container" in ''|-*|*[!A-Za-z0-9_.-]*) echo 'HOST_DIAGNOSTIC INVALID_CONTAINER_NAME'; exit 2 ;; esac
printf 'HOST_DIAGNOSTIC utc=%s cause=unknown\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if command -v docker >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1; then
 if snapshot="$(timeout 10 docker inspect -f '{{.State.Status}} {{.State.ExitCode}} {{.State.OOMKilled}} {{.RestartCount}}' "$container" 2>/dev/null)"; then
  set -- $snapshot
  if [ "$#" = 4 ]; then
   case "$1" in created|running|paused|restarting|removing|exited|dead) status="$1" ;; *) status=unknown ;; esac
   case "$2" in ''|*[!0-9]*) code=unknown ;; *) code="$2" ;; esac
   case "$3" in true|false) oom="$3" ;; *) oom=unknown ;; esac
   case "$4" in ''|*[!0-9]*) count=unknown ;; *) count="$4" ;; esac
   printf 'CONTAINER status=%s exitCode=%s oomKilled=%s restartCount=%s\n' "$status" "$code" "$oom" "$count"
   if [ "$oom" = true ]; then echo '容器故障：Docker报告内存不足终止（OOM）。';
   elif [ "$code" = 137 ]; then echo '容器故障：退出码137，可能被强制终止，不能仅凭此认定内存不足。';
   elif [ "$code" != unknown ] && [ "$code" != 0 ]; then echo '容器存在非零退出码，请结合核心日志检查运行或配置错误。'; fi
  else echo 'CONTAINER unavailable'; fi
  if timeout 10 docker exec "$container" /app/healthcheck.sh >/dev/null 2>&1; then
   echo 'HEALTH fresh=true'
  else echo 'HEALTH fresh=unknown checkFailed=true'; fi
 else echo 'CONTAINER unavailable'; fi
else echo 'CONTAINER unavailable dockerOrTimeoutMissing=true'; fi

net="${HOST_SYS_NET:-/sys/class/net}"
found=0
for interface in "$net"/*; do
 [ -d "$interface" ] || continue
 name="${interface##*/}"
 case "$name" in ''|*[!A-Za-z0-9_.:-]*) continue ;; esac
 state="$(cat "$interface/operstate" 2>/dev/null || true)"
 case "$state" in up|down|unknown|dormant|notpresent|lowerlayerdown|testing) ;; *) state=unknown ;; esac
 carrier="$(cat "$interface/carrier" 2>/dev/null || true)"
 case "$carrier" in 0|1) ;; *) carrier=unknown ;; esac
 printf 'LINK interface=%s operstate=%s carrier=%s role=unknown\n' "$name" "$state" "$carrier"
 found=1
done
[ "$found" = 1 ] || echo 'LINK unavailable'
if command -v logread >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1; then
 if wireless_lines="$(timeout 5 logread -l 200 2>/dev/null)"; then
  wireless="$(printf '%s\n' "$wireless_lines" | awk '/deauth|disassoc/ {n++} END {print n+0}')"
  case "$wireless" in ''|*[!0-9]*) echo 'WIRELESS unavailable' ;; *) printf 'WIRELESS deauthOrDisassocMatchesInLast200Lines=%s attribution=unknown\n' "$wireless" ;; esac
 else echo 'WIRELESS unavailable'; fi
else echo 'WIRELESS unavailable'; fi
echo 'NOTE snapshotOnly=true interfaceRolesNotInferred=true rawWirelessLogsExcluded=true'
