# Docker v1.4.0-beta.3 本地测试包

仅本地交付，未上传 GitHub。支持 **linux/amd64（x86_64）**，不包含 arm64 镜像。本包重建当前 Docker 核心，包含 beta2、09-28 门户解析及 09-29 探测/待认证请求/凭据转义修复。

2026-10-08 门户修复重包仍使用 **v1.4.0-beta.3**，构建标识为`beta3-portal-fix-20261008`（见`BUILD_ID`）。新增属性查询串裸`&`兼容、逐项校验后合并规范化相同的auth地址，以及更明确的XML诊断；冲突地址、非法XML及重复ticket仍拒绝。源码276项认证回归通过；用户同日已完成一次真实登录、首次keep确认并可上网，持续心跳、掉线恢复和长期稳定仍待验收。历史ZIP内说明保留打包当时快照。

## 后续源码：心跳日志精简（尚未重包）

默认INFO下，首次周期心跳成功及之后约30分钟输出一条`HEARTBEAT_SUMMARY`，包含期间成功次数与下次间隔；摘要只在真实成功时输出，实际时刻取决于下一次心跳。失败立即保留`HEARTBEAT_FAILED`与异常，失败后的首次成功立即记录`HEARTBEAT_RECOVERED`。健康时间和失败计数仍逐次更新，心跳/认证/重连行为不变。现有`beta3-portal-fix-20261008`镜像及ZIP不包含此改动。

逐次`HEARTBEAT_ATTEMPT`/`HEARTBEAT_SUCCESS`保留在DEBUG。需要详细排障时，在部署目录的`data/log4j-debug.properties`保存完整日志配置（沿用源码`src/main/resources/log4j.properties`），追加：

```properties
log4j.logger.com.rsplwe.esurfing.Client=DEBUG
```

在该容器的`.env`添加`JAVA_OPTS=-Dlog4j.configuration=file:/data/log4j-debug.properties`，已有`JAVA_OPTS`则追加该参数；重建容器后生效。文件需允许容器运行用户读取。排障结束删除此参数并重建，恢复默认级别。不要用`-Dlog4j.debug`代替，它控制日志框架自身的诊断。

## 与正式版并存，交替启动

| 项目 | 正式版 | beta3 |
| --- | --- | --- |
| 容器 | ESurfingDialer | ESurfingDialer-Beta3 |
| Compose 项目 | 原有项目 | esurfing-dialer-beta3 |
| 镜像 | esurfing-dialer:v1.3 | esurfing-dialer:v1.4.0-beta.3 |
| 解压目录 | 原有目录 | ESurfingDialer-Docker-v1.4.0-beta.3 |
| 配置与状态 | 原有 .env、data | beta3 目录独立 .env、data |

解压到新的目录，不覆盖正式版，也不共用 data 或复制正式版 device-state.json。正式版、beta1、beta2 和历史部署包全部保留。

本包 `install.sh` 默认只导入镜像、配置账号并创建 beta3 容器，**不启动认证**；此时正式版可以继续运行。准备测试时先停止正式版，再运行 `start-beta3.sh`。启动脚本会拒绝在正式版、beta1 或 beta2 运行时启动 beta3，不会自行停止其他容器。脚本检查只针对这些约定名称；并发操作或绕过脚本手动启动仍需自行保证互斥。

## 解压与离线部署（推荐）

将 ZIP 上传到软路由的 `/root`，执行：

```sh
cd /root
unzip ESurfingDialer-Docker-v1.4.0-beta.3.zip
cd ESurfingDialer-Docker-v1.4.0-beta.3
sha256sum -c SHA256SUMS
sh install.sh
```

安装器会询问账号密码，自动按 Compose 环境文件语法转义。仅操作 beta3 名称与镜像，保持已有 beta3 .env/data；同名容器已存在时拒绝重复安装，请在原 beta3 目录使用明确项目名称的 Compose 命令管理。

## 已安装旧 beta3：更新为本次门户修复包

保留原beta3目录中的`.env`和`data`；本ZIP不含它们。把新ZIP上传至原解压位置，以`unzip -o`更新交付文件，然后在原beta3目录操作。不要重复运行`install.sh`，也不要复制正式版的`data`。

以下以旧beta3位于`/root/ESurfingDialer-Docker-v1.4.0-beta.3`为例；实际路径不同则替换对应目录：

```sh
cd /root
unzip -o ESurfingDialer-Docker-v1.4.0-beta.3.zip
cd ESurfingDialer-Docker-v1.4.0-beta.3
sha256sum -c SHA256SUMS
cat BUILD_ID

# 停止旧beta3；确认正式版/beta1/beta2也已停止，再启动测试
docker stop -t 60 ESurfingDialer-Beta3
gzip -dc images/esurfing-dialer-v1.4.0-beta.3-amd64.tar.gz | docker load
sh start-beta3.sh --force-recreate
docker logs --tail 100 ESurfingDialer-Beta3
```

