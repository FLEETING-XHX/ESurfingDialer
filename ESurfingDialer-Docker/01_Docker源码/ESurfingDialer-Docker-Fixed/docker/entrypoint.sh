#!/bin/sh
set -eu

state_dir="${STATE_DIR:-/data}"
log_dir="${LOG_DIR:-$state_dir/logs}"
log_retention_days="${LOG_RETENTION_DAYS:-3}"
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
  find "$log_dir" -type f -name 'dialer-*.log' -mtime +"$log_retention_days" -delete 2>/dev/null || true
}

cleanup_logs
(
  while :; do
    sleep 3600
    cleanup_logs
  done
) &
cleanup_pid="$!"

fifo="/tmp/esurfing-dialer-log.$$"
rm -f "$fifo"
mkfifo "$fifo"

(
  while IFS= read -r line; do
    log_file="$log_dir/dialer-$(TZ="$log_timezone" date +%F).log"
    printf '%s\n' "$line" | tee -a "$log_file"
  done < "$fifo"
) &
logger_pid="$!"

echo "BACKEND_SELECTED backend=${backend} arch=${arch} dynarmic=${dynarmic_arg:+true}" > "$fifo"

run_java() {
  java ${JAVA_OPTS:-} \
    -Duser.timezone="$timezone" \
    -jar /app/client.jar \
    -u "${DIALER_USER}" \
    -p "${DIALER_PASSWORD}" \
    ${dynarmic_arg}
}

if command -v su-exec >/dev/null 2>&1 && id esurfing >/dev/null 2>&1; then
  DYNARMIC_ARG="$dynarmic_arg" DIALER_TIMEZONE="$timezone" su-exec esurfing sh -c 'java ${JAVA_OPTS:-} -Duser.timezone="$DIALER_TIMEZONE" -jar /app/client.jar -u "$DIALER_USER" -p "$DIALER_PASSWORD" ${DYNARMIC_ARG:-}' > "$fifo" 2>&1 &
else
  DYNARMIC_ARG="$dynarmic_arg" run_java > "$fifo" 2>&1 &
fi
java_pid="$!"

terminate() {
  kill "$java_pid" 2>/dev/null || true
  wait "$java_pid" 2>/dev/null || true
  kill "$cleanup_pid" "$logger_pid" 2>/dev/null || true
  rm -f "$fifo"
}

trap terminate INT TERM

set +e
wait "$java_pid"
exit_code="$?"

kill "$cleanup_pid" 2>/dev/null || true
wait "$logger_pid" 2>/dev/null || true
rm -f "$fifo"

exit "$exit_code"
