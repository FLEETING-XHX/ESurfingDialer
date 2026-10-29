当前目录分类与产物入口见[目录与产物说明](目录与产物说明.md)。产品文档分别在Windows、Docker、DockerCpp子目录；以下保留各文档的用途索引。

# 文档清单与用途

更新：2026-10-29。看进度先读[修复与优化总记录](修复与优化总记录.md)，查操作再看对应说明。

下表列出保留的使用、开发与验证文档。Docker 部署统一使用图文教程，任务进度与 Linear 编号统一在总记录维护；重复产品入口、旧离线说明、简版部署说明和过时状态快照已删除。更早的24份历史报告/旧计划及两份旧 CHANGELOG 原文收在[历史归档](归档/历史Markdown_20261008.zip)。

## Docker目录

下表“源码”指 `Products/Docker`。

| 文件 | 大致用途 |
| --- | --- |
| [源码 README](../Products/Docker/README.md) | 源码结构、配置、在线构建和维护说明 |
| [图文部署教程](Docker/教程/ESurfingDialer-Docker部署教程.md) | WinSCP 上传、SSH 登录、v2.4 安装启动、迁移与常见问题 |
| [历史 beta3 测试说明](Docker/教程/历史版本/Docker-beta3测试说明.md) | 旧 beta 独立部署、版本互斥与更新；也供历史打包器读取 |
| [源码 CHANGELOG](../Products/Docker/CHANGELOG.md) | 打包器需要的简短入口，更新内容统一跳转总记录 |

## Windows目录

下表“源码”指 `Products/Windows`。

| 文件 | 大致用途 |
| --- | --- |
| [源码 README](../Products/Windows/README.md) | 开发、构建、打包和日志位置 |
| [核心 README](../Products/Windows/core/ESurfingDialerCore/README.md) | 认证核心职责、运行配置及与WPF的边界 |
| [核心 CHANGELOG](../Products/Windows/core/ESurfingDialerCore/CHANGELOG.md) | 源码/交付检查的简短入口，更新内容统一跳转总记录 |
| [图标 README](../Products/Windows/resources/icons/README.md) | 图标用途、状态颜色及资源规格 |
| [客户端回归 README](../Products/Windows/tests/ClientRegression/README.md) | 回归、短压、实际EXE启动检查及结果判定 |

## 共用及专项文档

除根目录 README 外，以下都在 `Docs`。

| 文件 | 大致用途 |
| --- | --- |
| [根目录 README](../README.md) | 整个项目的介绍、下载和产品导航 |
| [本页 README](README.md) | 现存Markdown清单及用途 |
| [v2.4 发布说明](V2.4-RELEASE.md) | 两端统一版本、发布门禁、附件摘要与剩余验收 |
| [v2.4.1修复记录](V2.4.1-FIXES.md) | 两端会话所有权/联网状态修复、每日重登规则及源码验证；未生成新交付包 |
| [Docker 10-10 审查修复](Docker/DOCKER-CODE-REVIEW-FIXES-2026-10-10.md) | 触发条件、修复依据与隔离回归 |
| [修复与优化总记录](修复与优化总记录.md) | Docker/Windows历史改动、交付状态和全部待办；以后统一更新这里 |
| [Windows使用说明](Windows/WINDOWS-USER-GUIDE.md) | 安装、账户、托盘、设置和常见故障 |
| [Windows现场故障](Windows/WINDOWS-AUTH-SESSION-ISSUE.md) | 区分历史原生失败、新门户问题和初始化空响应，保留证据边界 |
| [Windows beta3 现场凭据](Windows/WINDOWS-BOOTSTRAP-UA-FIX-2026-10-08.md) | UA2104受控对照、当前beta3包摘要及实际验证范围 |
| [Windows稳定/迁移基线](Windows/WINDOWS-STABILITY-AND-MIGRATION-BASELINE.md) | 资源实测、问题编号、长期验收及迁移必须保留的行为 |
| [v2.4会话所有权故障](Windows/WINDOWS-SESSION-OWNERSHIP-ISSUE-2026-10-29.md) | 可上网却连接中的代码定位、Docker影响边界、待验假设与C++迁移约束 |
| [Windows去JVM研究](Windows/WINDOWS-NO-JVM-MIGRATION-PLAN.md) | C#/Go迁移可行性、技术起点和前提；尚未实施 |
| [Docker beta3 现场凭据](Docker/DOCKER-LOCAL-BETA3-PORTAL-2026-10-08.md) | 当前beta3包/镜像摘要、构建检查与首次现场成功证据 |

## 本机协作文档 · 4份

仅本地维护，不上传GitHub。

| 文件 | 大致用途 |
| --- | --- |
| 根目录 `AGENTS.md` | 工具开始任务时读取的项目规则入口 |
| `Docs/协作规则.md` | 分支、文件认领、隐私和协作约定 |
| `Docs/PROJECT-GUIDE.md` | 架构、源码路径、开发命令和接手说明 |
| `Docs/DEVELOPMENT-HANDOFF.md` | 本机工具任务、改动范围、验证与遗留交接 |

构建/验证目录中的生成或冻结副本用于重现旧包，不当作日常维护文档。交付包、源码归档与本项目许可证保持原样。外部C参考源码只保留本机，已从本仓库移除；上游文档见 [BadGhost520/ESurfingClient-CVersion](https://github.com/BadGhost520/ESurfingClient-CVersion)。
