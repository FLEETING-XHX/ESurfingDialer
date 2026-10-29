# Windows 全项目代码审查与续查断点

更新：2026-10-29。状态：**整体静态审查确认的 WR-001、WR-002、WR-003 均已修复源码并通过离线回归**；包含第一批发现的 WR-001。测试源码按覆盖主题核对，未逐行审计全部测试；第三方二进制、真实校园网、安装升级及长期稳定性不在本次验证结论内。下文缺陷复现为修复前证据，最新实施见文末。

## 审查基线与边界

- 分支：`codex/windows-v2.4.1`，HEAD：`82b72e4c90df90d1f576ccb36711697910210365`。
- 对象：Windows 产品目录的**当前磁盘源码，包括全部未提交及未跟踪的 v2.4.1 文件**，并非只看 HEAD 或本轮 diff。
- 产品根：`Products/Windows`。下文源码路径相对此根。
- 本次仅审查、记录与隔离离线复现；产品源码、Docker 工作树、真实配置、研究资料及旧包未改。没有提交、打包、发布或校园网认证。
- 用户希望在剩余额度内分批推进，因此本次在一个完整批次后停下；没有将“读过一部分”标成全项目通过。既有回归通过不意味着覆盖此次发现的竞态。

## 已确认问题

### WR-001 / P2：每日重登关闭后，仍能入队并启动一次尚未开始的计划认证

定位：`core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/Client.kt:109`（claim 与入队分开）、`DailyReauthPlanner.kt:150`（配置关闭与取消）、`States.kt:80`（开始认证仅校验请求版本）。

触发顺序：

1. 客户端线程完成 `DailyReauthPlanner.claim()`，返回 true，尚未执行 `States.requestAuthorization(..., scheduled = true)`。
2. 控制线程完整执行 `configure(false, ...)`：计划已关闭，取消函数看不到尚未入队的请求，因而无事可取消。
3. 客户端线程恢复，仍将计划请求放入队列。`markAuthorizationStarted` 只判断运行状态与请求版本，允许这个请求开始。
4. 若此时有本核心会话，`Client.authorization` 可进入 terminate，导致用户关闭功能后仍出现一次主动断网重登。

这是**未开始的计划在关闭后开始**，与“已开始认证后继续正常失败恢复”的既定行为不同。v2.4.1 前一轮修复覆盖的是“已经入队的请求被取消后，旧快照不得开始”，未覆盖“claim 已完成但尚未入队”的间隙。

证据：隔离夹具使用现有 v2.4.1 JAR 的真实 `DailyReauthPlanner`、`States` 接口，按以上可达交错顺序执行；没有替换这些接口，也没有调用网络、认证或原生算法。

```text
R1 status=disabled pendingAfterDisable=true startedAfterDisable=true
```

复现保存于本机 `dist/windows-code-review-20261029/ReviewDailyRace.java`，输出为同目录 `daily-race-result.txt`。JAR 摘要见 `checkpoint.json`。这是状态机缺陷复现，不是实机断网复现，也不是并发压力测试。

建议修复方向：为计划配置引入代次或取消令牌，使“配置是否仍有效、计划请求入队、请求开始”具有共同的线性化边界；重新启用、改时间及正常恢复请求也需明确定义。不能只在入队前增加一次无锁的 enabled 判断，它仍留下检查与执行间隙。

应补回归：claim 与入队之间关闭；入队与开始之间关闭；改窗口后旧计划不可开始；已经开始的计划可作为普通恢复继续；关闭不得清除普通门户恢复。修复时保留每日账本防重复语义，避免直接回滚 claim 引入同日重复重登。

## 待进一步确认，暂不列正式缺陷

- **C1，计划请求与普通门户恢复合并时的归属**：`States.requestAuthorization:51–53` 对同一门户只更新 portal，保留原 scheduled 标志。夹具确认先有 scheduled 请求，再 `observePortal`，之后取消 scheduled 会将合并请求清空：`normalPortalStillScheduled=true normalRequestLostOnDisable=true`。但完整运行中后续网络探测可能重新生成请求，实际影响时长与是否属于应保留的独立普通恢复，需要下一批结合网络线程完整验证，不单凭接口顺序定为第二个 P2。
- **C2，崩溃恢复与健康恢复预算是否一致**：`DialerCoreProcess:271` 仍按进程存活超过两分钟重置崩溃次数，而 UI 健康恢复按持续认证健康重置。两条预算职责不同，不能直接认定为错误；下一批检查反复“存活但从未认证成功后退出”场景及文档承诺。
- **C3，退出与认证线程共用会话的生命周期**：shutdown hook 调用 `client.term()`，主认证线程也可能正在 encrypt/free。下一批检查 Session/JNI 的同步和释放实现后再判断，不提前宣称存在原生竞争。

