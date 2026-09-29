# Docker beta 与 Windows 客户端剩余问题审查

日期：2026-09-28。范围：Docker `codex/docker-beta` / `4f10b0b` 加当前未提交的门户修复；Windows v2.3beta 对应本地源码。现有 beta2 部署 ZIP 尚未包含本次门户修复。

2026-09-29 更新：S1 的 Docker 部分、D1、D2 已完成源码修改，见 [Docker 后续修复记录](DOCKER-BETA-FOLLOWUP-2026-09-29.md)。本轮只做编译、语法与静态审阅，行为回归和现场验证仍待进行；下面的行号与触发路径保留为修复前审查证据。Windows 的 S1、W1–W4 暂未实施。

本次以静态控制流和现有回归断言为依据，没有修改产品代码，没有运行真实认证。下面列出可从代码推导的具体触发路径，不表示它们都已在用户校园网发生。P1 表示优先处理的认证/恢复问题；P2 表示特定条件下的功能缺陷。

## S1 · P1 · 两端可能把未识别的 HTTP 200 页面当作网络畅通

- 位置：[Docker NetworkConnectivity.kt:58](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/utils/NetworkConnectivity.kt#L58)，[Windows NetworkConnectivity.kt:67](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/utils/NetworkConnectivity.kt#L67)。
- 触发：未认证网络返回状态 200 的登录页、提示页或 JS 跳转页，页面既无已知配置标记，也无当前识别的 IP 关键词。
- 结果：`parsePortalBody` 返回 null，探测函数立刻返回 SUCCESS，后面的探测地址不再访问。无会话的认证循环在 SUCCESS 分支等待，可能一直不开始登录。
- 注意：这里误判的是网络探测；代码没有因此直接将协议认证的 authenticated 标志设为 true。
- 修复方向：为内置探测地址核对预期状态和正文；非预期 200 作为可疑/未知响应继续探测或发现门户，不能直接证明外网可用。
- 必要回归：无 IP 的登录 HTML、JS 跳转、上游错误页、首地址可疑而后地址返回有效门户、真正的预期成功响应。

## D1 · P1 · Docker 已接受的重认证请求可能被覆盖

- 位置：[DialerApp.kt:69](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/DialerApp.kt#L69)、[DialerApp.kt:88](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/DialerApp.kt#L88)、[Client.kt:236](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/src/main/kotlin/com/rsplwe/esurfing/Client.kt#L236)。
- 触发：客户端正等待心跳/认证请求，网络线程达到门户恢复条件，将 `States.networkStatus` 设为门户状态并记录冷却时间。客户端尚未消费它时，下一次成功探测又将同一字段设为 SUCCESS；较早认证结束也会直接写 SUCCESS。
- 结果：请求没有独立的待办标记/版本号，可能被丢弃；冷却时间已经计入，后续门户恢复还可能受默认 300 秒冷却限制。恢复延迟取决于后续心跳和探测结果。
- 修复方向：把网络观测状态与待执行认证请求分开，以请求版本确认消费；较早完成的认证不能清除较新的请求。Windows 的请求版本机制可供设计对照，不能直接覆盖产品实现。
- 必要回归：心跳执行期间请求恢复→随后 SUCCESS；认证 A 执行期间接受请求 B→A 成功；手动停止后不消费/重启。

## D2 · P2 · Docker 安装器未按 .env 语法编码账号密码

- 位置：[install.sh:83](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/install.sh#L83)；已生成 beta2 包中的 install.sh 同样直接写入 `DIALER_PASSWORD=$dialer_password`。beta 打包器沿用这一段。
- 触发：密码包含形如 `$NAME`、`${NAME}` 的内容，或带有会被 .env 语法解释的引号/空白组合。
- 结果：未加保护地写入 .env 后，Compose 会执行变量展开等解析，容器拿到的值可能与输入不同；表现为用户输入正确但认证仍失败。
- 依据：Docker 官方说明未加引号与双引号的 env_file 值会插值，单引号按字面量处理，未定义插值变量会被替换为空。[env_file 语法](https://docs.docker.com/reference/compose-file/services/#env_file)、[插值规则](https://docs.docker.com/reference/compose-file/interpolation/)。
- 验证边界：准备了仅含合成值的 Compose config 夹具，但当前环境找不到 docker CLI，因此没有执行成功；结论基于实际安装脚本与官方语法，不宣称经过本机 Compose 实测。
- 修复方向：按兼容的 .env 格式正确编码和转义；覆盖美元符号、单/双引号、空格、反斜线与注释字符。不能默认要求软路由升级到才支持 raw env_file 的新版 Compose。

## W1 · P1 · Windows 登录后的心跳卡住可能长期显示已连接

- 位置：[HealthSnapshot.cs:80](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/app/ESurfingDialerLite/HealthSnapshot.cs#L80)、[EnhancedConnectionPolicy.cs:45](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/app/ESurfingDialerLite/EnhancedConnectionPolicy.cs#L45)、[Client.kt:121](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/Client.kt#L121)。
- 触发：已成功登录，后续心跳的原生加解密/JNI 调用卡住且没有返回；独立健康线程和网络线程仍在运行。
- 结果：authenticated 和 clientThreadAlive 仍保持 true，健康文件持续刷新。界面只核对 authenticated 与文件时间，增强恢复在 authenticated=true 时直接返回无异常；已登录心跳也没有独立阶段进度期限，因此可能长期显示已连接且不恢复。
- 现有测试：[Program.cs:66](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/tests/ClientRegression/Program.cs#L66) 明确接受 authenticated=true 且心跳过期的快照。它覆盖了当前设计，未覆盖认证线程活着但失去进展的情况。
- 修复方向：对已登录心跳/原生操作增加单调时钟进度监督，结合服务器心跳周期和请求宽限判断；不能把“健康文件有人写”当作“认证线程仍在推进”，也不宜用过短固定阈值制造误恢复。
- 必要回归：健康线程持续刷新但心跳挂起、合法较长心跳间隔、短暂传输失败、手动停止、恢复次数上限。

## W2 · P1 · Windows 仍有旧门户解析与缓存失败路径

- 位置：[PortalConfiguration.kt:40](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/PortalConfiguration.kt#L40)、[PortalConfiguration.kt:65](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/PortalConfiguration.kt#L65)。
- 触发：合法字段带标签空白/属性、混合 CDATA 与文本等旧正则不支持的结构；或缓存的内联配置不完整。
- 结果：返回 PORTAL_CONFIG_INVALID，缓存解析异常还会在重新 GET 前抛出。Windows 的确定性错误预算第三次失败后暂停，用户可能需要手动断开重连。
- 证据边界：确认存在与 Docker 原解析器同类的代码限制，不能据此断定用户这次 Docker 现场页面必然会在 Windows 出现相同结果；两端 UA 与门户返回可能不同。
- 修复方向：独立适配有界结构解析、脱敏诊断与失效缓存重取，保持 Windows 既有原生通道和有限重试策略。

## W3 · P2 · Windows 没有采用最终跳转 URL 中的 IP

- 位置：[PortalConfiguration.kt:79](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/PortalConfiguration.kt#L79)。
- 触发：首跳没有 user/ac IP，后续跳转的 URL 才带 `wlanuserip`/`wlanacip`，最终页面没有内联配置块。
- 结果：发现器忽略最终 URL 的参数，仍执行 `legacy(userIp, acIp)`，使用首跳传入的空值或旧值，导致 PORTAL_IP_INVALID 或使用不正确的 IP。
- 修复方向：在无配置的合法回退路径，优先提取并校验最终 URL 的唯一 IP 参数；拒绝重复/不完整参数，不能把新旧两组 IP 拼在一起。
- 必要回归：首跳空 IP、第二跳 URL 带合法 IP、最终页面无配置；重复或只有单个 IP 的跳转参数。

## W4 · P2 · Windows 增强恢复在重新启动失败后可能停止重试

- 位置：[MainWindow.xaml.cs:295](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/app/ESurfingDialerLite/MainWindow.xaml.cs#L295)、[DialerCoreProcess.cs:58](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/app/ESurfingDialerLite/DialerCoreProcess.cs#L58)、[MainWindow.xaml.cs:219](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/app/ESurfingDialerLite/MainWindow.xaml.cs#L219)。
- 触发：健康监督决定重启，已停止旧核心，但启动新 Java 进程时发生一次暂时错误。
- 结果：StartAsync 的 catch 把 `_requested` 置为 false；健康轮询只有同时 requested/running 才触发增强恢复，后续不再进入重试。日志虽写“将按退避策略重试”，该路径没有安排后续任务。进程自身崩溃的 HandleExitAsync 重试是另一条路径，不能覆盖这个启动异常。
- 修复方向：保留用户连接意图，由统一且可取消的恢复状态机管理启动失败；手动停止必须取消后续动作。
- 必要回归：健康恢复停止成功→第一次启动失败→下一次成功；冷却中手动停止或切账号；达到重试上限。

## 建议处理顺序与现场边界

1. 先修 D1、S1 和 W1，降低掉线后无法自行恢复及假在线的风险。
2. 再处理 Windows 门户解析/最终跳转参数（W2、W3），配合新的现场日志确认认证入口。
3. 补齐安装器密码保真和 Windows 启动失败重试（D2、W4）。

未知现场 Algo-ID、原生算法支持、真实登录和长时间运行资源仍需要证据；这些是验证缺口，不能仅凭代码审查列为已确认的算法 bug。部署包与源码应明确版本，避免把本地编译成功误当成软路由已经更新。

本轮未修改产品代码、未运行认证/部署、未提交或发布。合成 Compose config 夹具只留在 Docker 的忽略 build 目录。
