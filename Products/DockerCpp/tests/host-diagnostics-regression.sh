#!/bin/sh
set -eu
script="$1"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
mkdir -p "$root/bin" "$root/net/test-wan"
printf 'down\n' > "$root/net/test-wan/operstate"
printf '0\n' > "$root/net/test-wan/carrier"
cat > "$root/bin/docker" <<'EOF'
#!/bin/sh
case "$1" in
 inspect) printf 'exited 137 true 2\n' ;;
 exec) exit 1 ;;
 *) exit 90 ;;
esac
EOF
cat > "$root/bin/logread" <<'EOF'
#!/bin/sh
echo 'deauth private-MAC-and-SSID-MUST-NOT-LOG'
echo 'disassoc private-address-MUST-NOT-LOG'
EOF
chmod +x "$root/bin/docker" "$root/bin/logread"
PATH="$root/bin:$PATH" HOST_SYS_NET="$root/net" sh "$script" > "$root/result"
grep -q 'status=exited exitCode=137 oomKilled=true restartCount=2' "$root/result"
grep -q 'interface=test-wan operstate=down carrier=0 role=unknown' "$root/result"
grep -q 'deauthOrDisassocMatchesInLast200Lines=2 attribution=unknown' "$root/result"
! grep -q 'MUST-NOT-LOG\|private' "$root/result"
if CONTAINER_NAME='bad;value' sh "$script" > "$root/invalid"; then exit 91; fi
grep -q INVALID_CONTAINER_NAME "$root/invalid"
if CONTAINER_NAME=--help sh "$script" > "$root/invalid"; then exit 92; fi
printf '#!/bin/sh\nexit 1\n' > "$root/bin/logread"
PATH="$root/bin:$PATH" HOST_SYS_NET="$root/missing" sh "$script" > "$root/unavailable"
grep -q 'WIRELESS unavailable' "$root/unavailable"
grep -q 'LINK unavailable' "$root/unavailable"
echo 'Host snapshot: OOM/exit/link observations, unknown attribution, wireless redaction and invalid name checks passed; synthetic only'