## 全面审查续查结果

本轮先核对第一批 125 个文件的摘要，全部一致。继续检查了自有 Kotlin 核心、C# 客户端、账户事务、日志及密码保护、窗口和资源绑定、维护/构建脚本与安装配置，合计 70 个生产源码/配置/脚本文件。生产范围逐文件状态见本机 `dist/windows-code-review-20261029/review-final.json`；第一批快照保留原样。审查没有修改产品源码。

### WR-002 / P2：工程通配符纳入旧核心，导致发布失败

定位：`app/ESurfingDialerLite/ESurfingDialerLite.csproj:29–31`。Content 使用 `build/libs/*-all.jar`，所有匹配文件都 Link 到 `core/client.jar`，且设置 `CopyToPublishDirectory=PreserveNewest`。

现场同时有 `ESurfingDialer-1.4.0-all.jar` 和 `ESurfingDialer-2.4.1-all.jar`。MSBuild 求值确认两条同名输出。进一步只执行发布文件解析目标（未生成交付包、未运行安装器）：

```powershell
dotnet msbuild app/ESurfingDialerLite/ESurfingDialerLite.csproj -t:ComputeFilesToPublish -p:NoBuild=true -p:Configuration=Debug -getItem:ResolvedFileToPublish -nologo
```

实际退出 1，报 **NETSDK1152：多个具有相同相对路径的发布输出文件**，列出的正是上述两个 JAR。证据：`msbuild-content.json` 与 `publish-resolution.txt`。`Publish-Client.ps1` 在 dotnet publish **成功之后**才手工复制当前版本 JAR，因此不能挽救这个提前发生的失败；单独修改脚本的 JAR 选择仍不够。

普通 `dotnet build --no-restore -o artifacts/review-build-20261029` 为 0 警告/0 错误，实际复制 JAR 的 SHA256 与 2.4.1 一致。**未发现本次已复制旧核心，也不能将发布失败写成旧版本已经交付。**

修复方向：在工程层只纳入与当前核心版本相符的一份 JAR，并在发布前校验存在且唯一；不靠删除用户保留的历史 JAR。回归至少覆盖旧/新 JAR 共存、缺少当前版本和正常唯一版本。优先在下一次打包前修复。

### WR-003 / P2：已有设备身份损坏时静默生成新 ID 并覆盖原文件

定位：`core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/DeviceIdentityStore.kt:14–33,40–51`。现有文件通过正则按字段提取；任何缺失/无效字段都走随机生成分支，随后每次启动直接 `file.writeText` 覆盖，未区分首次启动与既有身份损坏，也没有原子替换或恢复备份。

如果一次写入被中断，例如 MAC 字段已写完而 clientId 字符串尚未结束，下次启动保留 MAC、随机生成新的 clientId，再覆盖损坏文件。用户并未要求换设备身份，程序也没有提示身份重建；这可能影响服务端设备绑定和旧会话恢复。服务端具体影响待现场确认，本次确认的是**本地身份改变和原文件覆盖**。

隔离夹具 `ReviewDeviceIdentity.java` 使用合成损坏文件和现有 JAR 的真实加载接口，输出 `existingDamagedStateOverwritten=true`、`newIdentityGenerated=true`，未抛出配置异常；没有联网或调用原生算法。输出为 `identity-result.txt`。

修复方向：将“文件不存在、首次初始化”与“已有文件无效”分开；无效文件保留、尝试恢复已验证备份或明确报错。写入采用临时文件、落盘及原子替换，验证 clientId 格式并正确编码 JSON。回归覆盖截断、非法 JSON、缺字段、写入失败、有效身份重启保持和显式环境覆盖。

### 三个候选的后续判断

- **C1 保留为边界用例，不另计正式缺陷**：同门户请求会保留 scheduled 标志；网络线程在观察到门户后继续轮询，可重新建立普通请求，尚不能从隔离接口顺序推出无限断线。修 WR-001 时应一起验证请求合并/降级，避免取消正常恢复。
- **C2 保留为策略风险**：崩溃预算确实按进程存活时间重置，健康预算按认证健康重置。当前不能声称二者是同一额度；从未认证却间歇退出的完整场景还需专门验收，不能把它写成已修复或已复现故障。
- **C3 排除直接 double-free/use-after-free，保留整段退出事务风险**：`NativeSessionHandle.use/free` 共用同步锁且 free 幂等，`Session` 的 encrypt/decrypt 均经过它；现有回归也覆盖在途使用与 free。该锁不保护 Client 的 session/ticket/endpoints 整组字段或整个 term 请求；未取得实际原生损坏证据，因此不列正式缺陷。

