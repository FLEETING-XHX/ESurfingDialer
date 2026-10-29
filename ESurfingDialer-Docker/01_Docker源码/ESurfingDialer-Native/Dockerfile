FROM debian:bookworm-slim AS builder
RUN apt-get -o Acquire::Retries=3 update && apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
    g++ cmake ninja-build libcurl4-openssl-dev libxml2-dev libssl-dev ca-certificates jq \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
ARG BUILD_JOBS=2
RUN cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build --parallel "$BUILD_JOBS" && ctest --test-dir build --output-on-failure

FROM debian:bookworm-slim
RUN apt-get -o Acquire::Retries=3 update && apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
    libcurl4 libxml2 ca-certificates jq tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 esurfing && useradd --uid 10001 --gid 10001 --no-create-home esurfing \
    && mkdir /data && chown 10001:10001 /data
COPY --from=builder /src/build/esurfing-native /app/esurfing-native
COPY docker/healthcheck.sh /app/healthcheck.sh
COPY third_party/ciphers/LICENSE /app/licenses/ciphers-APACHE-2.0
COPY third_party/NOTICE.md /app/licenses/NOTICE.md
RUN chmod 755 /app/healthcheck.sh
USER 10001:10001
LABEL org.esurfing.implementation="cpp"
ENV STATE_DIR=/data TZ=Asia/Shanghai
VOLUME ["/data"]
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 CMD ["/app/healthcheck.sh"]
ENTRYPOINT ["/app/esurfing-native"]
