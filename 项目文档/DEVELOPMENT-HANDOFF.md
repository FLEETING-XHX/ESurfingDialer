# 开发交接记录：zcode / Codex

最后更新：2026-09-26。这里记录当前执行状态；工程说明见 [PROJECT-GUIDE.md](PROJECT-GUIDE.md)，长期方向见 [ROADMAP.md](ROADMAP.md)。

## 当前基线与约束

| 项目 | 状态 |
| --- | --- |
| 共同开发目录 | `C:\Users\FLEETING\Documents\Project\ESurfingDialer-Docker` |
| 本次查看的分支与提交 | `main`，检查点 `c99dce5`；接手时重新核对 Git |
| 工作区 | REF-01 检查点 c99dce5；REC-02 与 REL-23B 按用户授权提交同步，参考 C 附件保留本机；发布提交以 v2.3beta 标签为准 |
| Windows | v2.3beta 预发布测试包；v2.2 有认证失败反馈 |
| Docker | v1.3，本轮未改认证实现 |
| 打包与发布 | 用户已授权；v2.3beta 安装包已生成，GitHub 发布结果见 REL-23B 交接 |
| 当前进行中的文件认领 | REL-23B：Codex 提交/推送与 GitHub 预发布；现场算法/实际校园网验证仍待证据 |

## 已完成任务

REF-01 已完成的文件范围：Windows 核心 `Client.kt`、`PortalConfiguration.kt`、`NetworkConnectivity.kt`、`DialerApp.kt`、`NetClient.kt`、`HealthStatus.kt`、新增协议响应解析文件及认证回归；WPF `HealthSnapshot.cs`、`EnhancedConnectionPolicy.cs`、客户端回归；根 README 和 `项目文档/`。保留现有 2.3 未提交改动，不编辑参考 C 源码，不改 Docker 协议、不打包。验证采用离线核心回归与客户端回归。

| 编号 | 执行工具 | 内容 | 验证与限制 |
| --- | --- | --- | --- |
| AUTH-01 | Codex | 初始化脱敏诊断与响应读取上限 | 本机模拟 HTTP、异常/截断输入检查通过 |
| AUTH-02 | Codex | 无效原生句柄不再调用 `aid(0)`，有效句柄初始化失败释放 | 可注入操作检查通过，未证明现场原生算法已支持 |
| AUTH-03 | Codex | 动态门户 auth/ticket 发现及明确旧入口回退 | CDATA、参数、跳转、错误配置等本地检查通过 |
| AUTH-04 | Codex | 失败状态展示、有限确定性重试与监督层边界 | 客户端、恢复、手动停止与成功清理检查通过 |
| DOC-01 | Codex | 规划/修复/问题/历史说明集中到 `项目文档/`；增加共用开发说明和规则 | 当时七份文件归档检查通过；没有打包 |
| REF-01 | Codex | C 附件对照；严格响应与请求转义、无 IP 门户发现、会话 IP 隔离、完整失败预算及阶段监督 | 87 项认证、21 项恢复、90 项客户端检查通过；新增对照文档，当时八份开发文档 |
| REC-02 | Codex | 切网/缺 IP 门户恢复、请求版本保留、唤醒重查、恢复延时取消、原生会话生命周期和有界格式诊断 | 109 项认证、46 项恢复、103 项客户端检查通过；当前九份开发文档；实际切网/休眠与算法兼容待现场验证 |

认证修复实际文件包括 Windows 源码内的 `Client.kt`、`DialerApp.kt`、`States.kt`、`HealthStatus.kt`、`Session.kt`、`NetClient.kt`、`NetworkConnectivity.kt`、新增 `AuthenticationDiagnostics.kt` 与 `PortalConfiguration.kt`；客户端的 `HealthSnapshot.cs`、`EnhancedConnectionPolicy.cs`、`MainWindow.xaml.cs`；认证/客户端回归程序、Gradle 任务、核心构建脚本及 2.3 版本元数据。接手时以 `git diff` 和未跟踪文件为准。

文档整理期间发现用户另放入未跟踪的 `ESurfingClient-CVersion-2.1.1-r4(另一个Github相关开源项目源码)` 目录；已保留原样，不属于 DOC-01 新建或认证修复自动引入的文件。

## 最近验证

- Windows 核心：`Build-Core.ps1 -Offline -Regression` 成功，109 项认证检查和 46 项恢复检查通过。
- Windows 客户端：`dotnet run --project tests/ClientRegression/ClientRegression.csproj --no-restore -- artifacts/recovery-repair-ui --core` 成功，最终 103 项检查通过。
- 本轮 WPF 截图位于 Windows 源码的 `artifacts/recovery-repair-ui`；此前截图保留在 `artifacts/reference-repair-ui` 和 `artifacts/auth-repair-ui`；属于本地验证产物，不作为发布包。
- 本轮文档索引和本地链接检查通过：64 个本地链接零失效；当前 9 份 Markdown 开发文档。Git diff 空白检查通过。
- 真实校园网算法、会话、ticket、账号登录及心跳仍未验证。不得写成根因已经解决。

