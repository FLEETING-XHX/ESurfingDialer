# ESurfingDialer（天翼校园）

**ESurfingDialer / Tianyi Campus** 是一个校园网认证工具，提供 Windows 客户端和 Docker 软路由两个版本。

如果你只知道“天翼校园”，也可以用“天翼校园认证”“校园网认证”“ESurfingDialer”等关键词搜索本项目。


### Windows 客户端：

适合直接安装在 Windows 电脑上。

~~[下载 Windows 客户端 v2.2](https://github.com/FLEETING-XHX/ESurfingDialer/releases/download/v2.2/ESurfingDialer-Windows-v2.2-Setup.exe)~~


> ![红色 Warning](assets/windows-warning.svg) 
>
> Windows 客户端 v2.2 存在可能导致无法完成校园网认证的 Bug，暂不建议下载或使用；已安装用户请保留或恢复原有可用的认证方式。
>
> **v2.3beta 现已提供测试**：请前往 [Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases/tag/v2.3beta) 下载。该版本仍需真实校园网认证与稳定性验证，不能保证已解决所有认证问题。README 暂不提供 Windows 安装包下载直连。



### Docker 软路由版：

适合部署在 iStoreOS / OpenWrt 软路由上。

[下载 Docker v1.3 离线部署包](https://github.com/FLEETING-XHX/ESurfingDialer/releases/download/v1.3/ESurfingDialer-Docker-v1.3.zip)

[查看 Docker 安装教程](ESurfingDialer-Docker/02_使用教程/ESurfingDialer-Docker部署教程.md)



Docker 版本提供离线镜像包，软路由没有公网时也可以完成部署。

> **稳定性记录**：Docker v1.3 已连续测试 14 天，期间未出现断网情况。

## 版本说明

- Windows 客户端和 Docker 版本分别维护，互不影响。
- 两个版本使用独立目录，但共用同一个 GitHub 仓库。
- 发布版本会在 Release 页面中分别标明客户端或 Docker。

## 其他文件

源码、历史版本和详细教程请进入 [Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases) 页面查看。

Windows 客户端和 Docker 版本使用不同的 Release 标签，避免下载时混淆。

## 项目文档与 zcode / Codex 协作

项目说明和开发文档集中在[项目文档](项目文档/)文件夹。两个工具共同开发时，请先阅读项目说明、协作规则和最新交接记录。根目录的 `AGENTS.md` 是 Codex 项目规则入口。

| 文档 | 用途 |
| --- | --- |
| [v2.3beta 测试说明](项目文档/V2.3BETA-RELEASE.md) | 更新内容、下载入口、验证边界与测试步骤 |
| [项目说明](项目文档/PROJECT-GUIDE.md) | 目录、架构、构建验证与双工具同步开发方式 |
| [协作规则](项目文档/协作规则.md) | 文件范围、改动保护、验证和打包约束 |
| [开发交接记录](项目文档/DEVELOPMENT-HANDOFF.md) | 当前任务、认领、完成情况与接手步骤 |
| [未来开发方向](项目文档/ROADMAP.md) | 长期规划与优先级 |
| [认证修复计划](项目文档/AUTH-REPAIR-PLAN.md) | 当前认证修复顺序、验收状态和待现场证据 |
| [C 项目对照与修复](项目文档/C-REFERENCE-REVIEW.md) | 参考源码差异、响应修复和后续兼容方向 |
| [连接恢复与会话修复](项目文档/RECOVERY-REPAIR-REPORT.md) | 切网、唤醒、恢复取消、原生会话生命周期与格式诊断 |
| [Windows 认证问题记录](项目文档/WINDOWS-AUTH-SESSION-ISSUE.md) | 反馈、日志证据与未确认假设 |
| [UI 与连接恢复历史记录](项目文档/UI-AND-RECOVERY.md) | 2.0 的界面和恢复改动说明 |

当前 Windows 测试版本为 v2.3beta；本地回归通过不等于故障校园网已恢复认证。现场测试与后续开发状态见项目文档。

## 致谢

核心认证逻辑来源于 [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)。
