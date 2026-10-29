#!/bin/sh
set -eu
script="${1:-/app/healthcheck.sh}"
directory="$(mktemp -d)"
trap 'chmod 600 "$directory/health.json" 2>/dev/null || true; rm -rf "$directory"' EXIT
trap 'exit 1' INT TERM
export STATE_DIR="$directory"
now="$(date +%s)"
jq -n --argjson now "$now" '{schemaVersion:3,implementation:"cpp",processId:123,instanceId:"synthetic-owner",processAlive:true,lastUpdatedAt:$now,healthWriteIntervalSeconds:10,authenticated:false,networkOnline:true,phase:"external_online"}' >"$directory/base.json"
passed=0
for phase in waiting_network external_online authorizing confirming online backoff paused; do
  jq --argjson now "$(date +%s)" --arg phase "$phase" '.lastUpdatedAt=$now | .phase=$phase | .authenticated=($phase=="online")' "$directory/base.json" >"$directory/health.json"
  sh "$script"
  passed=$((passed+1))
done
for filter in '.schemaVersion=2' '.processId="old-random-id"' '.instanceId=""' '.instanceId="unsafe\nowner"' '.processAlive=false' '.lastUpdatedAt-=121' '.lastUpdatedAt+=3600' '.lastUpdatedAt+=0.5' '.healthWriteIntervalSeconds=0' '.healthWriteIntervalSeconds=1.5' '.authenticated="true"' '.networkOnline=null' '.phase="unknown"' '.phase="stopped"' '.authenticated=true'; do
  jq --argjson now "$(date +%s)" '.lastUpdatedAt=$now | '"$filter" "$directory/base.json" >"$directory/health.json"
  if sh "$script" 2>/dev/null; then echo "invalid synthetic health accepted: $filter"; exit 1; fi
  passed=$((passed+1))
done
# A deliberately slow report policy has its own bounded freshness, independent of login age.
jq --argjson now "$(date +%s)" '.healthWriteIntervalSeconds=100 | .lastUpdatedAt=($now-240)' "$directory/base.json" >"$directory/health.json"
sh "$script"
jq --argjson now "$(date +%s)" '.healthWriteIntervalSeconds=100 | .lastUpdatedAt=($now-301)' "$directory/base.json" >"$directory/health.json"
if sh "$script" 2>/dev/null; then echo "expired slow-report health accepted"; exit 1; fi
printf '%s' '{truncated' >"$directory/health.json"
if sh "$script" 2>/dev/null; then echo "malformed health accepted"; exit 1; fi
printf '{"schemaVersion":0,' >"$directory/health.json"
tail -c +2 "$directory/base.json" >>"$directory/health.json"
if sh "$script" 2>/dev/null; then echo "duplicate health member accepted"; exit 1; fi
cat "$directory/base.json" "$directory/base.json" >"$directory/health.json"
if sh "$script" 2>/dev/null; then echo "multiple health documents accepted"; exit 1; fi
cp "$directory/base.json" "$directory/health.json"
chmod 000 "$directory/health.json"
if [ "$(id -u)" != 0 ]; then
  if sh "$script" 2>/dev/null; then echo "unreadable health accepted"; exit 1; fi
fi
chmod 600 "$directory/health.json"
echo "$passed phase/schema cases plus slow-report, malformed and nonroot permission checks passed"