## 下一步与所需证据

1. 按用户新的需求认领任务；当前没有固定分派给 zcode 或 Codex 的待执行代码任务。
2. 认证计划第 5 项仍待现场证据：需要脱敏的响应格式、长度、Algo-ID、请求入口主机/端口和同网认证结果。
3. 用可验证测试向量确定算法提供者，核对代码来源与许可，再实现兼容；未知 Algo-ID 不凭名称猜测。
4. 使用 v2.3beta 进行真实校园网会话、ticket、登录、心跳和恢复验证，根据证据修复后再安排正式版。

## 新任务认领模板

开始任务时填写或替换下面的表格；另一工具接手前先核对。

| 字段 | 填写内容 |
| --- | --- |
| 任务编号 / 用户需求 | 待认领 |
| 执行工具 | zcode / Codex |
| 状态 | 待开始 / 进行中 / 待验证 / 已完成 / 等待证据 |
| 文件范围 | 明确相对路径；涉及共同文件时协调顺序 |
| 起始分支 / 提交 | 实际 Git 状态 |
| 已有未提交改动 | 阅读 diff 后记录需保留的内容 |
| 验证方式 | 本任务必要命令或手工步骤 |
| 交接结果 | 修改内容、结果、未完成项及下一步 |

## 后续交接条目模板

```markdown
### YYYY-MM-DD · 任务编号 · zcode/Codex

- 用户目标：
- 改动文件与结果：
- 验证命令及工作目录：
- 验证结果与未验证范围：
- 未提交改动 / 提交标识：
- 遗留问题与下一步：
- 打包/发布状态：
```

## 2026-09-26 · REF-01 · Codex 完成交接

- 用户目标：继续修复，使用用户提供的 C 开源源码做对照，寻找其他可落地改进；暂不打包。
- 已完成：新增 `AuthenticationProtocol.kt`；必需 XML 字段/间隔/URL 校验，禁用外部实体与 DTD；动态请求字段转义；加密响应 1 MiB、解密 XML 64 Ki 字符上限；缺 IP 门户跳转保留与最终配置发现；活动会话使用自己的 IP；完整认证失败使用有限确定性预算；健康文件与 WPF 监督增加最多 90 秒的认证阶段宽限。
- 复核修正：确认请求的临时失败仍刷新门户给下一次尝试，不修改当前会话参数；HTTP/传输错误使用 ticket/login/heartbeat 分阶段错误码。预算仅在完整确认成功后重置。
- 核心验证：项目根调用 Windows 源码目录的 `scripts/Build-Core.ps1 -Offline -Regression`；最终离线构建成功，87 项认证和 21 项恢复检查通过。首次编译发现门户内联分支变量写错，已修正并在最终构建通过。
- 客户端验证：在 Windows 源码目录执行 `dotnet run --project tests/ClientRegression/ClientRegression.csproj --no-restore -- artifacts/reference-repair-ui --core`，90 项检查通过。源码变动以核心回归、WPF/策略/进程验证分别覆盖。
- 文档：新增 [C 项目对照与修复](C-REFERENCE-REVIEW.md)，同步根 README、工程 README、项目说明、修复计划和路线图；已有问题记录保留为历史证据。
- 限制：没有运行参考 C 程序，没有真实校园网测试。动态 ZSM、Android 新算法与通道兼容仍需现场格式、Algo-ID 和固定向量；不得写成 v2.2 认证故障已全面解决。
- 工作区：保留先前 2.3 未提交修复及参考源码；仍在 main，没有新提交、推送、安装包或发布。构建 JAR、回归截图和日志仅用于本地验证。
- 接手：zcode/Codex 从本目录和当前 diff 继续；算法提供者任务仍按 AUTH 计划第 5 项进行，确认需要的格式和算法后再实现。

## 2026-09-26 · 用户要求：修复前提交检查点

- 用户明确要求在继续修复前先 commit。此次本地提交包含已有 Windows 2.3 认证修复、回归程序、版本元数据、根入口和集中开发文档，以及 Docker README 的文档索引。
- 已核对 Git 文件范围与空白检查；使用上一轮已通过的 87 项认证、21 项恢复和 90 项客户端检查作为本检查点验证记录。
- 未纳入用户提供的外部 C 源码目录及忽略的构建/测试产物；附件保留原样。没有推送、打包或发布。
- 后续修复从此检查点继续，单独认领文件、验证并交接；本条对应提交可通过 Git 历史查看。

## 2026-09-26 · REC-02 · Codex 已完成本地修复

