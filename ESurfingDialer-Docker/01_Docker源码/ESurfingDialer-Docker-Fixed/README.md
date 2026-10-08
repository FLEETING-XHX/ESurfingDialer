# ESurfingDialer Docker 源码说明

广东电信天翼校园认证客户端的 Docker 修复版，基于 `ESurfingDialer 1.2.0` 源码改造，重点解决容器长期运行后认证线程失效、设备身份变化、心跳异常后无法自动恢复的问题。v1.3 新增了“认证有效期预测 + 安全窗口重认证”。

当前源码版本为v1.4.0-beta.3；最新本地包`beta3-portal-fix-20261008`包含beta2、09-28/29及属性查询串/等值auth兼容修复，用户首轮认证与上网通过，持续心跳/恢复/长期资源仍待验收。正式用户使用已发布v1.3，见[离线说明](README_OFFLINE.md)；beta3操作见[测试说明](README_BETA.md)，包摘要与现场结果见[交付记录](../../../项目文档/DOCKER-LOCAL-BETA3-PORTAL-2026-10-08.md)，剩余任务见[统一待办](../../../项目文档/PENDING-OPTIMIZATIONS.md)。

## 配置账户

1. 首次配置时复制环境变量模板；已有`.env`保留原文件：

```sh
cp .env.example .env
```

2. 编辑 `.env`，填写：

```dotenv
DIALER_USER="你的账号"
DIALER_PASSWORD="你的密码"
AUTO_REAUTH_ENABLED=1
AUTO_REAUTH_SAFE_WINDOW_START_HOUR=4
AUTO_REAUTH_SAFE_WINDOW_END_HOUR=6
```

手动编辑账号密码时，按 Compose 双引号格式填写：美元符号 `$` 写成 `$$`，反斜线 `\` 写成 `\\`，双引号 `"` 写成 `\"`；空格、单引号和 `#` 保留在双引号内。例如实际密码 `ab$cd` 写成 `DIALER_PASSWORD="ab$$cd"`。新版源码中的安装脚本会自动转义交互输入；已有 `.env` 不会自动重写凭据。

## 源码构建与启动

开发者在线构建时显式加载构建覆盖文件；默认 `docker-compose.yml` 只用于部署，不包含 build：

```sh
docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build
```

OpenWrt 或软路由环境必须使用 host 网络模式，本项目的 `docker-compose.yml` 已默认配置：

```yaml
network_mode: host
restart: unless-stopped
```

使用离线包时先导入包内镜像，再执行 `docker compose up -d --no-build --pull never`。beta 使用其包内独立 Compose 项目和名称，避免误操作正式容器。源码中的默认镜像标签仍可能沿用 v1.3；源码构建不表示已生成新发布版本。

## 直接启动源码构建的镜像

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

当前源码的联网探测只把空正文的 HTTP 204，或 `www.msftconnecttest.com/connecttest.txt` 返回的 HTTP 200 与精确文本 `Microsoft Connect Test` 视为成功。自定义 `NETWORK_CHECK_URLS` 请使用返回空 HTTP 204 的探测端点。其他 200 页面会继续检查后续地址；全部无法确认时报告 `PROBE_UNEXPECTED_RESPONSE_200`，不会作为联网成功。此修复已加入 beta3；历史 v1.3/beta2 部署包保留原样。

## 构建认证 JAR

核心使用 Java 17；Gradle/依赖齐全时可离线构建。以下命令只构建 JAR，不生成部署 ZIP：

```sh
./gradlew shadowJar
```

Windows 命令：

```bat
gradlew.bat shadowJar
```
