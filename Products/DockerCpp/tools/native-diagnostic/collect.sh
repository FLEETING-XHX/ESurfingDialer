#!/bin/sh
set -eu
umask 077
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
case "${1:-collect}" in
 collect) mode=collect; network=host ;;
 offline) mode=offline; network=none ;;
 module) mode=collect-module; network=host ;;
 header) mode=offline-header; network=none ;;
 *) echo '用法：sh collect.sh [collect|offline|module|header]' >&2; exit 2 ;;
esac
[ "$(uname -m)" = x86_64 ] || { echo '当前诊断包只验收x86_64路由器。' >&2; exit 2; }
[ -f native-diagnostic.jar ] || { echo '请先完整解压诊断包。' >&2; exit 2; }
image=esurfing-dialer:v2.4
docker image inspect "$image" >/dev/null 2>&1 || { echo '未找到本地Kotlin v2.4镜像，不会联网拉取。' >&2; exit 2; }
mkdir -p input results
output="$(mktemp -d "$PWD/results/run-XXXXXX")"
docker image inspect --format '{{.Id}}' "$image" > "$output/kotlin-image-id.txt"
code=0
# 独立容器，不挂载旧data/.env/配置，不调用旧容器的exec/start/stop。
docker run --rm --pull never --network "$network" --read-only --cap-drop ALL \
 --security-opt no-new-privileges:true --pids-limit 128 --memory 768m \
 --user "$(id -u):$(id -g)" --env STATE_DIR=/tmp/native-diagnostic \
 --env "DIAGNOSTIC_BACKEND=${DIAGNOSTIC_BACKEND:-unicorn2}" \
 --tmpfs /tmp:rw,exec,nosuid,size=128m \
 --mount "type=bind,src=$PWD/native-diagnostic.jar,dst=/tool/native-diagnostic.jar,readonly" \
 --mount "type=bind,src=$PWD/input,dst=/input,readonly" \
 --mount "type=bind,src=$output,dst=/output" \
 --entrypoint java "$image" -Xmx256m -cp /tool/native-diagnostic.jar:/app/client.jar \
 -XX:ErrorFile=/output/jvm-crash.log NativeDiagnostic "$mode" > "$output/private-tool.log" 2>&1 || code=$?
printf '诊断完成，退出码：%s\n结果目录：%s\n' "$code" "$output"
if [ -f "$output/report.properties" ]; then
 cat "$output/report.properties"
else
 echo '未生成摘要；请保留private-tool.log，本轮未获得完整材料。' >&2
fi
echo '先只反馈report.properties；不要公开module.bin、输入地址或private-tool.log。'
exit "$code"
