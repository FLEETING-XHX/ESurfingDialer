# ESurfingDialer 项目说明与双工具开发指南

更新日期：2026-09-26。适用工具：zcode、Codex，以及接手本项目的开发者。

## 项目目标与当前状态

ESurfingDialer（天翼校园）是校园网认证项目。同一个 Git 仓库维护 Windows 桌面客户端与 Docker 软路由版本，重点是轻量运行、稳定认证、故障恢复和离线部署。

| 分支产品 | 当前状态 | 开发重点 |
| --- | --- | --- |
| Windows | 已发布 v2.2 有会话初始化失败反馈；当前 v2.3beta 预发布测试版 | 补齐诊断、认证入口兼容、会话与恢复边界；仍需现场算法证据和真实认证验证 |
| Docker | v1.3；项目记录曾连续测试 14 天未断网，本轮未修改认证实现 | 保持稳定、离线部署；以后按明确任务维护 |
| macOS | 尚未实现，列入远期规划 | 等 Windows 与 Docker 的兼容性和稳定性得到验证后再推进 |

2.3 已实现初始化脱敏诊断、无效句柄检查、动态门户入口、严格认证响应校验、会话 IP 隔离、有限协议重试与有时限的阶段监督。C 附件对照结果见 [对照与修复记录](C-REFERENCE-REVIEW.md)。现有证据仍不能确定故障现场需要哪个算法，不能把本地回归通过表述成实际校园网认证已恢复。用户已明确授权 **v2.3beta 打包、提交/推送和 GitHub 预发布**，用于收集现场测试证据。

## 唯一开发入口

本次共同开发以这个目录作为项目根目录：

```text
C:\Users\FLEETING\Documents\Project\ESurfingDialer-Docker
```

目录名虽包含 Docker，但这里实际是 Windows 与 Docker 的合并仓库。旁边的 `C:\Users\FLEETING\Documents\Project\ESurfingDialer-Windows-Client` 是另一份目录，不作为本轮改动的同步目标；两边工具都应打开上面的合并仓库，避免修改了不同副本。

根目录另有用户放入的 `ESurfingClient-CVersion-2.1.1-r4(另一个Github相关开源项目源码)` 参考目录。它属于外部参考源码，不是当前客户端的构建入口；若后续复用其中实现，先核对来源、许可及测试向量，不直接覆盖现有核心。

```text
ESurfingDialer-Docker/                  ← 当前 Git 仓库根目录
├─ README.md                           用户入口、下载与文档索引
├─ AGENTS.md                            Codex 项目规则入口
├─ 项目文档/
│  ├─ PROJECT-GUIDE.md                  本文：架构、源码路径、构建和协作方式
│  ├─ 协作规则.md                       zcode/Codex 共用工作规则
│  ├─ DEVELOPMENT-HANDOFF.md             当前任务、验证与交接记录
│  ├─ ROADMAP.md                        长期开发方向
│  ├─ AUTH-REPAIR-PLAN.md                本次认证修复计划及验收状态
│  ├─ WINDOWS-AUTH-SESSION-ISSUE.md       反馈、日志证据和未确认假设
│  ├─ UI-AND-RECOVERY.md                 2.0 UI/恢复历史记录
│  ├─ C-REFERENCE-REVIEW.md              C 附件对照、响应修复及验证
│  └─ RECOVERY-REPAIR-REPORT.md           切网、唤醒、任务取消和原生边界
├─ ESurfingDialer-Windows-Client/
│  ├─ 00_最终安装包/                     历史安装包目录，不随源码改动更新
│  └─ 01_客户端源码/ESurfingDialer-Windows-Client/
│     ├─ app/ESurfingDialerLite/         Windows WPF 客户端
│     ├─ core/ESurfingDialerCore/        Windows 使用的 Kotlin/Java 认证核心
│     ├─ tests/ClientRegression/         客户端与 Java 子进程回归
│     ├─ scripts/                       核心、运行时、发布与安装包构建脚本
│     └─ installer/                     Inno Setup 配置
└─ ESurfingDialer-Docker/
   ├─ 01_Docker源码/ESurfingDialer-Docker-Fixed/
   └─ 02_使用教程/                       部署教程及截图
```

## 架构与职责

Windows 客户端使用 C#、WPF 和 .NET 10。它负责账户配置、DPAPI 密码保护、托盘、UI、日志导出与 Java 进程监督。认证核心使用 Kotlin/JVM、Java 21、OkHttp 与 Unidbg，负责门户识别、会话、ticket、登录、心跳和健康文件。二者通过 Java 子进程、本地健康 JSON 与标准输入控制命令协作。

关键入口：

- [Windows 客户端工程](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/app/ESurfingDialerLite/ESurfingDialerLite.csproj)
- [认证核心入口与网络监测](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/DialerApp.kt)
- [登录与心跳流程](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/Client.kt)
- [动态门户配置](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/PortalConfiguration.kt)
- [认证诊断与原生加载边界](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/AuthenticationDiagnostics.kt)
- [客户端健康监督策略](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/app/ESurfingDialerLite/EnhancedConnectionPolicy.cs)

Docker 使用独立的 Kotlin/JVM 核心和 Java 17，包含 Compose、镜像构建与离线安装脚本。Windows 与 Docker 的 UA、原生库、模拟架构和门户兼容路径存在差异，不应直接复制覆盖。Docker 具体构建、配置与部署以其 [源码 README](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/README.md)、[离线说明](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/README_OFFLINE.md) 和 [部署教程](../ESurfingDialer-Docker/02_使用教程/ESurfingDialer-Docker部署教程.md) 为准。

## zcode 与 Codex 同步开发