### 验证与审查边界

- 本轮普通客户端构建 0 警告/0 错误。
- 客户端回归 `dotnet run --project tests/ClientRegression/ClientRegression.csproj --no-restore -- artifacts/review-full-usercontext-20261029 --core`：**356 项通过**，结果和 WPF 渲染在该 artifacts 目录。初次沙箱执行在 DPAPI 处失败，保留失败记录；用户上下文重跑通过，未将首次失败算通过。
- 发布解析：**预期复现失败 NETSDK1152**，属于 WR-002 证据，不是发布验证通过；没有生成安装交付包。
- 每日开关和设备身份为隔离状态复现；未进行真实认证、真实原生算法对比、安装升级卸载、跨 DPI 实机矩阵或长时间压力测试。356 项也不能证明覆盖 WR-001/WR-003，夹具补出了这些现有覆盖空白。
- 自有生产代码已按模块整体审查；字体/图标只检查引用及许可随包路径，未逐字节审计第三方字形或二进制。Gradle 依赖安全公告与第三方库完整审计未做，不能据此宣称没有已知 CVE。
- 安全设计遗留：Java 命令行仍包含 `-u/-p` 明文参数（`DialerCoreProcess:125`），有本机进程命令行读取权限的主体可能看到。DPAPI 只保护落盘密码；日志脱敏不覆盖 OS 进程列表。建议后续用匿名管道发送启动凭据，但不将它声称为本次新增的远程漏洞。

下一步优先修 WR-001 与 WR-002，再修 WR-003 并补回归；用户本轮要求审查，当前三项均保持未修复。修复后再安排真实连接、退出重连和每日跨日验收。

## 用户授权统一修复后的结果（2026-10-29）

上文“待修复/未授权”为审查阶段历史状态。本次用户明确授权统一修复三项，实施保持在 `codex/windows-v2.4.1`，版本仍为 v2.4.1。

| 编号 | 最终实现 | 新回归 |
| --- | --- | --- |
| WR-001 | `DailyReauthCoordinator` 将配置更新与 claim/入队置于 `States` 同一监视锁内；认证开始也用该锁。关闭完成后旧计划无法入队或开始；修改窗口取消未开始的旧计划，重复下发相同配置不取消；已开始的请求转普通恢复，同门户普通恢复可接管计划请求 | 真实双线程加闩锁暂停持久化边界，验证关闭必须等待 claim/入队完成且随后取消；覆盖关闭后请求拒绝、旧快照、改窗口、相同配置、普通恢复、已开始请求及账本不回滚 |
| WR-002 | csproj 从 Gradle 读取核心版本，Content 精确纳入对应 JAR；构建与发布解析前显式验证当前 JAR 存在。历史 JAR 保留，选择回归加入正式/本地构建门禁 | 6 项：新旧共存、历史文件未变、唯一当前版本、缺当前但有旧版、完全缺失、仅当前版本；原失败的 `ComputeFilesToPublish` 已退出 0，唯一输出为 v2.4.1 |
| WR-003 | 完整校验已有身份；临时文件、flush 与原子替换（不支持时使用替换并依赖备份恢复）。首次创建先建备份；有效身份不重复覆盖主文件；损坏主文件可从有效备份恢复并保存 `.corrupt-*` 原件；无有效备份拒绝启动，不随机补字段 | 首次初始化/重启不变、截断/非法 JSON/缺字段、损坏保留、备份恢复、主文件缺失、部分/完整显式环境覆盖、无效标识、备份/主文件写失败及失败后重启 |

设备身份完整环境覆盖仍需同时提供有效的 `DIALER_MAC_ADDRESS` 和 `DIALER_CLIENT_ID` 才能覆盖无法恢复的损坏历史；单字段覆盖只能基于完整有效的主文件或备份。clientId 接受 1～128 位安全 ASCII 标识（字母数字起始，其后可含 `._:-`），兼容既有 UUID 和常见旧标识，拒绝引号、控制字符及超长内容。没有读取或改变真实用户身份文件。

当前验证（Windows 产品目录下）：

- `scripts/Build-Core.ps1 -Offline -Regression`：**327 项认证、126 项恢复通过**，恢复比前版新增 27 项；日志 `artifacts/wr-p2-core-validation-user.log`。首次沙箱 Gradle 原生 DLL 加载失败已记录，用户上下文离线重跑成功。
- 客户端完整回归 `--core`：**356 项通过**，`artifacts/wr-p2-client-user/result.json`，同目录保留 WPF 渲染；日志 `artifacts/wr-p2-client-user.log`。
- JAR 选择：**6 项通过**，`artifacts/wr-p2-jar-selection/result.json`。测试只移动自己的合成 JAR，未删除或改动真实历史 JAR。
- 发布解析：`artifacts/wr-p2-publish-resolution.json`，成功且 `core/client.jar` 唯一对应 `ESurfingDialer-2.4.1-all.jar`；只解析发布输入，没有生成安装器或交付目录。
- PowerShell AST、`git diff --check` 通过。未提交、推送、打包、发布、操作 Docker 或进行真实校园网认证。原反馈电脑、每日跨日、安装升级及长期运行仍待现场验收。

