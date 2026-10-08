#!/bin/sh
set -eu

# 单次只读门户结构诊断。不加载 .env，不挂载 data，不启动认证入口。
if [ "$#" -gt 1 ]; then
    printf '%s\n' 'DIAGNOSTIC code=INVALID_ARGUMENTS'
    exit 2
fi
image="${1:-esurfing-dialer:v1.4.0-beta.3}"
case "$image" in ''|-*|*[!A-Za-z0-9_./:@-]*)
    printf '%s\n' 'DIAGNOSTIC code=INVALID_IMAGE_REFERENCE'
    exit 2
    ;;
esac
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
jar="$script_dir/portal-diagnostic.jar"
if [ ! -r "$jar" ]; then
    printf '%s\n' 'DIAGNOSTIC code=DIAGNOSTIC_JAR_MISSING'
    exit 2
fi
if ! command -v docker >/dev/null 2>&1; then
    printf '%s\n' 'DIAGNOSTIC code=DOCKER_UNAVAILABLE'
    exit 2
fi

# 显式禁止拉取；overlay 只包含诊断入口与门户解析，依赖复用已部署 JAR。
if docker run --rm --pull never --network host --read-only --cap-drop ALL \
    --security-opt no-new-privileges --pids-limit 64 --memory 256m \
    --log-driver none --entrypoint java \
    --mount "type=bind,src=$jar,dst=/diagnostic/portal-diagnostic.jar,readonly" \
    "$image" -XX:-UsePerfData -cp /diagnostic/portal-diagnostic.jar:/app/client.jar \
    com.rsplwe.esurfing.PortalDiagnostic 2>/dev/null; then
    exit 0
else
    printf '%s\n' 'DIAGNOSTIC code=DIAGNOSTIC_CONTAINER_FAILED'
    exit 1
fi