同步依靠同一 Git 仓库、源码、任务范围和交接文件。本文不配置两种工具之间的聊天自动同步，也不假定 zcode 会自动读取某种规则文件；每次开工时明确要求工具读取根目录文档。

推荐日常方式：两边打开同一仓库，按任务轮流接手。一个工具实施某个文件的修改时，另一个工具可读代码、提出建议或处理明确不重叠的文件。不要同时修改同一个文件或同时运行会写相同输出目录的构建/测试。

| 环节 | 两边共同执行的动作 |
| --- | --- |
| 接手 | 阅读根目录 `AGENTS.md`、本文、`协作规则.md`、`DEVELOPMENT-HANDOFF.md` 和相关修复计划；检查 `git status` 与当前 diff |
| 认领 | 在交接记录中写明任务、执行工具和预计修改文件；先确认没有覆盖另一边正在做的改动 |
| 实施 | 按已认领范围修改；范围发生变化时先更新记录，与另一边错开文件 |
| 验证 | 运行与改动相关的检查，记录命令、结果、工作目录和未验证内容 |
| 交接 | 补充实际修改文件、遗留问题、下一步和当前未提交改动；接手方重新检查源码状态 |
| 同步仓库 | 同目录工作通过文件即可看到改动；需要提交时按具体任务选文件，推送前遵循用户当前授权 |

若以后必须真正并行实施同一模块，使用独立分支和独立工作目录，分别验证后再审阅合并。不要把“复制整份目录”作为常规同步方式。跨电脑工作时，以明确提交和远端仓库同步；此前本地检查点为 c99dce5；REC-02 和 beta 发布改动按本次授权提交并同步远端，实际提交与发布结果见交接记录。

可以将以下提示分别交给 zcode 和 Codex：

```text
请在 C:\Users\FLEETING\Documents\Project\ESurfingDialer-Docker 开发本项目。
先读取根目录 AGENTS.md，以及 项目文档/PROJECT-GUIDE.md、
项目文档/协作规则.md、项目文档/DEVELOPMENT-HANDOFF.md，
再读取本任务涉及的 项目文档/ROADMAP.md 或 项目文档/AUTH-REPAIR-PLAN.md。
先检查 git status 和现有 diff，保留已有未提交改动。
按交接记录认领任务和文件范围，避免覆盖另一工具的工作。
完成后更新修复状态、实际验证结果和交接记录。
仅按当前用户明确授权打包或发布；不要把本地回归通过当成真实校园网认证已修复。
本次任务：<填写具体问题或计划编号>。
```

## 本地构建与验证

以下是 Windows 源码验证命令，不生成安装包。首次准备构建环境需要 .NET 10 SDK、JDK 17、JDK 21 和 Gradle/依赖缓存。核心脚本以 JDK 17 启动 Gradle 8.4，以 JDK 21 编译和运行认证核心。

在合并仓库根目录打开 PowerShell：

```powershell
$clientSource = Join-Path (Get-Location).Path 'ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client'
Push-Location $clientSource
try {
    # 使用本机现有缓存；JDK 路径不同的机器替换这两个参数。
    & ./scripts/Build-Core.ps1 -Offline -Regression -Jdk17 'D:\Java\JDK17' -Jdk21 'D:\Java\JDK21'
    if ($LASTEXITCODE -ne 0) { throw '认证核心验证失败' }

    # 缺少 project.assets.json 时先生成还原资产。
    dotnet restore ./tests/ClientRegression/ClientRegression.csproj --ignore-failed-sources
    if ($LASTEXITCODE -ne 0) { throw '.NET 依赖还原失败' }
    dotnet build ./app/ESurfingDialerLite/ESurfingDialerLite.csproj --no-restore
    if ($LASTEXITCODE -ne 0) { throw '客户端编译失败' }
    dotnet run --project ./tests/ClientRegression/ClientRegression.csproj --no-restore -- artifacts/auth-repair-ui --core
    if ($LASTEXITCODE -ne 0) { throw '客户端回归失败' }
}
finally { Pop-Location }
```

`--ignore-failed-sources` 允许忽略不可用的还原源，不代表缺失依赖也能离线还原。`-Offline` 同样要求已准备好 Gradle/JDK 和依赖缓存。遇到缓存权限或原生组件加载失败时记录实际错误，按工具的权限流程处理，不安装不必要的新依赖。

2026-09-26 的修复源码已通过 109 项认证检查、46 项恢复检查、103 项客户端检查。检查数量与当次运行环境有关，后续应记录实际输出，不硬编码历史数量。测试使用独立数据目录和本机模拟 HTTP 服务；真实算法、账号登录、校园网心跳及长时间运行仍需现场验证。

运行日志通常位于 `%LOCALAPPDATA%\ESurfingDialerLite\logs`。通过客户端导出脱敏日志；不要将账户配置、密码、票据、密钥或原始 ZSM 加入 Git 或公共交接文档。

## 文档维护与发布边界

长期方向写入 [ROADMAP.md](ROADMAP.md)，本轮修复及验收写入 [AUTH-REPAIR-PLAN.md](AUTH-REPAIR-PLAN.md)，现场事实与假设写入 [WINDOWS-AUTH-SESSION-ISSUE.md](WINDOWS-AUTH-SESSION-ISSUE.md)，工具之间的执行状态写入 [DEVELOPMENT-HANDOFF.md](DEVELOPMENT-HANDOFF.md)。这些文档均位于本目录。历史说明明确标注版本，避免多份文档对当前状态作出相反结论。

当前 v2.3beta 用于现场测试，安装包和发布结果以 GitHub Releases 及交接记录为准。真实校园网认证和关键恢复测试通过后，再按用户授权发布正式版；长期规划无需全部完成才进行 beta 测试。
