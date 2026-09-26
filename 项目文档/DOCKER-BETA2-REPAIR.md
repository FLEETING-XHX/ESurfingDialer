# Docker v1.4.0-beta.2 修复与测试记录

日期：2026-09-26。基于前三项修复的 beta1，继续完成对照分析第 4–6 项。仅本地个人测试，不上传 GitHub；LuCI 暂不纳入本轮。

## 修改内容

| 范围 | 本轮实现 | 实际验证范围 |
| --- | --- | --- |
| 监督与手动停止 | 区分认证失败和程序停滞；通过阶段进度与网络检查完成时间判断活性。可选 watchdog 仅处理正在运行且使用 unless-stopped 的容器，尊重停止、暂停、维护标记；单实例锁、连续失败和冷却保护。操作前复核，使用容器内 TERM 退出，不执行 start/restart 拉起已停止容器 | 假 Docker 的停止/暂停/状态变化/锁/冷却回归；独立 JVM 子进程实际验证阶段超时记录诊断并退出 |
| 健康状态与资源 | schema 2 完整 JSON 转义，拒绝未来时间；同目录临时文件原子替换，设备身份和计划状态共用安全读写。健康文件默认每 10 秒写一次，探测复用连接并有总预算。持久 awk 日志输出，默认单文件 10 MiB、总量 50 MiB、轮转 2 份；只清理本项目命名日志 | 并发读写、损坏 JSON、未来时间、合法长等待、日志字节/轮转/总量/无关文件保留回归；提供只读 CPU/内存/PID 采样脚本，尚未实测软路由性能 |
| 重认证计划 | 仅采纳同一进程、同一网络且同时有门户与心跳拒绝证据的连续样本；断网、传输错误、切网、重启、计划认证、时钟跳变均不作为寿命学习。修复过去计划、跨午夜窗口与损坏状态迁移；保留默认 48 小时估计及普通故障恢复 | 注入时钟的样本/窗口/回拨/状态边界回归；未证明运营商真实认证期限或实际提前认证效果 |

长期重试分段更新进度，避免合法的 300 秒探测等待或最长退避被判断为线程卡死。JNI 调用仍设置独立阶段期限；超时退出绕过可能等待 JNI 锁的关闭钩子。宿主可选 watchdog 不自动安装或运行。

原有 Android 32 位原生库、UA、算法与设备标识规则保留。未知现场 Algo-ID 不猜测、不替换。

## 本地验证

以 JDK17 和已有 Gradle 8.4 离线运行 `check shadowJar --offline --console=plain`。认证回归 72 项；运行状态/计划/原子读写回归 113 项；监督/采样脚本回归 41 项；日志回归 18 组。

最后针对长等待阶段的修改再次执行 `authenticationRegression operationsRegression shadowJar --offline --console=plain`。测试使用隔离的 build 临时状态、本机 HTTP 替身与 JVM 子进程，没有使用真实账户、现有 data 或真实 Docker 容器。

## beta2 测试包

- 版本：`v1.4.0-beta.2`，镜像 `esurfing-dialer:v1.4.0-beta.2`。
- 容器：`ESurfingDialer-Beta2`，Compose 项目：`esurfing-dialer-beta2`。
- 文件：`ESurfingDialer-Docker-v1.4.0-beta.2.zip`，位于 `ESurfingDialer-Docker/00_最终部署包/Docker_v1.4.0-beta.2/`。
- 与正式版 `ESurfingDialer` 和 beta1 `ESurfingDialer-Beta` 各用独立目录、.env 与 data；同名 beta2 已存在时安装器拒绝创建。
- 保留 beta1 包；不包含真实账号、data 或工具协作文档。包内附 README_BETA、SHA256SUMS、BUILDINFO 和可选 watchdog/只读采样脚本。

打包使用已发布 v1.3 amd64 运行环境（SHA256 `93bca5389a75eb997f1aec0d0316f433401e758129f01d7fa862ffb2bb9fb5fd`），追加当前 JAR 和运行脚本；逐项验证 OCI/Docker 元数据、层摘要与 JAR 内容。不沿用旧构建证明冒充新镜像证明。

## 待现场验证

本机 Docker daemon 未运行；本轮没有实际 docker load、容器启动、软路由长时间 CPU/RSS 或校园网端到端认证验证。包仅适用 linux/amd64，arm64 beta2 尚未构建。双容器能够使用不同名字与数据目录；同账号能否同时在线仍取决学校策略。

下一步由个人测试确认镜像导入、登录/心跳、手动停止、断网恢复、日志配额和重认证时间。性能采样示例：`CONTAINER_NAME=ESurfingDialer-Beta2 SAMPLES=10 INTERVAL_SECONDS=60 sh scripts/measure-runtime.sh > beta2-runtime.csv`。LuCI 后续另行评估。
