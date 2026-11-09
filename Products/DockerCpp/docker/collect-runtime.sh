#!/bin/sh
# Build-time only. Fixed, trusted distribution executables; never user modules.
set -eu
root="${1:?runtime root required}"
[ "$root" = /runtime-root ] || exit 2
[ ! -e "$root" ] || exit 2
profile="${NATIVE_RUNTIME_PROFILE:-full}"
case "$profile" in full|lean) ;; *) exit 2 ;; esac
mkdir -p "$root/app" "$root/etc" "$root/tmp" "$root/data"
chmod 1777 "$root/tmp"
chown 10001:10001 "$root/data"

copy_file() {
 [ -f "$1" ] || { echo "Required file missing: $1" >&2; exit 1; }
 cp -L --parents "$1" "$root"
}
copy_program() {
 copy_file "$1"
 dependencies="$(ldd "$1")" || { echo "Cannot inspect dependencies: $1" >&2; exit 1; }
 case "$dependencies" in *'not found'*) echo "$dependencies" >&2; exit 1 ;; esac
 printf '%s\n' "$dependencies" | awk '/=> \/|^[[:space:]]*\// { for(i=1;i<=NF;i++) if($i ~ /^\//) print $i }' |
 while IFS= read -r library; do copy_file "$library"; done
}

cp -a /app/. "$root/app/"
copy_program /app/esurfing-native
# Health checks, the existing watchdog guard, log permission helper and bounded
# diagnostics. The lean root uses the loader's own dependency trace, not ldd/Bash.
for program in /bin/sh /usr/bin/jq /usr/bin/cat /usr/bin/wc \
 /usr/bin/date /usr/bin/awk /usr/bin/id /usr/bin/readlink /usr/bin/stat \
 /usr/bin/sha256sum /usr/bin/chown /usr/bin/chmod; do
 copy_program "$program"
done
if [ "$profile" = full ]; then
 for program in /bin/bash /usr/bin/getent /usr/bin/sleep; do copy_program "$program"; done
 copy_file /usr/bin/ldd
else
 mkdir -p "$root/app/licenses/native-deps"
 cp -a /opt/native-deps/share/native-deps/. "$root/app/licenses/native-deps/"
fi

# Dynamic providers/NSS modules are not necessarily listed by ldd.
# Character conversion modules are loaded dynamically too. Retain the installed
# mapping tables/cache so non-UTF8 input does not silently lose libc support.
if [ "$profile" = full ]; then
 find /usr/lib -type d -name gconv -exec cp -a --parents '{}' "$root" ';'
 find /usr/lib /lib -type f -path '*/gconv/*.so' -print |
  sort -u | while IFS= read -r library; do copy_program "$library"; done
else
 # Keep Chinese/Unicode plus common web single-byte encodings. libc implements
 # UTF-8/ASCII directly. Exclude the old cache so it cannot reference omitted files.
 for directory in /usr/lib/*/gconv; do
  [ -d "$directory" ] || continue
  mkdir -p "$root$directory"
  awk '$1 != "module" || $4 ~ /^(ISO8859-1|ISO8859-15|CP1252|UTF-16|UTF-32|GB18030|GBK|EUC-CN|BIG5|BIG5HKSCS)$/ {print}' \
   "$directory/gconv-modules" "$directory"/gconv-modules.d/*.conf > "$root$directory/gconv-modules"
  for module in ISO8859-1 ISO8859-15 CP1252 UTF-16 UTF-32 GB18030 GBK EUC-CN BIG5 BIG5HKSCS; do
   copy_program "$directory/$module.so"
  done
 done
fi
find /usr/lib /lib -type f \( -path '*/ossl-modules/*.so' -o -name 'libnss_*.so.2' \) -print |
 sort -u | while IFS= read -r library; do copy_program "$library"; done
for file in /etc/passwd /etc/group /etc/nsswitch.conf /etc/ld.so.cache \
 /etc/ld.so.conf /etc/gai.conf /etc/services /etc/protocols /etc/os-release \
 /etc/localtime /etc/timezone; do
 if [ -f "$file" ]; then copy_file "$file"; fi
done
# Keep all supported TZ values, CA trust anchors and OpenSSL configuration.
mkdir -p "$root/usr/share"
cp -a /etc/ssl "$root/etc/"
cp -a /usr/share/zoneinfo "$root/usr/share/"
if [ -d /usr/lib/ssl ]; then cp -a --parents /usr/lib/ssl "$root"; fi
if [ -d /usr/share/ca-certificates ]; then cp -a --parents /usr/share/ca-certificates "$root"; fi
# Preserve the targets of /etc/ssl/certs symlinks through the copied trust tree.
find /usr/share/doc -type f -name copyright -exec cp --parents '{}' "$root" ';'
dpkg-query -W > "$root/app/licenses/runtime-source-packages.tsv"
printf '%s\n' 'Source distribution package inventory; not an installed-package database for this minimal root.' \
 > "$root/app/licenses/runtime-source-packages-NOTE.txt"
(cd "$root"; find . -type f -exec sha256sum '{}' + | sort) > /tmp/runtime-files.sha256
cp /tmp/runtime-files.sha256 "$root/app/runtime-files.sha256"
