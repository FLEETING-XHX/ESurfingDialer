# 开发交接记录：zcode / Codex

最后更新：2026-09-26。这里记录当前执行状态；工程说明见 [PROJECT-GUIDE.md](PROJECT-GUIDE.md)，长期方向见 [ROADMAP.md](ROADMAP.md)。

## 当前基线与约束

| 项目 | 状态 |
| --- | --- |
| 共同开发目录 | `C:\Users\FLEETING\Documents\Project\ESurfingDialer-Docker` |
| 本次查看的分支与提交 | `main`；REF-01 修复检查点，具体标识用 `git log -1` 查看（接手时必须重新核对） |
| 工作区 | 已完成的 Windows 2.3 修复与文档纳入本地代码检查点；参考 C 附件保留在本机，仍不是正式发布基线 |
| Windows | 2.3 开发源码；2.2 已发布且有认证失败反馈 |
| Docker | v1.3，本轮未改认证实现 |
| 打包与发布 | 用户要求先不打包，可能还有其他变更；未生成新安装包、未发布 |
| 当前进行中的文件认领 | 无；REF-01 已完成本地修复与验证，现场算法兼容仍等待证据 |

## 已完成任务

REF-01 已完成的文件范围：Windows 核心 `Client.kt`、`PortalConfiguration.kt`、`NetworkConnectivity.kt`、`DialerApp.kt`、`NetClient.kt`、`HealthStatus.kt`、新增协议响应解析文件及认证回归；WPF `HealthSnapshot.cs`、`EnhancedConnectionPolicy.cs`、客户端回归；根 README 和 `项目文档/`。保留现有 2.3 未提交改动，不编辑参考 C 源码，不改 Docker 协议、不打包。验证采用离线核心回归与客户端回归。

| 编号 | 执行工具 | 内容 | 验证与限制 |
| --- | --- | --- | --- |
| AUTH-01 | Codex | 初始化脱敏诊断与响应读取上限 | 本机模拟 HTTP、异常/截断输入检查通过 |
| AUTH-02 | Codex | 无效原生句柄不再调用 `aid(0)`，有效句柄初始化失败释放 | 可注入操作检查通过，未证明现场原生算法已支持 |
| AUTH-03 | Codex | 动态门户 auth/ticket 发现及明确旧入口回退 | CDATA、参数、跳转、错误配置等本地检查通过 |
| AUTH-04 | Codex | 失败状态展示、有限确定性重试与监督层边界 | 客户端、恢复、手动停止与成功清理检查通过 |
| DOC-01 | Codex | 规划/修复/问题/历史说明集中到 `项目文档/`；增加共用开发说明和规则 | 当时七份文件归档检查通过；没有打包 |
| REF-01 | Codex | C 附件对照；严格响应与请求转义、无 IP 门户发现、会话 IP 隔离、完整失败预算及阶段监督 | 87 项认证、21 项恢复、90 项客户端检查通过；新增对照文档，当前八份开发文档 |

认证修复实际文件包括 Windows 源码内的 `Client.kt`、`DialerApp.kt`、`States.kt`、`HealthStatus.kt`、`Session.kt`、`NetClient.kt`、`NetworkConnectivity.kt`、新增 `AuthenticationDiagnostics.kt` 与 `PortalConfiguration.kt`；客户端的 `HealthSnapshot.cs`、`EnhancedConnectionPolicy.cs`、`MainWindow.xaml.cs`；认证/客户端回归程序、Gradle 任务、核心构建脚本及 2.3 版本元数据。接手时以 `git diff` 和未跟踪文件为准。

文档整理期间发现用户另放入未跟踪的 `ESurfingClient-CVersion-2.1.1-r4(另一个Github相关开源项目源码)` 目录；已保留原样，不属于 DOC-01 新建或认证修复自动引入的文件。

## 最近验证

- Windows 核心：`Build-Core.ps1 -Offline -Regression` 成功，87 项认证检查和 21 项恢复检查通过。
- Windows 客户端：`dotnet run --project tests/ClientRegression/ClientRegression.csproj --no-restore -- artifacts/reference-repair-ui --core` 成功，最终 90 项检查通过。
- 本轮 WPF 截图位于 Windows 源码的 `artifacts/reference-repair-ui`；此前截图保留在 `artifacts/auth-repair-ui`；属于本地验证产物，不作为发布包。
- 本轮文档索引和本地链接检查通过：59 个本地链接、零失效；当前 8 份 Markdown 开发文档。Git diff 空白检查通过。
- 真实校园网算法、会话、ticket、账号登录及心跳仍未验证。不得写成根因已经解决。

## 下一步与所需证据

1. 按用户新的需求认领任务；当前没有固定分派给 zcode 或 Codex 的待执行代码任务。
2. 认证计划第 5 项仍待现场证据：需要脱敏的响应格式、长度、Algo-ID、请求入口主机/端口和同网认证结果。
3. 用可验证测试向量确定算法提供者，核对代码来源与许可，再实现兼容；未知 Algo-ID 不凭名称猜测。
4. 进行真实校园网会话、ticket、登录、心跳和恢复验证，再按用户后续决定安排打包。

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
