# Docker beta 与 Windows 客户端历史问题审查

初始审查：2026-09-28；状态更新：2026-10-08。历史范围：Docker `codex/docker-beta` / `4f10b0b` 加当时未提交的门户修复，以及 Windows v2.3beta 本地源码。下面的触发路径与行号属于修复前基线，不是当前待开发清单。

这些源码问题均已修复，已完成的修复方向和处理顺序从本文删除，保留根因与触发证据：

- Docker S1、D1、D2 见[09-29 修复](DOCKER-BETA-FOLLOWUP-2026-09-29.md)，已进入[10-08 beta3 本地包](DOCKER-LOCAL-BETA3-PORTAL-2026-10-08.md)。合成凭据已通过真实 Compose 解析；用户该现场首轮认证、首次 keep 和上网通过，新会话仅约 6 秒，完整竞态/恢复/长期矩阵仍待验收。
- Windows S1、W1–W4 及随后发现的 W5–W7 见[八项修复](WINDOWS-CLIENT-FIXES-2026-09-29.md)，已进入[本地 v2.3beta.2 包](WINDOWS-LOCAL-BETA-2026-09-30.md)。包内 188 项检查与实际 EXE 入口通过，真实认证、恢复与长期稳定仍待现场。

两端剩余验收与研究只在[汇总清单](PENDING-OPTIMIZATIONS.md)维护。Docker 的一次现场成功不构成 Windows 或其他校园的认证证明。

09-28 审查以静态控制流和当时的回归断言为依据，没有修改产品代码或运行真实认证。以下代码推导不表示各问题都曾在用户校园网发生；P1/P2 保留当时的优先级。

## S1 · P1 · 历史问题：未识别的 HTTP 200 被当作网络畅通

