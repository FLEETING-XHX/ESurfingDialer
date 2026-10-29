#!/bin/sh
set -eu
file="${STATE_DIR:-/data}/health.json"
[ -f "$file" ] && [ "$(wc -c < "$file")" -le 32768 ] || exit 1
json="$(cat "$file")"
# Reject duplicate members before jq's ordinary object parser can overwrite them.
printf '%s\n' "$json" | jq --stream -s -e '
  [.[] | select(length == 2) | .[0]] | length == (unique | length)
' >/dev/null 2>&1 || exit 1
if [ "${1:-}" = --watchdog ]; then
  # Unknown/other implementations are not proof of a hung native process.
  printf '%s\n' "$json" | jq -s -e 'length == 1 and (.[0] | .schemaVersion == 3 and .implementation == "cpp")' >/dev/null 2>&1 || exit 2
  printf '%s\n' "$json" | jq -er '.instanceId | select(type == "string" and length > 0 and length <= 128 and test("^[A-Za-z0-9_-]+$"))' || exit 1
fi
# Liveness is separate from owned authentication. external_online/paused are real states,
# not evidence that a process needs restarting. Consumers can read authenticated explicitly.
printf '%s\n' "$json" | jq -s -e --argjson now "$(date +%s)" '
  length == 1 and (.[0] |
  .schemaVersion == 3 and .implementation == "cpp" and .processAlive == true
  and (.processId | type == "number" and . >= 1 and . == floor)
  and (.instanceId | type == "string" and length > 0 and length <= 128 and test("^[A-Za-z0-9_-]+$"))
  and (.lastUpdatedAt | type == "number" and . == floor and . > 0 and . <= 253402300799)
  and (.healthWriteIntervalSeconds | type == "number" and . == floor and . >= 1 and . <= 300)
  and .lastUpdatedAt <= $now and ($now - .lastUpdatedAt) <= ([120, (.healthWriteIntervalSeconds * 3)] | max)
  and (.authenticated | type == "boolean")
  and (.networkOnline | type == "boolean")
  and (.phase as $phase | ["waiting_network","external_online","authorizing","confirming","online","backoff","paused"] | index($phase) != null)
  and (.authenticated == false or .phase == "online")
  )
' >/dev/null
