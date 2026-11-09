ARG NATIVE_RELEASE_CHANNEL=experimental
FROM debian:bookworm-slim AS build-base
RUN apt-get -o Acquire::Retries=3 update && apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
    g++ cmake ninja-build libcurl4-openssl-dev libxml2-dev libssl-dev liblzma-dev ca-certificates jq tzdata \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src

FROM build-base AS lean-dependencies
RUN apt-get -o Acquire::Retries=3 update && apt-get -o Acquire::Retries=3 install -y --no-install-recommends dpkg-dev pkg-config zlib1g-dev
COPY docker/build-lean-deps.sh /tmp/build-lean-deps.sh
ARG BUILD_JOBS=2
RUN sh /tmp/build-lean-deps.sh

FROM build-base AS builder
COPY CMakeLists.txt Dockerfile ./
COPY cmake/ cmake/
COPY include/ include/
COPY src/ src/
COPY tests/ tests/
COPY third_party/ third_party/
COPY profiles/ profiles/
COPY scripts/ scripts/
COPY docker/ docker/
ARG BUILD_JOBS=2
ARG NATIVE_RELEASE_CHANNEL
RUN cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DNATIVE_RELEASE_CHANNEL="$NATIVE_RELEASE_CHANNEL" \
    && cmake --build build --parallel "$BUILD_JOBS" && ctest --test-dir build --output-on-failure

FROM builder AS builder-lean
COPY --from=lean-dependencies /opt/native-deps/ /opt/native-deps/
ENV LD_LIBRARY_PATH=/opt/native-deps/lib
RUN cmake -S . -B build -DCMAKE_PREFIX_PATH=/opt/native-deps \
    -DCURL_INCLUDE_DIR=/opt/native-deps/include -DCURL_LIBRARY_RELEASE=/opt/native-deps/lib/libcurl.so \
    -DLIBXML2_INCLUDE_DIR=/opt/native-deps/include/libxml2 -DLIBXML2_LIBRARY=/opt/native-deps/lib/libxml2.so \
    && cmake --build build --parallel "$BUILD_JOBS" && SLIM_TEST_LEAN_PROFILE=1 ctest --test-dir build --output-on-failure

FROM debian:bookworm-slim AS runtime-full
RUN apt-get -o Acquire::Retries=3 update && apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
    libcurl4 libxml2 libssl3 liblzma5 ca-certificates jq tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 esurfing && useradd --uid 10001 --gid 10001 --no-create-home esurfing \
    && mkdir /data && chown 10001:10001 /data
COPY --from=builder /src/build/esurfing-native /app/esurfing-native
COPY --from=builder /src/build/build-source.json /app/build-source.json
COPY docker/healthcheck.sh /app/healthcheck.sh
COPY third_party/ciphers/LICENSE /app/licenses/ciphers-APACHE-2.0
COPY third_party/NOTICE.md /app/licenses/NOTICE.md
COPY profiles/android32-2082-loader.so /app/module-profiles/android32-2082-loader.so
RUN chmod 755 /app/healthcheck.sh && chmod 444 /app/module-profiles/android32-2082-loader.so
USER 10001:10001
ARG NATIVE_RELEASE_CHANNEL
LABEL org.esurfing.implementation="cpp"
LABEL org.opencontainers.image.version="3.0.0" org.esurfing.release.channel="$NATIVE_RELEASE_CHANNEL"
ENV STATE_DIR=/data TZ=Asia/Shanghai NATIVE_MODULE_LIBRARY=/app/module-profiles/android32-2082-loader.so
VOLUME ["/data"]
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 CMD ["/app/healthcheck.sh"]
ENTRYPOINT ["/app/esurfing-native"]

# Opt-in candidate: retain the same glibc libraries while excluding the rest of
# the distribution. The default target below remains the established runtime.
FROM runtime-full AS runtime-files
USER 0:0
COPY docker/collect-runtime.sh /tmp/collect-runtime.sh
RUN sh /tmp/collect-runtime.sh /runtime-root

FROM scratch AS runtime-slim-control
ARG NATIVE_RELEASE_CHANNEL
COPY --from=runtime-files /runtime-root/ /
USER 10001:10001
LABEL org.esurfing.implementation="cpp"
LABEL org.opencontainers.image.version="3.0.0" org.esurfing.release.channel="$NATIVE_RELEASE_CHANNEL"
LABEL org.esurfing.runtime="minimal-glibc"
ENV STATE_DIR=/data TZ=Asia/Shanghai NATIVE_MODULE_LIBRARY=/app/module-profiles/android32-2082-loader.so
VOLUME ["/data"]
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 CMD ["/app/healthcheck.sh"]
ENTRYPOINT ["/app/esurfing-native"]

FROM runtime-full AS runtime-lean-files
USER 0:0
COPY --from=builder-lean /src/build/esurfing-native /app/esurfing-native
COPY --from=builder-lean /src/build/build-source.json /app/build-source.json
COPY --from=lean-dependencies /opt/native-deps/ /opt/native-deps/
COPY docker/collect-runtime.sh /tmp/collect-runtime.sh
ENV LD_LIBRARY_PATH=/opt/native-deps/lib
RUN NATIVE_RUNTIME_PROFILE=lean sh /tmp/collect-runtime.sh /runtime-root

FROM scratch AS runtime-slim
ARG NATIVE_RELEASE_CHANNEL
COPY --from=runtime-lean-files /runtime-root/ /
USER 10001:10001
LABEL org.esurfing.implementation="cpp"
LABEL org.opencontainers.image.version="3.0.0" org.esurfing.release.channel="$NATIVE_RELEASE_CHANNEL"
LABEL org.esurfing.runtime="minimal-glibc" org.esurfing.runtime.profile="lean-dependencies"
ENV STATE_DIR=/data TZ=Asia/Shanghai NATIVE_MODULE_LIBRARY=/app/module-profiles/android32-2082-loader.so LD_LIBRARY_PATH=/opt/native-deps/lib
VOLUME ["/data"]
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 CMD ["/app/healthcheck.sh"]
ENTRYPOINT ["/app/esurfing-native"]

FROM runtime-full AS runtime
