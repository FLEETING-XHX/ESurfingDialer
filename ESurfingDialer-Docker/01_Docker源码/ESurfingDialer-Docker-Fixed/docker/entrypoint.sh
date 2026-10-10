#!/bin/sh
set -eu

state_dir="${STATE_DIR:-/data}"
log_dir="${LOG_DIR:-$state_dir/logs}"
log_retention_days="${LOG_RETENTION_DAYS:-3}"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
LOG_DIR="$log_dir"
LOG_FILE_MAX_BYTES="${LOG_FILE_MAX_BYTES:-10485760}"
LOG_TOTAL_MAX_BYTES="${LOG_TOTAL_MAX_BYTES:-52428800}"
LOG_ROTATED_FILES="${LOG_ROTATED_FILES:-2}"
export LOG_DIR LOG_FILE_MAX_BYTES LOG_TOTAL_MAX_BYTES LOG_ROTATED_FILES
backend="${DIALER_BACKEND:-auto}"
timezone="${TZ:-Asia/Shanghai}"
log_timezone="$timezone"
arch="$(uname -m 2>/dev/null || echo unknown)"
dynarmic_arg=""

if [ "$timezone" = "Asia/Shanghai" ] && [ ! -e /usr/share/zoneinfo/Asia/Shanghai ]; then
  # POSIX TZ fallback keeps shell-generated log filenames on UTC+8 even in a
  # minimal image without the tzdata package. The JVM uses its bundled tzdb.
  log_timezone="CST-8"
fi

if [ -z "${DIALER_USER:-}" ]; then
  echo "ERROR: DIALER_USER is required" >&2
  exit 2
fi

if [ -z "${DIALER_PASSWORD:-}" ]; then
  echo "ERROR: DIALER_PASSWORD is required" >&2
  exit 2
fi

case "$backend" in
  auto)
    case "$arch" in
      aarch64|arm64)
        dynarmic_arg="-d"
        ;;
      *)
        dynarmic_arg=""
        ;;
    esac
    ;;
  dynarmic)
    dynarmic_arg="-d"
    ;;
  unicorn2|"")
    dynarmic_arg=""
    ;;
  *)
    echo "ERROR: DIALER_BACKEND must be auto, unicorn2, or dynarmic" >&2
    exit 2
    ;;
esac

mkdir -p "$state_dir" "$log_dir"
chown -R esurfing:esurfing "$state_dir" 2>/dev/null || true

cleanup_logs() {
  find "$log_dir" -type f \( -name 'dialer-[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9].log' -o -name 'dialer-[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9].log.[1-5]' \) -mtime +"$log_retention_days" -delete 2>/dev/null || true
}

cleanup_logs
sh "$script_dir/log-maintenance.sh" maintain "$(TZ="$log_timezone" date +%F)"
cleanup_loop() {
  sleep_pid=""
  trap '[ -z "$sleep_pid" ] || kill "$sleep_pid" 2>/dev/null || true; exit 0' INT TERM
  while :; do
    sleep 3600 &
    sleep_pid="$!"
    wait "$sleep_pid" || exit 0
    cleanup_logs
    sh "$script_dir/log-maintenance.sh" maintain "$(TZ="$log_timezone" date +%F)"
  done
}
cleanup_loop >/dev/null 2>&1 &
cleanup_pid="$!"

runtime_dir="$(mktemp -d /tmp/esurfing-dialer.XXXXXX)"
fifo="$runtime_dir/log"
logger_status="$runtime_dir/logger-status"
mkfifo "$fifo"
# Keep the writer open between the startup message and Java opening its stream.
exec 3<> "$fifo"

stop_child() {
  child_pid="$1"
  grace="$2"
  kill "$child_pid" 2>/dev/null || true
  elapsed=0
  while kill -0 "$child_pid" 2>/dev/null && [ "$elapsed" -lt "$grace" ]; do
    sleep 1
    elapsed=$((elapsed + 1))
  done
  kill -KILL "$child_pid" 2>/dev/null || true
  wait "$child_pid" 2>/dev/null || true
}