- 用户授权：继续按照未完成计划修复，且先提交此前代码；前置检查点已创建为 `c99dce5`。
- 认领文件：Windows 核心 `AuthenticationDiagnostics.kt`、原生 Session 边界、`RecoveryPolicy.kt`、`CoreSignals.kt`、`States.kt`、`Client.kt`、`DialerApp.kt`、相关回归；WPF `DialerCoreProcess.cs`、`EnhancedConnectionPolicy.cs`、`MainWindow.xaml.cs`、客户端回归；根 README、本目录计划、说明、路线图和交接记录。
- 范围：处理已登录后的无 IP 门户恢复、明确网络切换的防抖与冷却边界、恢复请求丢失、休眠恢复重新检测、原生会话并发释放和有界格式诊断。继续保留原生算法路线，不猜测新算法或密钥。
- 验证：离线核心认证/恢复回归、客户端与真实 Java 子进程回归；参考 C 文件和 Docker 协议不修改，不打包、不推送。后续修复暂不自动提交，便于审阅。

- 完成文件：上述认领范围及新增 `NativeSessionHandle.kt`、`ZsmHeaderReader.kt`、`RECOVERY-REPAIR-REPORT.md`；同期文档索引、项目说明、计划和路线图已更新。
- 核心验证：Windows 源码目录 `scripts/Build-Core.ps1 -Offline -Regression` 最终构建通过，109 项认证、46 项恢复检查通过。使用本机 HTTP 与原生操作替身，没有实际算法/密钥验证。
- 客户端验证：`dotnet build app/ESurfingDialerLite/ESurfingDialerLite.csproj --no-restore` 编译 0 警告、0 错误；`dotnet run --project tests/ClientRegression/ClientRegression.csproj --no-restore -- artifacts/recovery-repair-ui --core` 最终 103 项检查通过，包含恢复计时取消、保守模式下唤醒重查、进程未重启、手动停止和 WPF 回调窗口。
- 资源结果：单候选有界门户计数、原生句柄只释放一次、停止后立即取消待恢复延时、退出解除静态事件订阅；常态轮询频率不增加。尚未测量真实认证下长时间 CPU/内存/日志增长。
- 现场限制：仅模拟恢复回调，没有实际系统休眠，没有校园网真实登录。UA/格式/算法提供者、实际切网与长期运行仍待现场数据；第 5 项保持未完成。
- Git/交付：修复前检查点 c99dce5 保留；REC-02 后续改动未提交。没有推送、修改参考 C/Docker 协议或生成安装包。

## 2026-09-26 · REL-23B · Codex 进行中

- 用户明确授权：打包 v2.3beta、同步提交并推送代码、上传 GitHub；README 划掉旧 Windows 下载入口，增加红色 Warning/警告图标，只引导 Releases。此授权替代此前暂不打包要求。
- 文件认领：根 README、assets/windows-warning.svg；Windows 工程版本元数据、installer、Build-Installer.ps1、resources/README_TEST.txt；项目说明、协作规则、路线图、修复计划和本交接记录。保留并提交 REC-02 已完成修复；外部 C 参考源码不纳入。
- 验证：安装包构建、包内容与版本核对、隔离目录启动检查；检查 GitHub 预发布、附件 SHA256 和 main 提交同步。校园网认证、实际切网/休眠和长期运行仍待现场测试。

### REL-23B 构建与发布前验证

- 安装包：`ESurfingDialer-Windows-Client/00_最终安装包/Windows客户端_v2.3beta/ESurfingDialer-Windows-v2.3beta-Setup.exe`，157736890 字节，ProductVersion=2.3beta，FileVersion=2.3.0.0。
- SHA256：`4d2182a6c48ca40d6007f652e38f4c6109402f2f01c210021c0a99d394082dba`；旁边提供 SHA256SUMS.txt 和 README_TEST.txt。EXE 留在本机并上传 Releases，不加入 Git。
- `scripts/Build-Installer.ps1` 成功：核心 shadowJar、本机 Java 21 运行时、win-x64 自包含发布与 Inno Setup 编译完成。发布脚本增加 .NET publish 失败检查，避免失败后继续打包。
- Release 客户端回归编译 0 警告、0 错误；使用包内的客户端 DLL、client.jar 和 Java runtime 执行回归，102 项检查通过。该次日志导出时未包含可选 health 快照，检查数较此前 103 少 1，未出现失败。
- 生成的 EXE 使用 ESURFING_CLIENT_HOME 指定独立空配置目录启动，4 秒后仍存活且写入启动日志；仅结束本次测试进程。没有覆盖已安装客户端或用户配置；未验证安装向导、升级和卸载。
- README 保留纵向 Windows/Docker 布局；旧 Windows 下载入口划线并移除安装包直连；本地 SVG 显示红色 Warning，增加 ⚠️，引导 v2.3beta Releases 页面。
- 版本：客户端 NuGet Version=2.3.0-beta，InformationalVersion=2.3beta；安装器 AppVersion=2.3beta、数字文件版本2.3.0.0；原安装 AppId 保留。
- 现场认证、算法兼容、实际切网/休眠及长期运行仍未验证；beta 不表示认证根因已修复。
