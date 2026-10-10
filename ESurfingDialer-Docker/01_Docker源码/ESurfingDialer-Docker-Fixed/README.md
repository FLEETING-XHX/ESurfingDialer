# ESurfingDialer Docker 源码说明

广东电信天翼校园认证客户端的 Docker 修复版，基于 `ESurfingDialer 1.2.0` 源码改造，重点解决容器长期运行后认证线程失效、设备身份变化、心跳异常后无法自动恢复的问题。v1.3 新增了“认证有效期预测 + 安全窗口重认证”。

当前源码与本次发布版本统一为 **v2.4**。下载 `ESurfingDialer-Docker-v2.4.zip`，仅支持 amd64，离线安装步骤见[软路由图文部署教程](../../02_使用教程/ESurfingDialer-Docker部署教程.md)。本包汇入 beta3 门户、恢复和日志修复，以及 10-10 审查修复。此前用户已完成一次真实认证与上网，v2.4 的真实恢复、注销与长期稳定仍待验收；历史 v1.3 / beta 包保持各自快照。

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

10-10源码安装器追加缺失的自动重认证配置项时，先写换行分隔符，避免已有`.env`末行无换行时损坏密码或镜像标签；已配置的开关和安全窗口值保持。此修复进入 v2.4，历史离线包保持原样。可在源码目录运行 `python scripts/check-installer.py`，用Docker替身和合成配置执行31组隔离回归；也已接入Gradle的`installerRegression`及`check`任务，不访问真实配置或容器。

## 源码构建与启动

v2.4 已补五项审查修复：构建上下文排除根目录及子目录中的私有`.env`/环境配置备份，仅保留根目录`.env.example`模板；门户正文的nonce/时间戳不再重置退避，有效认证配置或门户身份变化仍允许提前重试；启动参数保留选项样式和带双引号的密码；周期心跳使用单调时钟；日志进程提前退出时，入口终止Java并非零退出，Java在五秒宽限后仍未结束则强制终止。正常退出保留Java退出码并有界等待日志收尾。

这些行为已加入`check`：`authenticationRegression`验证门户退避和单调心跳，`entrypointRegression`用生产脚本及Java/su-exec替身检查两个启动分支、日志故障与退出，`commandLineRegression`再用真实Commons CLI解析替身捕获的参数。详细依据、验证和未覆盖边界见[本次审查修复记录](../../../项目文档/DOCKER-CODE-REVIEW-FIXES-2026-10-10.md)。

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

恢复达到上限时抛出专用异常，先释放嵌套会话锁，再由现有线程异常处理器退出 JVM；shutdown hook 可以完成会话清理。`recoveryExitRegression` 用真实子 JVM、禁止网络与原生加载的会话替身验证退出码 1、清理一次及锁释放。
