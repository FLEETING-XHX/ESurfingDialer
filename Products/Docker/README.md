# ESurfingDialer Docker 源码说明

广东电信天翼校园认证客户端的 Docker 修复版，基于 `ESurfingDialer 1.2.0` 源码改造，重点解决容器长期运行后认证线程失效、设备身份变化、心跳异常后无法自动恢复的问题。v2.4.1 将旧认证时长预测改为每天一次的计划重登。

当前源码版本为 **v2.4.1**，本次只完成源码修复与验证，尚未生成安装包或公开发布。历史正式版本为v2.4。下载 `ESurfingDialer-Docker-v2.4.zip`，仅支持 amd64，离线安装步骤见[软路由图文部署教程](../../Docs/Docker/教程/ESurfingDialer-Docker部署教程.md)。本包汇入 beta3 门户、恢复和日志修复，以及 10-10 审查修复。此前用户已完成一次真实认证与上网，v2.4 的真实恢复、注销与长期稳定仍待验收；历史 v1.3 / beta 包保持各自快照。

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

这些行为已加入`check`：`authenticationRegression`验证门户退避和单调心跳，`entrypointRegression`用生产脚本及Java/su-exec替身检查两个启动分支、日志故障与退出，`commandLineRegression`再用真实Commons CLI解析替身捕获的参数。详细依据、验证和未覆盖边界见[本次审查修复记录](../../Docs/Docker/DOCKER-CODE-REVIEW-FIXES-2026-10-10.md)。

开发者在线构建时显式加载构建覆盖文件；默认 `docker-compose.yml` 只用于部署，不包含 build：

```sh
docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build
```

OpenWrt 或软路由环境必须使用 host 网络模式，本项目的 `docker-compose.yml` 已默认配置：

```yaml
network_mode: host
restart: unless-stopped
```

使用离线包时先导入包内镜像，再执行 `docker compose up -d --no-build --pull never`。beta 使用其包内独立 Compose 项目和名称，避免误操作正式容器。源码默认镜像标签为v2.4.1；修改版本不表示已生成新镜像或发布包。

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
- `reauth-plan.json`：每日重登的日期去重账本与下一次计划时间；不再学习会话有效期。
- `core-owner.lock`：进程生命周期独占锁；多个容器不得共享一个data目录。锁文件可以保留，操作系统在进程退出时释放实际锁。

10-29后续源码修复：已有身份文件损坏、缺字段或无法读取时，核心报 `DEVICE_IDENTITY_INVALID` 并拒绝初始化，保留原文；环境变量覆盖也不会绕过损坏文件检查。请先停止容器、保存故障文件并恢复有效的身份文件，再启动；不要用删除身份文件来绕过错误。有效身份无变更时不重写，显式MAC/Client ID覆盖仍经校验后原子持久化。

正式与beta打包入口现在共用版本字段和启动脚本生成：安装与启动使用同一个Compose项目名，启动前检测v2.4等已交付容器，旧容器运行时拒绝继续。旧beta入口生成自己的镜像文件名及标签，不再依赖源码中残留的v1.3字符串。这些改动仅在当前源码，历史部署包未更新。定向离线检查为Gradle的 `deviceIdentityRegression` 与 `deploymentConfigRegression`；后者使用Docker替身执行生产生成片段、安装器和启动脚本，不构建镜像。

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

`AUTO_REAUTH_ENABLED` 设为 `1` 时启用“每天在指定窗口重登一次”，设为 `0` 时关闭，仍保留原来的心跳和掉线重登逻辑。

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


## v2.4.1 每日重登与状态修复

`AUTO_REAUTH_ENABLED=1`启用每日一次计划重登，设为`0`关闭；普通心跳、断线恢复与门户认证不受此开关影响。按UTC+8每天计算，默认起始04:00、结束06:00，在窗口起点执行；程序在窗口内启动可补执行，错过窗口则等次日，不在白天补登。窗口内刚完成的普通登录计入当日任务，避免立即再登录。

起始小时为0～23，结束小时为0～24，**结束不能早于起始**。相等表示固定时刻，并允许该分钟内执行；不表示全天。无跨午夜窗口。错误配置会明确拒绝启动，不静默交换时间。修改`.env`后通过正常Compose更新使配置生效。

计划重登在操作前持久化日期认领，重启和时钟回拨不会对同一天重复触发；失败后的普通认证恢复仍可继续。已知格式账本损坏时保守跳过当天并修复记录，次日可继续；未知格式不覆盖，计划暂停并记录诊断。无法保存日期时不会发起计划操作，不影响普通恢复。关闭开关保留账本，窗口内普通登录也计入当日任务，避免同日重新启用时重复执行。

`health.json`分开报告`networkReachable`和`authenticated`。`connectionState=network-online-unowned`表示外网可达但不属于本核心的已确认会话；新鲜且有线程进展时健康检查可通过，不伪造认证成功。若此时没有可信门户/IP上下文，每日重登明确等待门户发现，不猜地址、不注销其他进程会话。外部autoheal请使用随源码更新的健康脚本。

认证核心启动前获取数据目录独占锁；已有核心持有时输出`CORE_STATE_IN_USE`并以专用码73退出。入口清理自有子进程后保持暂停，容器本地冲突标记使健康检查失败，避免读取另一个容器的共享健康快照或被`unless-stopped`不断重启；附带的OpenWrt watchdog也识别该暂停，即使共享健康变旧也不回收。修正数据目录后请明确重启容器。正常停止仍可结束暂停进程，外置autoheal可能主动重启不健康容器，需按外部策略处理。尚未验证真实校园网、多容器宿主锁行为或长期稳定性，离线回归结果不代表已完成现场验收。
