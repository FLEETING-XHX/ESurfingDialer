# Docker v1.3 对照分析与后续修复

初始记录：2026-09-26；状态更新：2026-10-08。对象为修复前 Docker v1.3 源码，本文保留缺陷依据；已完成的开发建议改为修复索引。协议/门户/会话修复见 [beta1](DOCKER-BETA-REPAIR.md)，监督/计划器/资源修复见 [beta2](DOCKER-BETA2-REPAIR.md)，探测/恢复请求/凭据修复见 [09-29 记录](DOCKER-BETA-FOLLOWUP-2026-09-29.md)。

这些修复及最新门户兼容已进入[10-08 beta3 本地包](DOCKER-LOCAL-BETA3-PORTAL-2026-10-08.md)。用户该现场首轮真实认证、首次 keep 和上网通过，新会话仅约 6 秒；长期、恢复、注销等仍待验收。当前任务只在[统一待办](PENDING-OPTIMIZATIONS.md)维护，LuCI 仍暂缓。

## 对照范围与结论

参考项目为 [BadGhost520/ESurfingClient-CVersion](https://github.com/BadGhost520/ESurfingClient-CVersion)。当时已核对公开 README 与 master 提交 `ec7071aa56e63f1bea9273a7fcc97dbcf20bfbe6`（2026-09-24）；具体流程对照使用本机 r4 附件，不能假定与上游 master 完全相同。09-26 附件未纳入仓库，09-29 已由用户保存到 `2cd6af1`；此状态变化不代表参考算法已被本项目采用或现场验证。

v1.3 已有完整的离线部署和恢复基础，beta 系列随后补齐认证校验、门户兼容、会话隔离和监督边界。C 项目可以提供设计参考，但不能据此认定它在所有学校更稳定，也不能直接替换 Docker 的 Android 32 位原生库、UA 或密钥。

对方 README 的 200–300 kB 指主程序体积，不是完整依赖、系统或 Docker 镜像；不能拿这个数字直接比较本项目整个离线包或内存。其历史长时间内存数据已被作者划线标为旧数据。本次没有进行两者 CPU/RSS/启动速度实测。

## v1.3 已有、继续保留的能力

- `device-state.json` 持久设备身份，升级安装保留 `.env` 和设备状态。
- 登录确认心跳、心跳失败阈值、指数退避、会话重置与异常连接池清理。
- 门户检测防抖、登录后的宽限和重认证冷却。
- `health.json`、Docker healthcheck、可选的 OpenWrt watchdog。
- v1.3 已实现重认证计划和历史样本持久化，默认 04:00–06:00 安全窗口；它不是尚未实现的空规划。
- 正式 v1.3 支持 amd64/arm64 离线镜像导入；标准镜像使用非 root 用户运行 Java；Docker stdout 日志已有 10 MB × 3 个文件的上限。当前 beta 包只交付 amd64，不能据此推定 arm64 beta 已可用。

这些是源码已存在的机制，不等于本次证明其在真实校园网全部正常。

## 修复前问题与处理结果

下列源码位置用于定位修复前问题，行号属于 09-26 基线；当前实现以修复记录为准。

| 原优先级 | 修复前问题及证据 | 可能影响 | 后续处理 |
| --- | --- | --- | --- |
| P1 | 心跳 `<interval>` 缺失或非数字仍回退旧间隔；ticket/login 使用字符串截取。见 [Client.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/Client.kt#L283) | 错误 XML 可能被当作心跳成功，错误确认登录或刷新健康；C 附件至少在缺 interval 时失败 | beta1 已加入严格协议解析及合法心跳确认，覆盖缺失/重复/嵌套/非法数字、CDATA/实体 |
| P1 | 账号、密码、ticket 直接拼入 XML，动态字段未转义。见 [请求 XML](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/Client.kt#L239) | `&`、`<` 等字符可破坏请求结构 | beta1 已统一转义，并有特殊字符协议回归 |
| P1 | auth/ticket 和 portal_node 固定；首跳无 IP 时不保留门户 URL。见 [Constants.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/Constants.kt#L10)、[States.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/States.kt#L24) | 门户换地址或后续跳转才给配置时可能失败 | beta1 已实现有界动态发现，09-28/10-08 再补解析兼容；该现场首轮通过 |
| P1 | 网络线程直接改全局 userIp/acIp，活动会话也读它。见 [DialerApp.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/DialerApp.kt#L80)、[会话请求](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/Client.kt#L270) | 旧会话可能带新网络参数，无 IP 门户在已认证状态下被忽略 | beta1 已隔离会话参数；09-29 D1 已独立保留恢复请求，完整竞态矩阵见 DOPT-01 |
| P1 | Session 未先拒绝零句柄，free 无一次性释放/并发保护。见 [Session.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/hook/Session.kt#L20) | 加载失败或退出/心跳并发可能越过原生资源边界；未证明现场发生 | beta1 已加入零句柄拒绝、初始化清理、使用/释放互斥及替身回归 |
| P1（启用可选 watchdog 时） | Running=false 达阈值后调用 docker restart。见 [watchdog](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/scripts/openwrt-watchdog.sh#L14) | 手动停止后可能被拉起 | beta2 已尊重停止/暂停/维护状态，并增加锁、冷却与操作前复核；Linux 实机矩阵待补 |
| P2 | 用线程布尔值与仅成功时更新的 lastNetworkCheckAt 判断活性。见 [DialerApp.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/DialerApp.kt#L80) | 网络失败可能误判停滞，认证卡住又可能漏判 | beta2 已区分探测完成/成功，并加入阶段进度与期限；独立 JVM 超时退出本地通过 |
| P2 | HTTP 只有分项超时，API 允许跳转，响应无业务大小上限。见 [NetClient.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/network/NetClient.kt#L15) | 慢速输出、大页或错误跳转延迟恢复并增加资源 | beta1/beta2 已限制请求预算、跳转和响应大小；退出注销的期限/信号仍由 DOPT-08 单独跟踪 |
| P2 | JSON 覆盖写入/手工拼接，healthcheck 接受未来时间。见 [HealthStatus.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/HealthStatus.kt#L36)、[healthcheck](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/docker/healthcheck.sh#L16) | 半份文件、非法 JSON、错误新鲜度 | beta2 已使用完整转义、原子替换、模式/进程标识和时间校验，并有损坏/并发回归 |
| P2 | 环境变量和 `-p` 参数传密码；旧 debug 响应与 ticket 掩码可能保留敏感片段。见 [entrypoint.sh](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/docker/entrypoint.sh#L56) | 有进程/容器配置读取权限者可见凭据；默认 INFO 不输出 debug 响应 | 凭据传递与完整脱敏边界继续跟踪 DOPT-06；不复制 C 的详细票据日志 |

Docker 的 `restart: unless-stopped` 针对容器退出/停止；仅变成 unhealthy 不会触发本 Compose 中额外的恢复动作。应结合当前进程退出策略和可选 watchdog 设计，不能把“配置了 healthcheck”等同于“认证卡住一定会自动恢复”。依据：[Docker 官方重启策略说明](https://docs.docker.com/engine/containers/start-containers-automatically/)。

## v1.3 重认证预测问题与 beta2 修复

实现见 [ReauthPlanner.kt](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/ReauthPlanner.kt)。以下为修复前的风险依据；样本过滤、未来计划、跨午夜窗口与原子持久化已在 beta2 实现，不再重复列为待开发功能。

1. v1.3 将 1 小时至 7 天的非计划认证间隔直接学习，拔线、切 IP、重启也可能混入。beta2 只采纳同进程/同网络且同时有门户与心跳拒绝证据的连续样本，排除传输、切网、重启、计划认证和时钟跳变。
2. 最低估计 1 小时而提前量固定 90 分钟，`登录时间 + 估计有效期 - 90 分钟` 可落到登录前 30 分钟，旧回退仍可能返回过去。beta2 已修复未来计划并用注入时钟回归；09-26 的原分析仅为源码推导。
3. 计划器/协议使用 UTC+8，Compose 的 TZ 不等于协议时区。时钟回拨/前跳边界已有本地回归，运营商实际周期和现场时钟影响仍需实测。
4. `overdueBeyondGrace` 可在超过窗口长度后补偿，默认 2 小时不等于永远只在 04:00–06:00 重认证。beta2 已覆盖窗口和跨午夜边界，实际提前认证效果尚待现场。
5. 样本/计划已使用原子写入与损坏状态迁移，普通心跳/门户恢复仍保留。默认 48 小时只是估计，不能视为所有学校的有效期或已连续运行时长。

## 尚待验证或研究的方向

已完成探测客户端复用、健康写入间隔调整、持久日志处理、大小/总量轮转、离线协议/计划/脚本回归和特殊字符凭据解析，不再列为未来开发。最新 beta3 的 276/113/41/18 项回归及 9 组交付检查见[交付记录](DOCKER-LOCAL-BETA3-PORTAL-2026-10-08.md)，这些结果不能替代软路由实测。

修复前资源依据：v1.3 默认每 5 秒写 health、每 5 秒探测，理论各 17280 次/天；每轮新建探测 HTTP 客户端，逐行日志启动 date/tee，挂载日志只按年龄清理。beta2 已将健康写入默认调整为 10 秒、复用探测客户端、使用持久 awk，并对挂载日志设置默认单文件 10 MiB/总量 50 MiB。此处只是机制前后对照，没有软路由 CPU 或内存收益实测。

| 当前项目 | 尚缺证据或实现 | 统一跟踪 |
| --- | --- | --- |
| 长期资源与预测 | JVM + Android 模拟器 + Docker 尚无与 C 版同机长期比较；真实 RSS/CPU/I/O、日志限额和预测误差需采样。资源收益不能凭依赖层数或写入频率推断 | DOPT-04 |
| 实机恢复与升级 | 首轮认证通过，但网络/请求竞态完整矩阵、Linux 信号/停止、版本交替、身份保留、升级回滚未全验收；包已提供校验和同版本更新步骤 | DOPT-01、03、06、08 |
| 架构与设备 | beta 只交付 amd64；arm64 的 ABI/原生后端、镜像及离线安装未验证。C 项目支持 OpenWrt/MIPS 不证明当前 JVM 通道可用 | DOPT-05 |
| 管理界面与原生后台 | LuCI、低配 OpenWrt 或独立 C 后台仍是后续评估，先明确权限/状态/凭据边界和算法等价证据 | DOPT-07 |
| 维护流程 | 已有离线回归和包校验，尚需把链接检查、交付摘要与关键回归统一成重复执行的发布流程 | MOPT-02 |

C 版的 LuCI、跨架构打包、按账户隔离进程和日志轮转可以借鉴。其字符串 XML 解析、详细票据日志、动态模块加载或接口凭据处理同样需要审查；“改成 C”本身不保证认证更正确。

## 09-26 当轮验证与限制

- 对照当时 Docker v1.3 源码和本地 C 附件，查询上游公开 README/提交及 Docker 官方重启策略。
- 使用修复前真实 `docker/healthcheck.sh` 和完全虚构、晚于当前时间 300 秒的健康文件运行：返回 **0**，确认旧脚本接受该未来时间。
- 使用真实 `scripts/openwrt-watchdog.sh`，仅把 docker/logger/sleep 替换为隔离测试替身。容器 Running=false、失败阈值 3 后观察到 **mock restart requested**；测试用 sleep 替身退出码 7 结束循环，未调用真实 docker restart。
- 测试文件位于 Docker 源码 `build/docker-analysis-fixtures/`，属于忽略的本地验证产物，不含账户信息。
- Docker CLI 可用但本机 daemon 未运行。本次没有启动 Docker，也没有实际容器安装/重启、校园网登录、C 程序认证、真实资源基准或 14 天复测。
- 重认证公式、XML 回退、共享 IP 与原生句柄问题属于当时源码证据；除上述两个 shell 用例外，没有声称已执行端到端缺陷复现。后续本地回归与 10-08 首轮现场成功应分别引用对应记录，不回写成 09-26 已完成实测。
