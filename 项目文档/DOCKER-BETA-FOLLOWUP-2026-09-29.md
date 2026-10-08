# Docker beta 后续修复记录

初始记录：2026-09-29；状态更新：2026-10-08。基线：`codex/docker-beta` / `4f10b0b`，叠加 09-28 门户修复。本轮完成 [剩余问题审查](BETA-CLIENT-REVIEW-2026-09-28.md) 中的 Docker S1、D1、D2。

后续交付：本记录修复已进入 beta3；10-08 门户兼容重包通过 276/113/41/18 项回归及 9 组交付检查，其中真实 Compose 已验证合成特殊字符凭据保真。用户该现场完成首轮真实认证、首次 keep 确认并可上网，新会话仅约 6 秒，见[最新交付记录](DOCKER-LOCAL-BETA3-PORTAL-2026-10-08.md)。下方检查表保留 09-29 当轮结果，不代表后续尚未构建或部署。

完整网络探测/认证请求竞态、持续运行、自然掉线恢复、停止与数据保留矩阵仍待补，统一跟踪[清单](PENDING-OPTIMIZATIONS.md) DOPT-01、03、04、08，不再重复列为待开发修复。Windows 的 S1、W1–W7 已独立修复并进入[本地 v2.3beta.2 包](WINDOWS-LOCAL-BETA-2026-09-30.md)，Windows 现场认证与长期验收仍未完成。

## 修复内容

### S1：探测成功必须符合预期响应

- `NetworkConnectivity.kt` 不再接受任意 HTTP 200。内置 generate_204 地址及自定义探测端点要求 HTTP 204 且正文为空；微软 connecttest 地址要求 HTTP 200 和精确的 `Microsoft Connect Test` 正文。
- 已识别门户仍进入既有发现流程；无法识别的 200 响应记录 `PROBE_UNEXPECTED_RESPONSE_200` 并继续探测后续地址，全部不符时返回 REQUEST_ERROR。未执行未知页面中的 JavaScript，也不把任意页面当作凭据接收地址。
- 请求构建纳入单地址异常处理，非法自定义地址不会中断其余地址的探测。沿用最多 8 个地址、128 KiB 正文上限及总时间预算。
- 探测函数不再自行修改全局健康成功时间，由网络监测线程根据最终结果更新；门户观测也不再误记为联网成功。协议登录仍由有效 keep 心跳确认。
- 自定义探测站若返回普通 HTTP 200，需改用返回空 HTTP 204 的端点。所有地址只返回未知页面时仍需现场门户证据，当前修复不会凭空识别其认证入口。

微软预期响应依据：[NCSI 官方 FAQ](https://learn.microsoft.com/en-us/windows-server/networking/ncsi/ncsi-frequently-asked-questions)。

### D1：保留独立的认证请求

- `States.kt` 保存一个带递增版本号、入口快照和触发原因的待认证请求。网络 SUCCESS/REQUEST_ERROR 仅更新观测字段，不清除请求。
- 客户端优先消费待认证请求，在旧会话清理前捕获入口。成功时只清除对应版本；期间收到的较新请求保留。队列只保留最新入口，避免无界积累。
- 同一 URL/IP 的重复观测合并为同一请求，允许刷新缓存正文。不同入口必须经网络线程的接受流程才替换待办，避免仅观察到新入口就绕过已登录状态的防抖和冷却。
- 确认登录失败、原生初始化失败和预期解析错误都保留请求并继续按既有上限退避；心跳失败达到阈值后也登记请求，避免一次外网探测成功阻止重新认证。
- 确认期间发现的新门户可用于后续尝试；已登录后的活动会话仍使用自己的 IP、端点和设备上下文。
- 登录完成与未登录状态下的请求接受使用同一把状态锁复核，避免监测线程根据过期的 authenticated=false 再次排入同一入口。
- 请求不因停止后返回的网络结果重新登记；客户端在阻塞阶段之间检查停止状态，退出/清理时取消待办。已有阻塞请求或 JNI 调用仍依赖原有超时与进度监督结束，不承诺能立即中断原生调用。

### D2：安装器保持凭据原值

- `install.sh` 用 Compose 双引号 dotenv 格式编码交互输入：美元符号加倍，反斜线和双引号转义，保留空格、单引号与注释字符；制表符/回车按转义写入。
- 编码器从标准输入读取原值，避免 awk `-v` 提前解释反斜线。没有 eval，也不输出凭据内容到日志。
- 新建 `.env` 时先使用 `umask 077`；已有 `.env` 继续保留原凭据，不猜测或重写其输入含义。已有错误写法需要用户重新核对；README 与模板已补充手动转义示例。
- 没有引入只在新版 Compose 才支持的 raw env_file，也没有改变容器入口参数传递。09-29 当轮未打包；后续 beta3 已纳入新安装脚本并通过真实 Compose 解析。

序列化依据：[Docker 环境变量文件语法](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/#env-file-syntax)、[Compose dotenv 解析实现](https://github.com/compose-spec/compose-go/blob/main/dotenv/parser.go)。

## 09-29 当轮检查结果与限制

工作目录：`ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed`。

| 项目 | 本轮结果 |
| --- | --- |
| JDK17 + Gradle 8.4：`compileKotlin --offline --console=plain` | 两次编译成功；最后一次包含请求缓存刷新、停止检查及等待调整 |
| Git sh：`sh -n install.sh` | 退出码 0，仅语法检查，未执行安装逻辑 |
| `git diff --check` | 通过；只有仓库既有 LF/CRLF 转换提示 |
| Windows 源码 diff | 为空 |
| 新增/运行测试 | 本轮没有；没有把此前 138/113 项结果当成本轮回归结果 |
| Compose config、容器运行、现场认证 | 本轮未执行 |
| 镜像、JAR、ZIP、提交、推送 | 本轮未生成或更新；编译只产生类文件，已有 JAR/ZIP 仍是旧产物 |

09-29 留下的网络/请求矩阵尚未全部完成：首地址未知 200 后继续探测；心跳期间接受恢复请求后再探测成功；认证 A 期间接受 B 后 A 成功；同入口合并、缓存更新与停止边界。其完成条件统一保留在 DOPT-01。凭据特殊字符的真实 Compose 解析已在 beta3 交付检查通过，实际容器内环境及升级数据保留属于实机验收。

本轮保留 Portal XML 兼容与脱敏诊断，不更换原生算法。10-08 用户的首次真实登录/keep 成功只覆盖该现场这次会话，不证明跨校园/未知算法兼容或长期稳定；停止后偶发仍联网未在本轮修复，继续由 DOPT-08 跟踪。历史 beta2 包保持其当时内容。
