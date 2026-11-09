#!/bin/sh
# Build from the same patched Debian source versions as the installed dev packages.
# This runs in the builder only; neither compiler nor source archives enter runtime.
set -eu
prefix=/opt/native-deps
sources=/opt/native-deps-source
mkdir -p "$prefix/share/native-deps" "$sources"
sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/debian.sources
apt-get -o Acquire::Retries=3 update
cd "$sources"
xml_version="$(dpkg-query -W -f='${source:Version}' libxml2-dev)"
curl_version="$(dpkg-query -W -f='${source:Version}' libcurl4-openssl-dev)"
apt-get -o Acquire::Retries=3 source "libxml2=$xml_version" "curl=$curl_version"
printf 'libxml2\t%s\ncurl\t%s\n' "$xml_version" "$curl_version" > "$prefix/share/native-deps/versions.tsv"
sha256sum ./*.dsc ./*.tar.* > "$prefix/share/native-deps/source-archives.sha256"
jobs="${BUILD_JOBS:-2}"
for project in libxml2 curl; do
 set -- "$sources/$project-"*/CMakeLists.txt
 [ "$#" = 1 ] && [ -f "$1" ] || exit 1
 source_dir="${1%/CMakeLists.txt}"
 set -- -G Ninja -DCMAKE_BUILD_TYPE=MinSizeRel -DCMAKE_INSTALL_PREFIX="$prefix" \
   -DCMAKE_INSTALL_LIBDIR=lib -DBUILD_SHARED_LIBS=ON
 if [ "$project" = libxml2 ]; then
  set -- "$@" -DLIBXML2_WITH_ICU=OFF -DLIBXML2_WITH_ICONV=ON \
   -DLIBXML2_WITH_PYTHON=OFF -DLIBXML2_WITH_PROGRAMS=OFF -DLIBXML2_WITH_TESTS=OFF \
   -DLIBXML2_WITH_HTTP=OFF -DLIBXML2_WITH_FTP=OFF
 else
  set -- "$@" -DBUILD_CURL_EXE=OFF -DBUILD_TESTING=OFF -DHTTP_ONLY=ON \
   -DCURL_USE_OPENSSL=ON -DCURL_USE_LIBSSH2=OFF -DCURL_USE_LIBSSH=OFF \
   -DCURL_USE_LIBPSL=OFF -DCURL_USE_GSSAPI=OFF -DUSE_LIBIDN2=OFF \
   -DUSE_NGHTTP2=OFF -DCURL_BROTLI=OFF -DCURL_ZSTD=OFF \
   -DCURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt -DCURL_CA_PATH=/etc/ssl/certs
 fi
 cmake -S "$source_dir" -B "$sources/build-$project" "$@"
 cmake --build "$sources/build-$project" --parallel "$jobs"
 cmake --install "$sources/build-$project" --strip
 cp "$sources/build-$project/CMakeCache.txt" "$prefix/share/native-deps/$project-CMakeCache.txt"
 cp "$source_dir/debian/copyright" "$prefix/share/native-deps/$project-copyright"
done