# 日志退出通知中断父进程的 wait；平时无需轮询或反复创建监督进程。
parent_pid="$$"
logger_failed=0
stop_requested=0
trap 'logger_failed=1' USR1
trap 'stop_requested=130' INT
trap 'stop_requested=143' TERM
run_logger() {
  set +e
  sink_stop=0
  trap 'sink_stop=1' INT TERM
  TZ="$log_timezone" LC_ALL=C awk -v helper="$script_dir/log-maintenance.sh" -f "$script_dir/log-sink.awk" < "$fifo" &
  sink_pid="$!"
  if [ "$sink_stop" -eq 0 ]; then
    wait "$sink_pid"
    sink_exit="$?"
  else
    sink_exit=143
  fi
  if [ "$sink_stop" -ne 0 ]; then
    trap '' INT TERM
    stop_child "$sink_pid" 2
    sink_exit=143
  fi
  printf '%s\n' "$sink_exit" > "$logger_status"
  kill -USR1 "$parent_pid" 2>/dev/null || true
  exit "$sink_exit"
}
run_logger 3>&- &
logger_pid="$!"

echo "BACKEND_SELECTED backend=${backend} arch=${arch} dynarmic=${dynarmic_arg:+true}" >&3

run_java() {
  exec java ${JAVA_OPTS:-} \
    -Duser.timezone="$timezone" \
    -jar /app/client.jar \
    "--user=${DIALER_USER}" \
    "--password=${DIALER_PASSWORD}" \
    ${dynarmic_arg}
}

if command -v su-exec >/dev/null 2>&1 && id esurfing >/dev/null 2>&1; then
  DYNARMIC_ARG="$dynarmic_arg" DIALER_TIMEZONE="$timezone" su-exec esurfing sh -c 'exec java ${JAVA_OPTS:-} -Duser.timezone="$DIALER_TIMEZONE" -jar /app/client.jar "--user=$DIALER_USER" "--password=$DIALER_PASSWORD" ${DYNARMIC_ARG:-}' > "$fifo" 2>&1 3>&- &
else
  DYNARMIC_ARG="$dynarmic_arg" run_java > "$fifo" 2>&1 3>&- &
fi
java_pid="$!"

set +e
if [ "$logger_failed" -eq 0 ] && [ "$stop_requested" -eq 0 ]; then
  wait "$java_pid"
  exit_code="$?"
else
  exit_code=1
fi
# 后续清理不可被重复信号打断；Java 有五秒宽限，随后强制结束。
trap '' INT TERM USR1
exec 3>&-
if [ "$stop_requested" -ne 0 ]; then
  exit_code="$stop_requested"
  stop_child "$java_pid" 5
elif [ "$logger_failed" -ne 0 ]; then
  echo "ERROR: log process exited; stopping Java" >&2
  exit_code=1
  stop_child "$java_pid" 5
fi

stop_child "$cleanup_pid" 2
# 正常结束先让日志读到 EOF；磁盘/助手挂起时也不能无限等待。
elapsed=0
while [ ! -f "$logger_status" ] && [ "$elapsed" -lt 5 ]; do
  sleep 1
  elapsed=$((elapsed + 1))
done
if [ ! -f "$logger_status" ]; then
  echo "ERROR: log process did not drain within five seconds" >&2
  stop_child "$logger_pid" 5
  [ "$exit_code" -ne 0 ] || exit_code=1
else
  wait "$logger_pid" 2>/dev/null || true
  read -r sink_exit < "$logger_status"
  if [ "$sink_exit" -ne 0 ]; then
    [ "$exit_code" -ne 0 ] || exit_code=1
  fi
fi
rm -f "$fifo" "$logger_status"
rmdir "$runtime_dir" 2>/dev/null || true

exit "$exit_code"
