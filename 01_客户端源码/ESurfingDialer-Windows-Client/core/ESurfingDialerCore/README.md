# ESurfingDialer Docker Fixed

基于 [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer) 完整源码的 Docker 稳定性修复版。此版本保留旧二手 Docker 版本的“一键部署、环境变量传账号密码、host 网络运行”体验，但 JAR 和镜像均从修改后的源码重新构建。

## 快速部署

```sh
cp .env.example .env
```

编辑 `.env`：

```dotenv
DIALER_USER=你的账号
DIALER_PASSWORD=你的密码
```

启动：

```sh
docker compose up -d --build
```

## docker run

```sh
docker build -t esurfing-dialer:itsuwarii-fixed .
docker run -d \
  --name ESurfingDialer \
  --network host \
  --restart unless-stopped \
  -e DIALER_USER='你的账号' \
  -e DIALER_PASSWORD='你的密码' \
  -v "$(pwd)/data:/data" \
  esurfing-dialer:itsuwarii-fixed
```

## 状态文件

容器会把运行状态写入 `/data`，建议长期保留挂载目录：

- `device-state.json`：持久化 MAC 地址和 Client ID。删除该文件才会生成新身份。
- `health.json`：业务健康状态，Docker `HEALTHCHECK` 会读取它。

查看状态：

```sh
docker logs --timestamps --tail 300 ESurfingDialer
cat data/health.json
cat data/device-state.json
```

## 环境变量

```dotenv
DIALER_USER=
DIALER_PASSWORD=

DIALER_MAC_ADDRESS=
DIALER_CLIENT_ID=

LOGIN_RETRY_INITIAL_SECONDS=5
LOGIN_RETRY_MAX_SECONDS=60
HEARTBEAT_FAILURE_THRESHOLD=3
NETWORK_CHECK_INTERVAL_SECONDS=5
NETWORK_CHECK_URLS=http://www.gstatic.com/generate_204,http://connect.rom.miui.com/generate_204,http://www.msftconnecttest.com/connecttest.txt
MAX_ABNORMAL_RECOVERIES_BEFORE_EXIT=30
```

如果学校认证系统要求真实 WAN MAC，可以设置 `DIALER_MAC_ADDRESS`。否则首次启动会自动生成稳定身份并写入 `device-state.json`。

## 构建 JAR

需要 Java 21：

```sh
./gradlew shadowJar
```

Windows：

```bat
gradlew.bat shadowJar
```

构建产物位于 `build/libs/*-all.jar`。

## OpenWrt 说明

OpenWrt 本机常见 musl 环境不适合直接运行原生依赖，推荐继续使用 Docker。镜像运行时使用 glibc 系的 Eclipse Temurin 21 JRE，并默认使用 `network_mode: host`。

可选宿主机兜底脚本：

```sh
sh scripts/openwrt-watchdog.sh
```

它会按“重启容器 → 重连 WAN → 冷却后才重启路由器”的顺序恢复，不做定时重启。
