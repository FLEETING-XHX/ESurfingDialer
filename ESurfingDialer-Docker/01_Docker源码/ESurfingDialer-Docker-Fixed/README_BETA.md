# Docker v1.4.0-beta.2 个人测试包

这是本地测试版，没有发布到 GitHub。目前包含 **linux/amd64（x86_64）** 离线镜像，不含 arm64 镜像。

## 与正式版同时保留

| 项目 | 正式版 v1.3 | 本测试版 |
| --- | --- | --- |
| 容器 | ESurfingDialer | ESurfingDialer-Beta2 |
| Compose 项目 | 原有项目 | esurfing-dialer-beta2 |
| 镜像 | esurfing-dialer:v1.3 | esurfing-dialer:v1.4.0-beta.2 |
| 解压目录 | 原有目录 | ESurfingDialer-Docker-v1.4.0-beta.2 |
| 配置/状态 | 原有 .env 和 data | beta 目录下独立 .env 和 data |

把 ZIP 上传到软路由，**解压到新的目录**，不要覆盖正式版目录，不要共用 data，也不要复制正式版的 device-state.json。正式版安装目录、镜像、容器和设备身份均保留。beta1 的 `ESurfingDialer-Beta` 也可以保留，beta2 不使用其容器名或数据。

```sh
unzip ESurfingDialer-Docker-v1.4.0-beta.2.zip
cd ESurfingDialer-Docker-v1.4.0-beta.2
sha256sum -c SHA256SUMS
sh install.sh
```

安装器会询问测试账号、密码，仅导入 beta 镜像并创建 `ESurfingDialer-Beta2`，不下载任何依赖。已存在同名 beta 容器时会停止安装；更新它请在当前 beta 目录使用下方明确项目名称的 Compose 命令。

两个容器可以同时存在，也可以同时运行；同一账号是否允许同时认证取决于校园网策略，可能发生互踢。为了分清测试结果，可以保留正式版容器、测试时自行暂停它，测试结束再启动。beta 安装器不会停止正式版。

## 查看、停止、恢复 beta

```sh
docker logs --tail 100 ESurfingDialer-Beta2
cat data/health.json
docker compose --project-name esurfing-dialer-beta2 stop
docker compose --project-name esurfing-dialer-beta2 up -d --no-build --pull never
```

如需手动创建：

```sh
gzip -dc images/esurfing-dialer-v1.4.0-beta.2-amd64.tar.gz | docker load
cp .env.example .env
vi .env
docker compose --project-name esurfing-dialer-beta2 up -d --no-build --pull never
```

`.env` 中填写 `DIALER_USER` 和 `DIALER_PASSWORD`。请使用包内 Compose 文件，它已设置独立容器名和项目名。本包附带新版可选 watchdog，但不自动启动。默认只监控 `ESurfingDialer-Beta2`，不会管理正式版或 beta1。

## beta2 新增修复与测试重点

beta1 的协议/动态门户/会话修复继续保留，本次新增三组：

1. **停止与监督**：watchdog 不启动已停止、不存在、暂停或正在重启的容器，支持暂停文件、每容器单实例及冷却。网络探测失败也更新完成进度，不把失去认证当作进程卡死。认证阶段按单调时钟监督；超过阶段时限会写错误并退出进程，避免被 JNI 卡住的退出钩子挡住恢复。
2. **健康与资源**：拒绝未来、过期或非法时限；区分探测成功与完成；完整 JSON 编码错误文本；健康、设备身份和计划文件原子替换。默认周期写入由 5 秒改为 10 秒。日志单文件默认 10 MiB、挂载目录总量 50 MiB，保留最近片段；使用常驻日志进程，取消逐行 date/tee 子进程。
3. **重认证计划**：排除重启、切网、普通传输失败、主动/计划认证和时钟跳变样本。只有同进程、同网络连续会话的门户与协议心跳失败相互印证时，才接受保守的相关样本；它仍不是运营商到期策略的证明。提前量随估计缩短，下一计划至少在认证成功后 30 分钟；修复跨午夜窗口、旧状态迁移和损坏/未来状态。

建议验证：首次登录、心跳、强制下线恢复、切网、停止/重启，以及计划/健康文件。不要分享 .env、设备身份或未脱敏的 ticket/账号日志。

### 可选 watchdog

无需在普通测试时启用。如果确实要测试监督，可在 beta2 目录单独运行：

```sh
sh scripts/openwrt-watchdog.sh
```

默认状态/锁/暂停文件名称都包含 `ESurfingDialer-Beta2`。暂停监督可执行：

```sh
touch /tmp/esurfing-watchdog-ESurfingDialer-Beta2.state.paused
# 恢复监督
rm /tmp/esurfing-watchdog-ESurfingDialer-Beta2.state.paused
```

该脚本要求系统已有 `timeout`，不会联网安装依赖。不通过 `docker restart/start` 拉起停止的容器；对确认卡住的运行进程发出退出请求，恢复交给包内 `unless-stopped` 策略。原有正式版 watchdog 不会自动更新；测试时注意原有宿主任务是否仍在管理其各自容器。相关行为依据 [Docker 重启策略](https://docs.docker.com/engine/containers/start-containers-automatically/) 和 [运行中容器 exec](https://docs.docker.com/reference/cli/docker/container/exec/)，实际路由器恢复仍待验证。

计划与协议时间继续采用 UTC+8；`TZ` 用于日志显示。默认 04:00–06:00 安全窗口之外，超出窗口长度的宽限后仍允许补偿认证。设置 `AUTO_REAUTH_ENABLED=0` 可关闭计划认证，心跳/门户恢复继续工作。LuCI 本轮未实现。

### 日志限额与性能采样

日志仍按日期保存，默认每个活动文件最多 10 MiB、最多两份轮转片段，整个程序日志目录最多 50 MiB。会按时间清理旧片段，并为活动文件预留一个文件额度；超长日志行会截断。已有超额程序日志会保留末尾片段或按总额清理，只处理程序生成的日期日志，不处理其他文件。可在 beta2 的 .env 调整：

```dotenv
LOG_FILE_MAX_BYTES=10485760
LOG_TOTAL_MAX_BYTES=52428800
LOG_ROTATED_FILES=2
HEALTH_WRITE_INTERVAL_SECONDS=10
```

修改后在 beta2 目录重新执行对应 Compose up 命令。默认健康阈值会考虑所配写入/探测周期。

只读采集当前 beta2 的 CPU、内存和进程数（默认 10 个样本，每隔 60 秒）：

```sh
sh scripts/measure-runtime.sh 10 > beta2-runtime.csv
```

采集不会启动、停止容器，也不会读取账号或 .env。可用 `CONTAINER_NAME=ESurfingDialer` 采集正式版；比较时保持同一设备和负载。打包电脑没有实际容器性能基线，不据此宣称 CPU/RSS 已下降。

## 验证范围

已进行 JDK17 构建、离线协议/HTTP/原生操作替身、停止/监督、计划器、并发状态写入和日志限额回归；包括隔离子进程的阶段超时退出验证。镜像内容和校验和也已验证。镜像沿用已校验 v1.3 amd64 运行环境，增加新 JAR 与更新后的运行脚本；OCI 与 Docker manifest 同时更新，构建信息见 BUILDINFO.json。镜像归档结构按 [OCI image layout](https://github.com/opencontainers/image-spec/blob/main/image-layout.md) 校验。

打包电脑未运行 Docker daemon，没有实际 docker load/容器启动或校园网认证测试。当前原生库、32 位 Android 模拟架构和 UA 保持原样；未知现场算法尚未验证，不能把离线测试通过理解为校园网认证已恢复。
