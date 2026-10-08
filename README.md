# ESurfingDialer（天翼校园）

**ESurfingDialer / Tianyi Campus** 是一款校园网认证工具，目前提供 Windows 客户端和 Docker 软路由两个版本，可以根据自己的使用环境选择。

如果你更熟悉“天翼校园”这个名字，也可以用“天翼校园认证”“校园网认证”或“ESurfingDialer”等关键词找到本项目。


### Windows 客户端：

如果你在 Windows 电脑上使用校园网，可以选择这个版本，直接安装即可。

~~[下载 Windows 客户端 v2.2](https://github.com/FLEETING-XHX/ESurfingDialer/releases/download/v2.2/ESurfingDialer-Windows-v2.2-Setup.exe)~~


> ![红色警告](assets/windows-warning.svg)
>
> Windows 客户端 v2.2 存在可能导致校园网认证失败的 Bug，目前不建议下载或使用。如果已经安装，建议先保留或恢复原来可用的认证方式。
>
> **v2.3beta 已开放测试**：想试用的话，可以到 [Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases/tag/v2.3beta) 下载。这个版本还需要在真实校园网环境中验证认证和稳定性，可能仍会遇到认证问题。Windows 测试包请从 Releases 页面下载。



### Docker 软路由版：

如果你使用 iStoreOS / OpenWrt 软路由，可以选择 Docker 版本。

[下载 Docker v1.3 离线部署包](https://github.com/FLEETING-XHX/ESurfingDialer/releases/download/v1.3/ESurfingDialer-Docker-v1.3.zip)

[查看 Docker 安装教程](ESurfingDialer-Docker/02_使用教程/ESurfingDialer-Docker部署教程.md)



Docker 版本提供离线镜像包。提前准备好安装包后，软路由即使没有公网，也可以完成部署。

> **稳定性记录**：Docker v1.3 已连续测试 14 天，期间未出现断网情况。

## 版本说明

- Windows 客户端和 Docker 版本分别维护，更新互不影响。
- 两个版本的文件放在各自目录中，共用这个 GitHub 仓库。
- Releases 页面会标明是 Windows 客户端还是 Docker 版本，下载时留意一下即可。

## 其他文件

需要源码、历史版本或相关说明，可以到 [Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases) 页面查看。

Windows 客户端和 Docker 版本使用不同的 Release 标签，方便区分。

## 项目文档

想了解后续计划、修复进度或测试情况，可以看看[项目文档](项目文档/)文件夹里的说明。

| 文档 | 用途 |
| --- | --- |
| [中文文档索引](项目文档/README.md) | 按用户、维护者、产品和历史记录分类阅读 |
| [Windows 使用说明](项目文档/WINDOWS-USER-GUIDE.md) | 账户、连接、托盘、设置和常见故障排查 |
| [待优化与待验收清单](项目文档/PENDING-OPTIMIZATIONS.md) | Windows/Docker 当前待办、证据和完成条件 |
| [v2.3beta 测试说明](项目文档/V2.3BETA-RELEASE.md) | 更新了什么、在哪里下载，以及测试步骤和已验证范围 |
| [未来开发方向](项目文档/ROADMAP.md) | 后续计划和优先顺序 |
| [认证修复计划](项目文档/AUTH-REPAIR-PLAN.md) | 认证问题的修复顺序、进度和待补充的现场证据 |
| [Linear同步与核查](项目文档/LINEAR-WINDOWS-ISSUES-2026-09-30.md) | 当前Issue、依赖、完成条件与最近核查结果 |
| [Docker最新本地beta3](项目文档/DOCKER-LOCAL-BETA3-PORTAL-2026-10-08.md) | 门户修复交付、用户首轮认证结果和剩余验收 |

Windows 已发布测试版本仍是 v2.3beta。本地 v2.3beta.2 已包含 09-29 八项、09-30 六项修复及实际程序入口修复，测试包仅保留本地、未公开发布，见[本地测试包记录](项目文档/WINDOWS-LOCAL-BETA-2026-09-30.md)。真实校园网认证与长期稳定性仍待验收，后续先稳定现有客户端，再评估去 JVM 迁移。

Docker本地beta3门户修复包已在用户现场完成一次登录/首次心跳确认并可上网；持续运行、掉线恢复与注销确认尚未验收。修复原因、历史审查和旧包摘要统一从文档索引进入，不再作为重复开发任务列出。

当前仅同步源码和公共文档，Docker暂不打正式版包，beta包保留本地；计划Windows修复完成后统一安排发布。

## 致谢

核心认证逻辑来自 [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)，感谢原作者的开源工作。
