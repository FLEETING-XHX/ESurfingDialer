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

fifo="/tmp/esurfing-dialer-log.$$"
rm -f "$fifo"
mkfifo "$fifo"
# Keep the writer open between the startup message and Java opening its stream.
exec 3<> "$fifo"

TZ="$log_timezone" LC_ALL=C awk -v helper="$script_dir/log-maintenance.sh" -f "$script_dir/log-sink.awk" < "$fifo" 3>&- &
logger_pid="$!"

echo "BACKEND_SELECTED backend=${backend} arch=${arch} dynarmic=${dynarmic_arg:+true}" >&3

run_java() {
  java ${JAVA_OPTS:-} \
    -Duser.timezone="$timezone" \
    -jar /app/client.jar \
    -u "${DIALER_USER}" \
    -p "${DIALER_PASSWORD}" \
    ${dynarmic_arg}
}

if command -v su-exec >/dev/null 2>&1 && id esurfing >/dev/null 2>&1; then
  DYNARMIC_ARG="$dynarmic_arg" DIALER_TIMEZONE="$timezone" su-exec esurfing sh -c 'java ${JAVA_OPTS:-} -Duser.timezone="$DIALER_TIMEZONE" -jar /app/client.jar -u "$DIALER_USER" -p "$DIALER_PASSWORD" ${DYNARMIC_ARG:-}' > "$fifo" 2>&1 3>&- &
else
  DYNARMIC_ARG="$dynarmic_arg" run_java > "$fifo" 2>&1 3>&- &
fi
java_pid="$!"

terminate() {
  exec 3>&-
  kill "$java_pid" 2>/dev/null || true
  wait "$java_pid" 2>/dev/null || true
  kill "$cleanup_pid" "$logger_pid" 2>/dev/null || true
  rm -f "$fifo"
}

trap terminate INT TERM

set +e
wait "$java_pid"
exit_code="$?"
exec 3>&-

kill "$cleanup_pid" 2>/dev/null || true
wait "$logger_pid" 2>/dev/null || true
rm -f "$fifo"

exit "$exit_code"
