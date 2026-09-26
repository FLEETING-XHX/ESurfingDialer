# Docker v1.3 对照分析与升级建议

日期：2026-09-26。对象：本轮修复前的 Docker v1.3 源码。本文保留原始分析，后续认证校验、动态门户、会话/原生生命周期三组修复见 [Docker beta 修复记录](DOCKER-BETA-REPAIR.md)。后续监督、健康时间、计划器和日志资源项已在 [beta2 修复记录](DOCKER-BETA2-REPAIR.md) 中实现；容器及校园网现场验证仍待进行，LuCI 暂缓。

## 对照范围与结论

参考项目为 [BadGhost520/ESurfingClient-CVersion](https://github.com/BadGhost520/ESurfingClient-CVersion)。已核对其公开 README 与 master 提交 `ec7071aa56e63f1bea9273a7fcc97dbcf20bfbe6`（2026-09-24）；具体流程对照使用本机 `ESurfingClient-CVersion-2.1.1-r4` 附件，它不随仓库上传，不能假定与最新 master 完全相同。

当前 Docker 版已有完整的离线部署和恢复基础。最值得升级的是认证正确性、门户兼容、会话隔离及监督边界，其次是资源占用和路由器管理体验。C 项目可以提供设计参考，但不能据此认定它在所有学校更稳定，也不能直接替换 Docker 的 Android 32 位原生库、UA 或密钥。

对方 README 的 200–300 kB 指主程序体积，不是完整依赖、系统或 Docker 镜像；不能拿这个数字直接比较本项目整个离线包或内存。其历史长时间内存数据已被作者划线标为旧数据。本次没有进行两者 CPU/RSS/启动速度实测。

## 当前已有、应保留的能力

- `device-state.json` 持久设备身份，升级安装保留 `.env` 和设备状态。
- 登录确认心跳、心跳失败阈值、指数退避、会话重置与异常连接池清理。
- 门户检测防抖、登录后的宽限和重认证冷却。
- `health.json`、Docker healthcheck、可选的 OpenWrt watchdog。
- v1.3 已实现重认证计划和历史样本持久化，默认 04:00–06:00 安全窗口；它不是尚未实现的空规划。
- amd64/arm64 离线镜像导入；标准镜像使用非 root 用户运行 Java；Docker stdout 日志已有 10 MB × 3 个文件的上限。

这些是源码已存在的机制，不等于本次证明其在真实校园网全部正常。

## 优先处理的问题

| 优先级 | 问题及证据 | 可能影响 | 建议 |
| --- | --- | --- | --- |
| P1 | 心跳 `<interval>` 缺失或非数字仍回退旧间隔；ticket/login 使用字符串截取，没有验证必需字段。见 [Client.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/Client.kt#L283) | 解密出错误 XML 时可能仍把心跳当作成功，从而错误确认登录、刷新健康状态。C 附件至少在缺 interval 时返回失败 | 独立加入严格协议解析：缺失/重复/嵌套/非法数字应失败；CDATA/实体兼容；成功确认只能来自合法心跳响应 |
| P1 | 账号、密码、ticket 等直接拼入 XML，动态字段未转义。见 [请求 XML](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/Client.kt#L239) | 含 `&`、`<` 等字符的配置或票据可破坏请求结构 | 对动态字段统一 XML 转义，并用特殊字符输入做协议回归；不改变协议通道 |
| P1 | auth/ticket 和 portal_node 固定；首跳无 IP 时不保留门户 URL。见 [Constants.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/Constants.kt#L10)、[States.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/States.kt#L24)、[NetworkConnectivity.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/utils/NetworkConnectivity.kt#L68) | 门户更换地址、先跳登录页再给配置时可能认证失败；不具备 C 附件的门户配置发现路径 | 从当前门户提取 auth-url/ticket-url，校验 HTTP(S)、IP、长度和有限跳转；没有配置时才使用明确旧入口回退 |
| P1 | 网络线程直接改全局 userIp/acIp；已有会话心跳/退出也读这些字段。见 [DialerApp.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/DialerApp.kt#L80)、[活动会话请求](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/Client.kt#L270) | IP 或网关变化时，旧会话可能带上新网络参数；无 IP 门户在已认证状态下被忽略 | 每个会话持有不可变端点；新门户作为下一次认证候选；恢复请求用版本保留，并按同一候选连续确认 |
| P1 | Session 加载后立即调用 aid，没有先拒绝零句柄；free 没有一次性释放或并发保护。见 [Session.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/hook/Session.kt#L20) | 原生加载失败、退出与心跳并发时可能出现错误 JNI 调用或资源边界问题；不代表 Docker 已出现 Windows 同样的故障 | 可注入原生操作、无效句柄立即失败、初始化失败清理、释放与使用互斥。未知格式/算法记录脱敏错误并有限暂停，不猜新算法 |
| P1（可选 watchdog 启用时） | 容器 Running=false 被视为故障，达到阈值后调用 docker restart。见 [openwrt-watchdog.sh](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/scripts/openwrt-watchdog.sh#L14) | 手动 docker stop 后仍可能被宿主 watchdog 拉起，违背手动停止意图 | 增加明确暂停/期望运行状态与单实例保护；区分不存在、主动停止和意外退出 |
| P2 | watchdog 用线程布尔值和 lastNetworkCheckAt 判断；网络失败不更新该时间，线程布尔值也不是实际进度证明。见 [openwrt-watchdog.sh](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/scripts/openwrt-watchdog.sh#L14)、[DialerApp.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/DialerApp.kt#L80) | WAN/探测站不可达可被误判为线程卡死；反之认证线程卡住但网络线程正常时，也可能未被 watchdog 识别 | 分开“探测完成时间/探测成功时间”和线程进度；增加有上限的认证阶段时限。不要把任何失去认证都变成容器重启 |
| P2 | HTTP 只有连接/读/写超时，API 允许跳转，响应 bytes/string 无业务体积上限。见 [NetClient.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/network/NetClient.kt#L15)、[NetworkConnectivity.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/utils/NetworkConnectivity.kt#L68)、[初始化响应](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/Client.kt#L184) | 慢速持续输出、异常大页或错误跳转可能放大资源占用、延迟恢复 | 限制总体请求时限、重定向次数、门户/加密响应体积；分阶段分类错误 |
| P2 | health/设备/计划 JSON 直接覆盖写入，部分字段手工拼接；healthcheck 不拒绝未来时间。见 [HealthStatus.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/HealthStatus.kt#L36)、[healthcheck.sh](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/docker/healthcheck.sh#L16) | 断电/并发读取可能遇到半份文件；带控制字符的错误信息可生成非法 JSON；未来时间可被错误认为新鲜 | 正规 JSON 序列化、临时文件原子替换、模式/进程标识和时间合法性检查 |
| P2 | Java 环境变量和 `-p` 参数传入密码；debug 登录响应可包含敏感信息；ticket 掩码保留首尾片段。见 [entrypoint.sh](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/docker/entrypoint.sh#L56)、[诊断日志](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/Client.kt#L246) | 本机有读取进程/容器配置权限的主体可见凭据；公开故障日志需进一步脱敏。默认 INFO 并不会执行 debug 响应日志 | 可选 secret 文件/stdin，统一敏感字段脱敏，完全隐藏 ticket/key；不照搬 C 的详细响应日志 |

Docker 的 `restart: unless-stopped` 针对容器退出/停止；仅变成 unhealthy 不会触发本 Compose 中额外的恢复动作。应结合当前进程退出策略和可选 watchdog 设计，不能把“配置了 healthcheck”等同于“认证卡住一定会自动恢复”。依据：[Docker 官方重启策略说明](https://docs.docker.com/engine/containers/start-containers-automatically/)。

## v1.3 重认证预测值得单独完善

实现见 [ReauthPlanner.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/ReauthPlanner.kt#L49)。现有机制可保留，但需要区分有效期到期和其他恢复原因。

1. 非计划重认证成功之间的间隔，只要落在 1 小时到 7 天就作为学习样本。网线拔插、IP 切换、路由器重启也可能形成这样的间隔，未必说明认证有效期缩短。建议记录触发原因、可信样本和估计置信度，重启/网络切换样本不要直接混入到期样本。
2. 当前最低估计为 1 小时，而提前量固定 90 分钟。公式 `登录时间 + 估计有效期 - 90 分钟` 在最低估计时会落到登录前 30 分钟；后面的回退仍可返回该时间。此为源码条件推导，未执行 Kotlin 计划器验证。在安全窗口内可能立即触发计划重认证，应确保下次计划严格晚于当前成功时间，并限制最短重认证间隔。
3. 计划器和协议时间固定 UTC+8，Compose 又允许修改 TZ；需要明确是运营商时间还是可配置时区，并覆盖系统时间回拨/前跳。不要为了校时增加高频公网请求。
4. 超过窗口长度后 `overdueBeyondGrace` 会允许窗口外触发。默认 2 小时是补偿策略，不是“永远只在 04:00–06:00 重认证”；应说明给用户，并通过配置和回归确认。
5. 样本/计划持久化应原子写入、校验时间和文件损坏；主动认证失败保持原有心跳/门户恢复路径。不要为了主动认证取消所有异常恢复，也不要把 48 小时当作所有学校的统一有效期。

## 可继续升级的产品方向

| 方向 | 相对不足 | 升级方式与边界 |
| --- | --- | --- |
| 资源与镜像 | JVM + Android 模拟器 + Docker 的依赖层次较多；当前没有本项目与 C 版的同机测量 | 先测空闲/认证/掉线时 RSS、CPU、启动时间、镜像和离线包大小；再评估 Java 模块精简、GC 与内存参数。C 后端作为独立可选路线评估，不直接重写已有稳定通道 |
| 后台开销 | 默认每 5 秒写 health、每 5 秒探测；理论各 17280 次/天，探测每轮新建 HTTP 客户端。逐行日志还启动 date/tee 等进程 | 复用探测客户端、减少无变化状态写入、事件时及时更新，测量后再选低频间隔；同步调整健康窗口。不能凭频率直接推断 CPU 高 |
| 日志磁盘占用 | Docker stdout 有上限，但 `/data/logs/dialer-日期.log` 只按年龄删除，没有按单文件/总空间限制。见 [entrypoint.sh](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/docker/entrypoint.sh#L56) | 增加大小/总量双重轮转、重复异常限频；同时限制宿主日志与挂载日志，不能只看 Docker logging 配置 |
| 架构与设备覆盖 | 安装器只接受 amd64/arm64，依赖 Docker Compose，并建议至少 1 GB 可用空间。见 [install.sh](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/install.sh#L18) | 明确支持矩阵、实际 ABI/原生后端验证；低配/MIPS 路由器可评估独立 OpenWrt 原生版。C 项目拥有 OpenWrt 包不意味着现有 JVM 模拟器直接支持这些平台 |
| 路由器管理 | 当前以 `.env`、SSH、docker logs/health.json 管理；没有类似 C 附件的 LuCI 管理入口 | 先做只读状态/错误原因/日志导出，再做显式连接、停止和账户配置；限制监听地址、权限和凭据泄露，不直接照搬 Web API |
| 安装与升级 | `.env` 直接写值，特殊字符的 Compose 环境语义需测试；现有升级无包校验和自动回滚步骤 | 测试 `$`、`#`、引号和空格密码；提供校验、配置/身份备份和恢复步骤；继续保持离线导入，不默认增加在线下载依赖 |
| 验证与维护 | 当前 Docker 源码目录未见专用协议/恢复/计划回归或根 CI 工作流 | 先加协议和计划的离线测试，再加脚本模拟、实际容器信号/升级测试与 amd64/arm64验证；14 天某一网络稳定记录不能替代异常输入和兼容验证 |

C 版的 LuCI、跨架构打包、按账户隔离进程和日志轮转可以借鉴。其字符串 XML 解析、详细票据日志、动态模块加载或接口凭据处理同样需要审查；“改成 C”本身不保证认证更正确。

## 建议实施顺序与验收

1. **协议和原生边界**：严格 XML、转义、响应上限、错误分类、句柄保护。覆盖错误页/缺字段/重复字段/非法间隔/特殊字符/零句柄，不误报登录成功。
2. **门户和会话隔离**：有界门户发现、immutable 会话参数、无 IP 跳转、明确切网候选、恢复请求版本。覆盖活动会话与新网络并发，不直接跨产品覆盖 UA/原生库。
3. **监督与计划器**：手动停止不被拉起、检测进度而非结果、时钟合法性、可信样本、未来计划和原子文件。补做窗口跨日、窗口外补偿、断网/重启样本与损坏状态测试。
4. **资源与离线交付**：同设备性能基线、探测复用、日志上限、凭据特殊字符、升级回滚、双架构离线部署。保留设备身份与 .env，避免通过删除 data“修复”。
5. **可选管理界面/新后端**：待现有通道稳定后再评估 LuCI 和 C 算法提供者；先核对许可、脱敏样本和固定测试向量，再接入，不猜密钥。

这些是建议顺序，不是已完成修复，也不据此生成新 Docker 版本。

## 本次验证与限制

- 对照当前 Docker 源码和本地 C 附件，查询上游公开 README/提交及 Docker 官方重启策略。
- 使用真实 `docker/healthcheck.sh` 和完全虚构、晚于当前时间 300 秒的健康文件运行：返回 **0**，确认当前脚本接受该未来时间。
- 使用真实 `scripts/openwrt-watchdog.sh`，仅把 docker/logger/sleep 替换为隔离测试替身。容器 Running=false、失败阈值 3 后观察到 **mock restart requested**；测试用 sleep 替身退出码 7 结束循环，未调用真实 docker restart。
- 测试文件位于 Docker 源码 `build/docker-analysis-fixtures/`，属于忽略的本地验证产物，不含账户信息。
- Docker CLI 可用但本机 daemon 未运行。本次没有启动 Docker，也没有实际容器安装/重启、校园网登录、C 程序认证、真实资源基准或 14 天复测。
- 重认证公式、XML 回退、共享 IP 与原生句柄问题属于当前源码证据；除上述两个 shell 用例外，没有声称已执行端到端缺陷复现。
