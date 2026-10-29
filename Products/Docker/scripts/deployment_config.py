"""发布入口共用的版本字段与启动脚本生成；不运行 Docker。"""
import re


def set_version(name, text, version, tag):
    if name == "install.sh":
        for key, value in (("VERSION", version), ("IMAGE_NAME", tag)):
            text, count = re.subn(r'^' + key + r'=.*$', key + '="' + value + '"', text, flags=re.MULTILINE)
            if count != 1:
                raise ValueError("安装模板必须含唯一字段 " + key)
    elif name == ".env.example":
        text, count = re.subn(r'^DIALER_IMAGE=.*$', 'DIALER_IMAGE=' + tag, text, flags=re.MULTILINE)
        if count != 1:
            raise ValueError("环境模板必须含唯一 DIALER_IMAGE")
    elif name == "docker-compose.yml":
        text, count = re.subn(r'(?m)^(\s*image:)\s*.*$', lambda m: m[1] + ' "${DIALER_IMAGE:-' + tag + '}"', text)
        if count != 1:
            raise ValueError("Compose模板必须含唯一镜像字段")
    return text


def startup_script(project, container, filename):
    # 已交付名称也必须覆盖；数据目录锁不能防止不同目录的双认证实例。
    known = ("ESurfingDialer", "ESurfingDialer-Beta", "ESurfingDialer-Beta2",
             "ESurfingDialer-Beta3", "ESurfingDialer-v2.4", "ESurfingDialer-v2.4.1")
    others = " ".join(name for name in known if name != container)
    return r'''#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
case "$#:${1:-}" in
  0:) ;;
  1:--force-recreate) ;;
  *) printf '用法: sh @FILE@ [--force-recreate]\n' >&2; exit 2 ;;
esac
docker info >/dev/null
for other in @OTHERS@; do
  running="$(docker inspect --format '{{.State.Running}}' "$other" 2>/dev/null || true)"
  if [ "$running" = true ]; then
    printf '错误: %s 正在运行，请先停止，再启动 @CONTAINER@。\n' "$other" >&2
    exit 1
  fi
done
docker compose --project-name @PROJECT@ up -d "$@" --no-build --pull never
'''.replace("@FILE@", filename).replace("@OTHERS@", others).replace("@CONTAINER@", container).replace("@PROJECT@", project)
