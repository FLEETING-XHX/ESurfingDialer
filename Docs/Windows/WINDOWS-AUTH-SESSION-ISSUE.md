# Windows 客户端认证会话初始化失败问题记录

记录日期：2026-09-23

当前状态（2026-10-09）：UA2104版本已完成本次真实初始化、原生会话、票据、用户登录和首次确认心跳，用户确认能访问外网且日志正常。当前认证阻断已解除；后续定时心跳、恢复和长期稳定仍待验收。历史v2.1/v2.2原生失败的唯一根因保持未知，不能用新版本成功改写旧结论。依据见[通道修复与现场凭据](WINDOWS-BOOTSTRAP-UA-FIX-2026-10-08.md)，当前待办见[总记录](../修复与优化总记录.md#windows)。

本次日志中观察到的版本：Windows 客户端 v2.1、v2.2

后续核查（2026-10-08）：用户确认本地 v2.3beta.2 仍不能使用，并补充新日志，五次均在 `PORTAL_CONFIG_XML_INVALID` 处失败，未进入bootstrap或原生会话。该版本包内也已复现 Docker 此次修复的两个门户兼容阻断，见[重新核查](../修复与优化总记录.md#windows)；具体本次XML语法/setup未确认。这些问题位于原生初始化之前，不能解释本文旧日志的 `load()=0`。本文的算法/通道假设继续保持未确认，不能把旧失败阶段直接套到当前版本。

同日后续已完成[门户兼容与XML诊断源码修复](../修复与优化总记录.md#windows)，并按授权纳入[本地beta3测试包](../修复与优化总记录.md#windows)，319/63/188项与实际EXE就绪/清理通过。旧beta2包保持原状，尚未进行真实认证；没有修改本文历史原生路线或算法结论。

## 2026-10-08 beta3 新现场：初始化响应为空（当时仅排查，后续已修复）

用户提供 `ESurfingDialer-logs-20261008-191124.zip`，导出版本为 `2.3.0.3`。按最新要求仅查找问题，不修改源码或重新打包；版本字段不能代替实际安装文件摘要核验。

- 19:10:53–19:11:21 两次核心启动共五次尝试，均出现 `AUTH_ENDPOINTS source=portal`；本次已越过之前的门户XML阻断，没有 `PORTAL_CONFIG_XML_INVALID`。
- 五次初始化请求均为 `TICKET_RESPONSE http=200 contentType=text/html declaredLength=-1`，随后 `TICKET_BODY receivedLength=0`、`LOGIN_FAILED code=ZSM_EMPTY`。第一会话第三次后暂停，手动停止/重开后的两次退避；健康状态仍未认证，登录/心跳成功时间为0。
- Windows `Client.initSession()` 先调用 `requestSessionBootstrap(ticketUrl, States.algoId)`，再检查字节，之后才创建原生会话。`ZsmHeaderReader.inspect` 在空字节时直接抛出该错误；日志没有 `ZSM_METADATA`、`SESSION_READY` 或后续成功标志。因此本次未到后续原生会话创建和账号认证，不能套用历史 `load()=0` 根因，也没有密码被拒绝的证据。
- `TICKET_RESPONSE` 在这里表示会话初始化下载，尚未执行加密的业务票据请求。HTTP 200只表示HTTP成功状态；`text/html` 是响应类型声明，正文为空不能称为已取得HTML错误页；`declaredLength=-1` 表示长度未知，实际零字节由读取结果确认。
- 局部源码对照：两端初始化均POST门户ticket入口，正文为全零Algo-ID，正文MD5校验、Client-ID/Algo-ID头、Accept/Content-Type及关闭重定向策略一致；未找到可以由本日志证实的正文构造或读取缺陷。Windows UA为 `CCTP/android64_vpn/2085`，Docker为 `CCTP/Android8_vpn/2082`，两端门户节点常量也不同；这些是待验证差异，不能直接判作根因或据此互换原生库。当前日志来源为portal，不能直接认定使用了固定回退节点。
- 尚缺证据：实际初始化请求的路径/查询结构与同网Docker成功请求的对照，以及空响应由服务端还是中间网络设备产生的依据。现有endpoint日志仅保留origin。下一步优先核查通道与入口请求的匹配；不猜Algo-ID或密钥，不将本次定位写成认证已修复。

后续用户授权修复：同一初始化入口/测试身份/正文/校验头，仅改UA，2085/2093为空，Docker2082与ARM64的2104为非空。已将Windows统一UA改为 `CCTP/android11_64/2104`，原库保持；327/63项通过，新JAR完成无账号测试身份的真实初始化、原生会话及加密票据响应解析。这补足上述空响应根因证据，未执行用户账号登录；独立beta3修复包及验收边界以[新记录](WINDOWS-BOOTSTRAP-UA-FIX-2026-10-08.md)为准。

## 2026-10-09：真实认证和外网通过

`ESurfingDialer-logs-20261009-155457.zip`导出版本2.3.0.3，使用2104通道，初始化响应为200/二进制、实际17215字节，随后SESSION_READY、TICKET_READY、LOGIN_CONFIRMED attempt=1和LOGIN_SUCCESS齐全。15:51:40登录成功，15:54:25用户主动停止，未见登录/心跳失败或异常恢复。用户同时确认可访问外网、日志正确。

核心样本2分48秒，登录后约2分45秒，只有首次确认心跳；最后健康时间与停止一致，进程/线程已退出，保留的authenticated=true不表示停止后仍在线。本次通过范围及日志摘要见[现场凭据](WINDOWS-BOOTSTRAP-UA-FIX-2026-10-08.md#site-20261009)。

## 问题概述（历史 v2.1/v2.2）

部分 Windows 客户端在检测到校园网认证门户后，会长期显示“连接中”，但认证核心实际上没有完成登录。

日志表明，客户端能够启动认证核心、识别用户 IP 和 AC IP，并取得 ticket 接口响应；失败发生在原生认证组件解析响应、创建加密会话的阶段。会话未能有效建立，流程停止，后续的票据获取、账号密码提交和登录确认均不会执行。

增强连接功能能够检测到认证长期未确认，并按退避策略重启认证核心，但重启后仍在相同阶段失败，因此它只能触发恢复，不能解决会话初始化失败。

## 日志证据

本次日志包中的 `settings.json` 记录客户端版本为 `2.2.0.0`，可以确认测试者已经更新到 v2.2。

2026-09-23 的 v2.2 日志反复出现以下顺序：

```text
LOGIN_ATTEMPT
Initializing Android Mock...
Initializing Session...
LOGIN_FAILED failed to initialize session
```

对应的健康状态为：

```text
authenticated: false
lastLoginSuccessAt: 0
lastHeartbeatSuccessAt: 0
lastError: failed to initialize session
```

2026-09-21 的 v2.1 日志中已经存在相同的 `LOGIN_FAILED failed to initialize session`，因此该现象不是 v2.2 新增的健康检查或增强恢复逻辑造成的。

日志中没有出现 `Session ID`、`Ticket`、`LOGIN_SUCCESS` 或登录确认记录，说明失败发生在账号密码提交之前。现有证据不支持把问题归因于账号或密码错误。

## 代码路径

认证流程首先调用 `initSession()` 请求 ticket 接口，再把响应字节交给 `Session`。`Session` 调用原生 `DaMod.load([B)J` 创建会话；返回值为 `0` 时，客户端记录 `failed to initialize session` 并等待重试。

相关文件：

- [`Client.kt`](../../Products/Windows/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/Client.kt)
- [`Session.kt`](../../Products/Windows/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/hook/Session.kt)
- [`AndroidMock.kt`](../../Products/Windows/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/hook/AndroidMock.kt)
- [`Constants.kt`](../../Products/Windows/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/Constants.kt)

## 反馈发生时与 Docker／旧终端版本的差异

Windows 客户端与 Docker／旧终端版本虽然共用相似的业务流程，但认证兼容层并不相同：

| 项目 | Docker／旧终端路线 | Windows 客户端路线 |
| --- | --- | --- |
| User-Agent | `CCTP/Android8_vpn/2082` | `CCTP/android64_vpn/2085` |
| Portal Node | `125.88.59.131:10002` | `61.140.12.23:10002` |
| 模拟架构 | 32 位 | 64 位 |
| Android Resolver | API 19 | API 23 |
| 原生兼容处理 | 包含针对 `ipv4` 的 `strcmp` Hook | 未包含该 Hook |
| `libdaproxy.so` | 32 位路线使用的二进制 | 与 Docker 文件内容及 SHA-256 均不同 |
| Java 运行时 | Java 17 | Java 21 |

这些差异都位于或影响当前失败的会话初始化阶段。因此，“Docker 可以正常认证”不能证明 Windows 客户端的原生会话实现也能正确处理同一校园网返回的数据。

## 反馈发生时与同源 C 项目的静态对照

参考 2026-09-23 收到的 `ESurfingClient-CVersion-2.1.1-r4.zip`。该项目的 README 说明它从 Rsplwe 的 Kotlin 源码改写而来，可在多种操作系统上运行；“支持 Windows 平台”不等于实现了独立 Windows 认证通道：其通道 1 明确回退到 Android 通道。本节仅使用附件源码和更新日志作为线索，未在故障校园网验证其认证结果。

| 环节 | 当前 Windows 客户端 | C 项目附件中的实现 |
| --- | --- | --- |
| 协议标识 | 固定 `CCTP/android64_vpn/2085` | Android 通道使用 `CCTP/android11_64/2104`；源码另保留旧标识 `/2093` |
| 认证入口 | 固定 `BASE_URL`、`PORTAL_NODE`，本地拼出 ticket URL | 从当前认证门户配置提取 `auth-url`、`ticket-url`，再取用户 IP 和 AC IP |
| ZSM 处理 | 响应交给 64 位 `libdaproxy.so` 的 `DaMod.load([B)J`；失败时只得到无效会话 | 先识别动态 ZSM 或提取 Algo-ID，再选对应算法；未知算法有单独错误 |
| 算法覆盖 | 取决于随包附带的原生库，日志无法判断支持范围 | 工厂列出 Linux 和新 Android 算法，iOS/macOS 动态 ZSM 另有处理分支 |
| 诊断信息 | 没有 ticket 响应长度、格式、Algo-ID、原生失败类别 | 记录通道、ZSM 长度、Algo-ID 和解包失败原因 |

附件 `UpdateLogs.md` 记载：2026-08-31 因“算法更迭”临时关闭 phone 通道，2026-09-01 加入新 Android 算法。这与“三个月前可以使用、近期 Windows 客户端持续初始化失败”的时间顺序相容，但不能代替失败现场的 Algo-ID 证据。附件的 `DialerClient.c`、`CipherFactory.c` 和 `PlatformUtils.c` 可作为后续设计参考；若复用具体实现，需核对其 Apache-2.0 许可和第三方代码来源。

## 当前判断与证据强度

**已确认：** Windows 客户端取得 ticket 接口响应后，未能建立有效加密会话；现有健康状态和登录日志均证实认证没有成功。v2.1 和 v2.2 都出现同样的失败，更新恢复逻辑不能改变这一阶段的结果。

**优先验证的假设：**

1. 高优先级：服务端下发了现有 64 位原生库无法识别的 ZSM 格式或 Algo-ID。C 项目近期新增 Android 算法，构成旁证；尚未取得本次失败响应的 Algo-ID，不能指定是哪一种算法。
2. 高优先级：固定的旧 Android User-Agent 和固定门户节点使 Windows 请求走到与当前学校不匹配的服务端路径，或者取得与原生库不匹配的 ZSM。
3. 次优先级：64 位 `libdaproxy.so` 或 Unidbg 环境自身的兼容性问题，包括旧 32 位路线有而 Windows 路线缺少的 `ipv4` Hook。仅凭两份实现的差异，不能证明该 Hook 是根因。

目前不能从现有日志确定唯一根因：没有 ticket 响应的 HTTP 状态、`Content-Type`、长度、受控算法标识和安全摘要，也没有同一网络环境下 Docker 与 Windows 的对照结果。Docker 使用另一套 32 位库、UA 和门户节点，因此 Docker 可用也不能排除 Windows 路线的协议兼容问题。

## 已排除或暂不支持的方向

- 不是单纯的 UI 状态显示问题：认证核心确实未完成登录。
- 不是测试者仍在运行 v2.1：导出配置明确记录为 v2.2.0.0。
- 暂无证据表明是账号或密码错误：失败发生在提交账号密码之前。
- 不是增强连接恢复本身导致：v2.1 已存在同一会话初始化失败，恢复重启只是重复触发该失败。

## 后续诊断顺序

先扩充低风险诊断，再逐项改变协议变量；不要直接把附件的算法或 UA 全部替换进正式版。

1. 记录客户端版本、通道／User-Agent、认证地址的主机与端口、ticket HTTP 状态、`Content-Type` 和响应长度；不得记录完整 URL、响应正文、账号、密码、密钥或票据。仅在受控本地对照需要时计算响应 SHA-256；确认其跨用户关联风险之前，不放入公开导出日志。
2. 在严格校验长度与格式的前提下，仅从 ZSM 头部提取格式类别和 Algo-ID；解析失败时给出明确阶段错误，不输出原始 ZSM。记录原生 `load()` 的返回类别，不把进程内会话句柄当成可公开日志字段。
3. 同一校园网、同一时间窗口，对照 Windows、现有 Docker／旧终端和对方 C 项目的请求目标、UA、响应格式／Algo-ID 与认证结果。对方程序需由测试者自行在可信环境运行；本记录没有运行附件。
4. 若确认 Algo-ID 不受支持，先选定一条兼容路线并用脱敏样本验证；若响应并非预期 ZSM，先核对门户发现和请求参数；若格式与算法均一致，再检查 64 位原生库、模拟环境及 Hook。

确认标准：同网对照能指出失败位于门户发现、ticket 请求、ZSM 解析、算法选择或原生执行中的哪一步；修复后必须看到有效会话、票据获取、登录提交与登录后心跳确认，且在断网、重试和恢复时不会无限停留在“连接中”。具体开发顺序见 [未来开发方向](../修复与优化总记录.md)。
