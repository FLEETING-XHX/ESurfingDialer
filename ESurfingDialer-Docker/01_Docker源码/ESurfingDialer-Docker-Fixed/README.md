# ESurfingDialer Docker Fixed

广东电信天翼校园认证客户端的 Docker 修复版，基于 `ESurfingDialer 1.2.0` 源码改造，重点解决容器长期运行后认证线程失效、设备身份变化、心跳异常后无法自动恢复的问题。v1.3 新增了“认证有效期预测 + 安全窗口重认证”。

## 快速部署

1. 复制环境变量模板：

```sh
cp .env.example .env
```

2. 编辑 `.env`，填写：

```dotenv
DIALER_USER=你的账号
DIALER_PASSWORD=你的密码
AUTO_REAUTH_ENABLED=1
AUTO_REAUTH_SAFE_WINDOW_START_HOUR=4
AUTO_REAUTH_SAFE_WINDOW_END_HOUR=6
```

3. 构建并启动：

```sh
docker compose up -d --build
```

OpenWrt 或软路由环境必须使用 host 网络模式，本项目的 `docker-compose.yml` 已默认配置：

```yaml
network_mode: host
restart: unless-stopped
```

## 直接 docker run

```sh
docker build -t esurfing-dialer:fixed .
docker run -d \
  --name ESurfingDialer \
  --network host \
  --restart unless-stopped \
  -e DIALER_USER='你的账号' \
  -e DIALER_PASSWORD='你的密码' \
  -v "$(pwd)/data:/data" \
  esurfing-dialer:fixed
```

## 状态文件

程序会在 `/data` 中写入：

- `device-state.json`：持久化 MAC 地址和 Client ID。删除该文件才会重新生成身份。
- `health.json`：Docker HEALTHCHECK 使用的业务健康状态。
- `reauth-plan.json`：自动重认证的历史样本、预测间隔和下一次计划时间。

可查看：

```sh
docker logs --timestamps --tail 300 ESurfingDialer
cat data/health.json
cat data/device-state.json
```

## 可配置环境变量

```dotenv
DIALER_USER=
DIALER_PASSWORD=
DIALER_MAC_ADDRESS=
DIALER_CLIENT_ID=
LOGIN_RETRY_INITIAL_SECONDS=5
LOGIN_RETRY_MAX_SECONDS=60
HEARTBEAT_FAILURE_THRESHOLD=3
LOGIN_CONFIRMATION_ATTEMPTS=3
LOGIN_CONFIRMATION_INTERVAL_SECONDS=2
NETWORK_CHECK_INTERVAL_SECONDS=5
NETWORK_CHECK_URLS=http://www.gstatic.com/generate_204,http://connect.rom.miui.com/generate_204,http://www.msftconnecttest.com/connecttest.txt
AUTO_REAUTH_ENABLED=1
AUTO_REAUTH_SAFE_WINDOW_START_HOUR=4
AUTO_REAUTH_SAFE_WINDOW_END_HOUR=6
```

如学校认证系统要求使用真实 WAN MAC，可设置 `DIALER_MAC_ADDRESS`。否则首次启动会生成并保存一个稳定的本地身份。

`AUTO_REAUTH_ENABLED` 设为 `1` 时启用“自动推算下次认证并在安全窗口重认证”，设为 `0` 时关闭，仍保留原来的心跳和掉线重登逻辑。

## 构建

```sh
./gradlew shadowJar
```

Windows：

```bat
gradlew.bat shadowJar
```