镜像标签与容器名保持beta3，必须加载新镜像并重新创建容器；只执行`docker restart`仍会使用旧容器的镜像。启动脚本的`--force-recreate`会重新创建beta3并复用原目录的配置/状态，仍执行其他版本的运行互斥检查。60秒停止宽限仅给已有退出逻辑留出时间，不证明服务端注销成功。

可比较以下两行输出，确认容器引用了当前beta3镜像，两者应相同：

```sh
docker inspect --format '{{.Image}}' ESurfingDialer-Beta3
docker image inspect --format '{{.Id}}' esurfing-dialer:v1.4.0-beta.3
```

本次构建标识还可从镜像标签读取：

```sh
docker image inspect --format '{{index .Config.Labels "esurfing.build.id"}}' esurfing-dialer:v1.4.0-beta.3
```

## 首次安装：手动部署

仅首次安装时使用以下步骤；已有beta3按上方更新流程操作。手动部署同样只创建容器：

```sh
gzip -dc images/esurfing-dialer-v1.4.0-beta.3-amd64.tar.gz | docker load
umask 077
[ -f .env ] || cp .env.example .env
vi .env
mkdir -p data
docker compose --project-name esurfing-dialer-beta3 create --no-build --pull never
```

`.env` 至少填写 `DIALER_USER` 和 `DIALER_PASSWORD`。手动编辑时使用双引号；其中 `$` 写成 `$$`，反斜线 `\` 写成 `\\`，双引号 `"` 写成 `\"`。空格、单引号、`#` 和中文保留在双引号内。交互安装时直接输入原值，不输入手工转义后的值。已有 .env 不会重写凭据。

## 可选：自行构建运行镜像

本包已带离线镜像，通常不需要构建。若要自己构建，包内提供已重建的 beta3 JAR、运行脚本、`Dockerfile.runtime` 和 Compose 构建覆盖文件，不在软路由上重新编译 Kotlin。此方式需要联网获取 Java 基础镜像及 Alpine 软件包，且应在 amd64 主机执行：

```sh
# 在 beta3 解压目录执行；只构建镜像，不启动容器
docker compose --project-name esurfing-dialer-beta3 \
  -f docker-compose.yml -f docker-compose.build.yml build
# .env 配置完成后，只创建，不启动
docker compose --project-name esurfing-dialer-beta3 create --no-build --pull never
```

`.dockerignore` 将 .env、data 和离线镜像归档排除在构建上下文外。自行构建复用包内同一 JAR，但基础镜像/软件包版本可能与离线镜像不同，需另做实际容器验证。

## 测试 beta3 与切回正式版

```sh
# 测试 beta3：停止正式版，再显式启动 beta3
docker stop ESurfingDialer
sh start-beta3.sh
docker logs --tail 100 ESurfingDialer-Beta3

# 切回正式版：先停止 beta3，再启动正式版
docker stop ESurfingDialer-Beta3
docker start ESurfingDialer
```

不要同时启动两个版本。手动 stop 保留容器、镜像和数据，`unless-stopped` 尊重手动停止。重启软路由后仍需核对只运行所选版本。包内可选 watchdog 不自动启用；旧正式版宿主任务仍需按其配置管理，避免外部任务重新启动被停止的版本。

更新 beta3 配置后，保持其他版本已停止，再执行 `sh start-beta3.sh`；脚本中的 Compose up 会应用配置。状态可查看 `data/health.json`。

## 修复与验证边界

beta1/beta2 的协议校验、会话隔离、原生生命周期、停止/监督、健康原子写入、可信认证周期及日志限额继续保留。09-28/29 新增有界门户结构解析与脱敏诊断、严格联网响应、独立认证请求版本与快照、安装器特殊字符凭据转义。

当前包以校验过的 v1.3 amd64 运行环境追加本轮重建 JAR 与运行脚本，OCI/Docker 清单同步更新。源码版本/摘要、基础镜像与 JAR 校验和见 BUILDINFO.json；sourceDirty=true 表示版本及打包说明等有未提交变更。包内 SHA256SUMS 覆盖交付文件。

本机离线回归、镜像内容、Compose 配置、安装/启动替身与压缩包完整性验证见同目录交付记录。本机没有进行真实 docker build/load/容器启动、校园网认证或长期稳定性验收；未知 Algo-ID/算法仍待现场证据。原生库、UA、设备通道不更换。不分享 .env、设备身份或未脱敏票据日志。

## 可选监督与资源采样

普通测试无需启用 watchdog。如需单独测试，使用 beta3 的脚本：

```sh
sh scripts/openwrt-watchdog.sh
# 只读采样，默认每 60 秒一次
sh scripts/measure-runtime.sh 10 > beta3-runtime.csv
```

两个脚本默认目标均为 ESurfingDialer-Beta3，watchdog 不会自行启动停止的容器。暂停监督使用 `/tmp/esurfing-watchdog-ESurfingDialer-Beta3.state.paused`。日志默认单文件 10 MiB、总量 50 MiB；设置、可信样本和安全窗口沿用 beta2，计划/协议时间使用 UTC+8，TZ 控制日志显示。设置 `AUTO_REAUTH_ENABLED=0` 可关闭计划认证，心跳/门户恢复保留。真实资源和周期预测效果仍待测量。
