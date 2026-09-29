# Docker beta 后续修复记录

日期：2026-09-29。基线：`codex/docker-beta` / `4f10b0b`，叠加 09-28 已有门户修复。本轮处理 [剩余问题审查](BETA-CLIENT-REVIEW-2026-09-28.md) 中的 Docker S1、D1、D2；Windows 问题留待后续。

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
- 没有引入只在新版 Compose 才支持的 raw env_file，也没有改变容器入口参数传递。现有 beta 打包器会读取新安装脚本，本轮未执行打包器。

序列化依据：[Docker 环境变量文件语法](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/#env-file-syntax)、[Compose dotenv 解析实现](https://github.com/compose-spec/compose-go/blob/main/dotenv/parser.go)。

## 检查结果与限制

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

后续行为验证应覆盖：首探测地址返回未知 200、后续返回有效门户或预期成功响应；心跳阻塞时接受恢复请求后又探测成功；认证 A 期间接受入口 B 且 A 成功；同入口重复观测不造成连续重登；坏缓存变为有效缓存；停止发生在发现/确认/退避阶段；包含美元符号、引号、连续或末尾反斜线、空格、中文的合成凭据经过真实 Compose 解析后保持一致。

本轮保留此前 Portal XML 兼容与脱敏诊断。现场 Algo-ID、原生算法兼容、实际登录/心跳与长期运行仍待证据。源码修复没有自动更新软路由或历史 beta2 部署包。