本次仅关闭三个已确认 P2 的源码与离线回归状态，不将 C2/C3 候选或命令行凭据遗留风险自动标为已解决。迁移到 C++ 时继续沿用这些编号和行为用例，不能只逐句翻译原实现。

## 第一批覆盖范围

本批完整阅读并审查以下 14 个生产文件，重点为进程所有权、健康监督、每日计划及相关配置边界；“本批已审”仅表示该视角已审查，不表示无缺陷或不需要后续交叉复核。

| 范围 | 文件 |
| --- | --- |
| C#，7 个 | `app/ESurfingDialerLite/` 下 `DialerCoreProcess.cs`、`CoreProcessJob.cs`、`HealthSnapshot.cs`、`EnhancedConnectionPolicy.cs`、`AppPaths.cs`、`ClientConfig.cs`、`ConfigStore.cs` |
| Kotlin，7 个 | `core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/` 下 `CoreStateOwnership.kt`、`DailyReauthPlanner.kt`、`States.kt`、`DialerApp.kt`、`Client.kt`、`HealthStatus.kt`、`RuntimeConfig.kt` |
| 局部审查 | `MainWindow.xaml.cs:111–181` 设置保存及下发；`:231–430` 启停、健康刷新与恢复预算 |
| 辅助核对，不算已审完整文件 | `utils/NetworkConnectivity.kt:1–100`；`DailyAndOwnershipRegression.kt:1–150`；`ProcessOwnershipChecks.cs:1–130`；相关测试检索 |

未发现新问题的检查点包括：Job 句柄不继承及父退出回收的组合；按启动实例选择健康文件；状态目录文件锁保留至进程退出；无会话外网可达不冒充 authenticated；已取消的旧请求版本不可开始；账本 claim 写失败不启动计划。以上为静态检查结论，不替代实机故障恢复或长时间验收。

## 第一批历史断点：B2-AUTH-NATIVE（本轮已继续完成）

以下保留第一批交接步骤供追溯；当前状态以“全面审查续查结果”和 `review-final.json` 为准，下一步为修复 WR-001/002/003。

下一次从此处继续，无需重读整个仓库或重跑全部回归：

1. 核对分支与 `git status`，运行下列快照检查。若文件摘要变化，先对变化文件重审相关结论；不要 reset、stash 或覆盖既有改动。
2. **下一批优先审查**：`AuthenticationSession.kt`、`NativeSessionHandle.kt`、`hook/Session.kt`、`hook/ESurfingJni.kt`、`hook/AndroidMock.kt`、`network/NetClient.kt`、`network/NetResult.kt`；结合 `Client.kt:328–401` 与 `DialerApp.kt:176–201` 跟踪退出/释放并发，处理 C3。
3. 随后检查 `AuthenticationProtocol.kt`、`AuthenticationDiagnostics.kt`、`ZsmHeaderReader.kt`、`PortalConfiguration.kt`、`PortalXmlDetails.kt`、`utils/NetworkConnectivity.kt`、`RecoveryPolicy.kt`、`CoreSignals.kt`、`DeviceIdentityStore.kt`，验证 C1/C2 与认证/网络超时及重试边界。
4. 后续批次：WPF 剩余事件/账户切换/启动与退出；密码、配置和日志导出；UI/XAML 资源与 DPI；构建、运行时和安装升级脚本；已有测试覆盖空白。第三方二进制只检查来源、调用契约和生命周期，不将没有源码的二进制称为已做源码审查。
5. 每完成一批更新本报告及快照中的覆盖状态，保留旧快照；只运行能回答具体疑问的定向验证。WR-001 当前是审查发现，用户本轮没有要求同时修复。

本机断点文件：`dist/windows-code-review-20261029/checkpoint.json`，包含本次 Windows 文件清单、SHA256、逐文件覆盖状态、已审行段、复现 JAR 身份及下一入口。验证命令：

```powershell
python dist/windows-code-review-20261029/checkpoint.py --check
```

该命令只校验文件快照，不能证明程序正确。夹具重放必须给 `STATE_DIR` 指定新的隔离目录，避免账本复用使 claim 返回 false；不可指向真实用户数据。