- 位置：[Docker NetworkConnectivity.kt:58](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/utils/NetworkConnectivity.kt#L58)，[Windows NetworkConnectivity.kt:67](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/utils/NetworkConnectivity.kt#L67)。
- 触发：未认证网络返回状态 200 的登录页、提示页或 JS 跳转页，页面既无已知配置标记，也无当前识别的 IP 关键词。
- 结果：`parsePortalBody` 返回 null，探测函数立刻返回 SUCCESS，后面的探测地址不再访问。无会话的认证循环在 SUCCESS 分支等待，可能一直不开始登录。
- 注意：这里误判的是网络探测；代码没有因此直接将协议认证的 authenticated 标志设为 true。
- 后续：两端已独立核对预期状态/正文并继续探测非预期响应；Docker 完整探测矩阵见 DOPT-01，Windows 实际网络验收见 WOPT-03。

## D1 · P1 · 历史问题：Docker 重认证请求可能被覆盖

- 位置：[DialerApp.kt:69](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/DialerApp.kt#L69)、[DialerApp.kt:88](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/DialerApp.kt#L88)、[Client.kt:236](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/Client.kt#L236)。
- 触发：客户端正等待心跳/认证请求，网络线程达到门户恢复条件，将 `States.networkStatus` 设为门户状态并记录冷却时间。客户端尚未消费它时，下一次成功探测又将同一字段设为 SUCCESS；较早认证结束也会直接写 SUCCESS。
- 结果：请求没有独立的待办标记/版本号，可能被丢弃；冷却时间已经计入，后续门户恢复还可能受默认 300 秒冷却限制。恢复延迟取决于后续心跳和探测结果。
- 后续：已增加独立、带版本的待认证请求，按版本消费并保留较新入口；上述竞态与手动停止矩阵仍由 DOPT-01 跟踪。

## D2 · P2 · 历史问题：Docker 安装器未编码账号密码

- 位置：[install.sh:83](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/install.sh#L83)；已生成 beta2 包中的 install.sh 同样直接写入 `DIALER_PASSWORD=$dialer_password`。beta 打包器沿用这一段。
- 触发：密码包含形如 `$NAME`、`${NAME}` 的内容，或带有会被 .env 语法解释的引号/空白组合。
- 结果：未加保护地写入 .env 后，Compose 会执行变量展开等解析，容器拿到的值可能与输入不同；表现为用户输入正确但认证仍失败。
- 依据：Docker 官方说明未加引号与双引号的 env_file 值会插值，单引号按字面量处理，未定义插值变量会被替换为空。[env_file 语法](https://docs.docker.com/reference/compose-file/services/#env_file)、[插值规则](https://docs.docker.com/reference/compose-file/interpolation/)。
- 当时验证边界：09-28 找不到 Docker CLI，合成 Compose config 夹具未成功执行；当时结论来自安装脚本与官方语法。
- 后续：安装器已按兼容 dotenv 格式编码，不依赖新版本 raw env_file。beta3 已用真实 Compose 检查中文、空格、注释字符、引号、美元符号、连续/末尾反斜线及制表符的合成值保真；实际容器环境仍属实机矩阵。

## W1 · P1 · 历史问题：Windows 心跳卡住仍显示已连接

- 位置：[HealthSnapshot.cs:80](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/app/ESurfingDialerLite/HealthSnapshot.cs#L80)、[EnhancedConnectionPolicy.cs:45](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/app/ESurfingDialerLite/EnhancedConnectionPolicy.cs#L45)、[Client.kt:121](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/Client.kt#L121)。
- 触发：已成功登录，后续心跳的原生加解密/JNI 调用卡住且没有返回；独立健康线程和网络线程仍在运行。
- 结果：authenticated 和 clientThreadAlive 仍保持 true，健康文件持续刷新。界面只核对 authenticated 与文件时间，增强恢复在 authenticated=true 时直接返回无异常；已登录心跳也没有独立阶段进度期限，因此可能长期显示已连接且不恢复。
- 旧测试：[Program.cs:66](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/tests/ClientRegression/Program.cs#L66) 当时接受 authenticated=true 且心跳过期的快照，未覆盖线程活着但无进展。
- 后续：Windows 已增加心跳阶段进度/期限和健康判定，本地覆盖合法长间隔、停滞、停止与恢复上限；真实 JNI/网络边界仍由 WOPT-03 验收。

## W2 · P1 · 历史问题：Windows 门户解析与缓存失败路径

- 位置：[PortalConfiguration.kt:40](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/PortalConfiguration.kt#L40)、[PortalConfiguration.kt:65](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/PortalConfiguration.kt#L65)。
- 触发：合法字段带标签空白/属性、混合 CDATA 与文本等旧正则不支持的结构；或缓存的内联配置不完整。
- 结果：返回 PORTAL_CONFIG_INVALID，缓存解析异常还会在重新 GET 前抛出。Windows 的确定性错误预算第三次失败后暂停，用户可能需要手动断开重连。
- 证据边界：确认存在与 Docker 原解析器同类的代码限制，不能据此断定用户这次 Docker 现场页面必然会在 Windows 出现相同结果；两端 UA 与门户返回可能不同。
- 后续：Windows 已独立加入有界结构解析、脱敏诊断和失效缓存重取，并保留其原生通道/有限重试；真实门户兼容仍由 WOPT-01/03 验收。10-08 Docker 的属性裸 `&`/等值 auth 修复没有跨产品复制到 Windows。

## W3 · P2 · 历史问题：Windows 忽略最终跳转 URL 中的 IP

- 位置：[PortalConfiguration.kt:79](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/PortalConfiguration.kt#L79)。
- 触发：首跳没有 user/ac IP，后续跳转的 URL 才带 `wlanuserip`/`wlanacip`，最终页面没有内联配置块。
- 结果：发现器忽略最终 URL 的参数，仍执行 `legacy(userIp, acIp)`，使用首跳传入的空值或旧值，导致 PORTAL_IP_INVALID 或使用不正确的 IP。
- 后续：Windows 已采用最终 URL 的完整、唯一合法 IP 对，拒绝拼接新旧参数；对应跳转/重复/不完整参数已有本地回归，现场边界见 WOPT-03。

## W4 · P2 · 历史问题：Windows 增强恢复启动失败后停止重试

- 位置：[MainWindow.xaml.cs:295](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/app/ESurfingDialerLite/MainWindow.xaml.cs#L295)、[DialerCoreProcess.cs:58](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/app/ESurfingDialerLite/DialerCoreProcess.cs#L58)、[MainWindow.xaml.cs:219](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/app/ESurfingDialerLite/MainWindow.xaml.cs#L219)。
- 触发：健康监督决定重启，已停止旧核心，但启动新 Java 进程时发生一次暂时错误。
- 结果：StartAsync 的 catch 把 `_requested` 置为 false；健康轮询只有同时 requested/running 才触发增强恢复，后续不再进入重试。日志虽写“将按退避策略重试”，该路径没有安排后续任务。进程自身崩溃的 HandleExitAsync 重试是另一条路径，不能覆盖这个启动异常。
- 后续：Windows 已保留连接意图并统一可取消的恢复入口，本地验证启动失败后重试、停止/切账户取消和上限；现场边界见 WOPT-03。

## 当前验收边界

Docker 首轮现场成功已记录，持续心跳、自然掉线/计划认证、资源趋势及停止注销仍待验收；Windows 的现场 Algo-ID/算法、登录和长期资源仍未确认。这些缺口不能仅凭代码审查列为已确认算法 bug，也不能按 Docker 结果关闭 Windows 验收。

09-28 当轮未修改产品代码、未运行认证/部署、未提交或发布，合成 Compose config 夹具仅留在 Docker 忽略的 build 目录。后续修复、交付和真实反馈分别见开头链接；历史 beta2 与原 Windows v2.3beta 包不会因源码修复自动更新。
