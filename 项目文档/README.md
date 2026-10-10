# 文档清单与用途

更新：2026-10-08。看进度先读[修复与优化总记录](修复与优化总记录.md)，查操作再看对应说明。

日常维护共26份 Markdown：Docker目录6份、Windows目录6份、共用及专项10份、本机协作4份。重复的24份历史报告/旧计划已删除，原文和两份旧 CHANGELOG 收在[历史归档](归档/历史Markdown_20261008.zip)，不再逐份维护。

## Docker目录 · 6份

下表“源码”指 `ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed`。

| 文件 | 大致用途 |
| --- | --- |
| [产品 README](../ESurfingDialer-Docker/README.md) | 产品介绍、下载和部署入口 |
| [源码 README](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/README.md) | 源码结构、配置、在线构建和维护说明 |
| [源码 README_OFFLINE](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/README_OFFLINE.md) | 正式版离线安装、凭据填写、升级和回滚 |
| [源码 README_BETA](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/README_BETA.md) | beta独立部署、版本互斥、更新及测试步骤 |
| [源码 CHANGELOG](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/CHANGELOG.md) | 打包器需要的简短入口，更新内容统一跳转总记录 |
| [图文部署教程](../ESurfingDialer-Docker/02_使用教程/ESurfingDialer-Docker部署教程.md) | v1.3软路由图文操作教程 |

## Windows目录 · 6份

下表“源码”指 `ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client`。

| 文件 | 大致用途 |
| --- | --- |
| [产品 README](../ESurfingDialer-Windows-Client/README.md) | 产品介绍、下载、当前本地包和使用入口 |
| [源码 README](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/README.md) | 开发、构建、打包和日志位置 |
| [核心 README](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/README.md) | 认证核心职责、运行配置及与WPF的边界 |
| [核心 CHANGELOG](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore/CHANGELOG.md) | 源码/交付检查的简短入口，更新内容统一跳转总记录 |
| [图标 README](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/resources/icons/README.md) | 图标用途、状态颜色及资源规格 |
| [客户端回归 README](../ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/tests/ClientRegression/README.md) | 回归、短压、实际EXE启动检查及结果判定 |

## 共用及专项文档 · 10份

除根目录 README 外，以下都在 `项目文档`。

| 文件 | 大致用途 |
| --- | --- |
| [根目录 README](../README.md) | 整个项目的介绍、下载和产品导航 |
| [本页 README](README.md) | 现存Markdown清单及用途 |
| [修复与优化总记录](修复与优化总记录.md) | Docker/Windows历史改动、交付状态和全部待办；以后统一更新这里 |
| [Windows使用说明](WINDOWS-USER-GUIDE.md) | 安装、账户、托盘、设置和常见故障 |
| [Windows现场故障](WINDOWS-AUTH-SESSION-ISSUE.md) | 区分历史原生失败、新门户问题和初始化空响应，保留证据边界 |
| [Windows当前交付凭据](WINDOWS-BOOTSTRAP-UA-FIX-2026-10-08.md) | UA2104受控对照、当前beta3包摘要及实际验证范围 |
| [Windows稳定/迁移基线](WINDOWS-STABILITY-AND-MIGRATION-BASELINE.md) | 资源实测、问题编号、长期验收及迁移必须保留的行为 |
| [Windows去JVM研究](WINDOWS-NO-JVM-MIGRATION-PLAN.md) | C#/Go迁移可行性、技术起点和前提；尚未实施 |
| [Linear同步记录](LINEAR-WINDOWS-ISSUES-2026-09-30.md) | Issue编号、依赖及核查时的完成条件，不代表远端已更新 |
| [Docker当前交付凭据](DOCKER-LOCAL-BETA3-PORTAL-2026-10-08.md) | 当前beta3包/镜像摘要、构建检查与首次现场成功证据 |

## 本机协作文档 · 4份

仅本地维护，不上传GitHub。

| 文件 | 大致用途 |
| --- | --- |
| 根目录 `AGENTS.md` | 工具开始任务时读取的项目规则入口 |
| `项目文档/协作规则.md` | 分支、文件认领、隐私和协作约定 |
| `项目文档/PROJECT-GUIDE.md` | 架构、源码路径、开发命令和接手说明 |
| `项目文档/DEVELOPMENT-HANDOFF.md` | 本机工具任务、改动范围、验证与遗留交接 |

构建/验证目录另有632份生成或冻结副本，供重现旧包使用，未删除，也不当作日常维护文档。交付包及源码归档保持原样；外部C参考项目的文档另属上游，本轮没动。
