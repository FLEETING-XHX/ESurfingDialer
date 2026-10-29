#!/bin/sh
set -eu
log_dir="${LOG_DIR:-${STATE_DIR:-/data}/logs}"
file_limit="${LOG_FILE_MAX_BYTES:-10485760}"
total_limit="${LOG_TOTAL_MAX_BYTES:-52428800}"
rotated="${LOG_ROTATED_FILES:-2}"
for value in "$file_limit" "$total_limit" "$rotated"; do
  case "$value" in ''|*[!0-9]*) exit 2 ;; esac
  [ "${#value}" -le 10 ] || exit 2
  case "$value" in 0?*) exit 2 ;; esac
done
[ "$file_limit" -ge 1024 ] && [ "$file_limit" -le 104857600 ] || exit 2
[ "$total_limit" -ge "$file_limit" ] && [ "$total_limit" -le 1073741824 ] || exit 2
[ "$rotated" -ge 1 ] && [ "$rotated" -le 5 ] || exit 2
action="${1:-maintain}"
day="${2:-$(date +%F)}"
case "$day" in [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;; *) exit 2 ;; esac
mkdir -p "$log_dir"
current="$log_dir/dialer-$day.log"
[ ! -L "$current" ] || exit 2
owned_log() {
  [ -f "$1" ] && [ ! -L "$1" ] || return 1
  case "$(basename -- "$1")" in
    dialer-[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9].log|dialer-[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9].log.[1-5]) return 0 ;;
    *) return 1 ;;
  esac
}
maintain() {
  for file in "$log_dir"/dialer-*.log*; do
    owned_log "$file" || continue
    if [ "$(wc -c < "$file")" -gt "$file_limit" ]; then
      temporary="$(mktemp "$log_dir/.log-trim.XXXXXX")"
      if tail -c "$file_limit" "$file" > "$temporary"; then mv -f "$temporary" "$file"; else rm -f "$temporary"; return 1; fi
    fi
  done
  # Reserve one full active file so appending cannot overshoot the total quota.
  others=0
  for file in "$log_dir"/dialer-*.log*; do
    owned_log "$file" || continue
    [ "$file" = "$current" ] || others=$((others + $(wc -c < "$file")))
  done
  budget=$((total_limit - file_limit))
  if [ "$others" -gt "$budget" ]; then
    ls -1tr "$log_dir"/dialer-*.log* 2>/dev/null | while IFS= read -r file; do
      owned_log "$file" || continue
      [ "$file" != "$current" ] || continue
      [ "$others" -gt "$budget" ] || break
      size="$(wc -c < "$file")"
      rm -f "$file"
      others=$((others - size))
    done
  fi
}
case "$action" in
  size) [ ! -f "$current" ] && echo 0 || wc -c < "$current" ;;
  rotate)
    [ ! -L "$current.$rotated" ] || exit 2
    rm -f "$current.$rotated"
    index="$rotated"
    while [ "$index" -gt 1 ]; do
      previous=$((index - 1))
      [ ! -L "$current.$previous" ] || exit 2
      [ ! -f "$current.$previous" ] || mv -f "$current.$previous" "$current.$index"
      index="$previous"
    done
    [ ! -f "$current" ] || mv -f "$current" "$current.1"
    : > "$current"
    maintain ;;
  maintain) maintain ;;
  *) exit 2 ;;
esac
