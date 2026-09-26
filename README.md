# ESurfingDialer（天翼校园）

**ESurfingDialer / Tianyi Campus** 是一款校园网认证工具，目前提供 Windows 客户端和 Docker 软路由两个版本，可以根据自己的使用环境选择。

如果你更熟悉“天翼校园”这个名字，也可以用“天翼校园认证”“校园网认证”或“ESurfingDialer”等关键词找到本项目。


### Windows 客户端：

如果你在 Windows 电脑上使用校园网，可以选择这个版本，直接安装即可。

~~[下载 Windows 客户端 v2.2](https://github.com/FLEETING-XHX/ESurfingDialer/releases/download/v2.2/ESurfingDialer-Windows-v2.2-Setup.exe)~~


> ![红色 Warning](assets/windows-warning.svg) 
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
| [v2.3beta 测试说明](项目文档/V2.3BETA-RELEASE.md) | 更新了什么、在哪里下载，以及测试步骤和已验证范围 |
| [未来开发方向](项目文档/ROADMAP.md) | 后续计划和优先顺序 |
| [认证修复计划](项目文档/AUTH-REPAIR-PLAN.md) | 认证问题的修复顺序、进度和待补充的现场证据 |
| [C 项目对照与修复](项目文档/C-REFERENCE-REVIEW.md) | 参考项目的差异、本次修复和后续兼容方向 |
| [连接恢复与会话修复](项目文档/RECOVERY-REPAIR-REPORT.md) | 切换网络、休眠唤醒、连接恢复和原生会话的修复记录 |
| [Windows 认证问题记录](项目文档/WINDOWS-AUTH-SESSION-ISSUE.md) | 已收到的问题反馈、日志和仍待确认的原因 |
| [UI 与连接恢复历史记录](项目文档/UI-AND-RECOVERY.md) | 2.0 版本的界面和连接恢复改动 |

Windows 当前测试版本是 v2.3beta。本地回归检查已经通过，但还不能据此确认真实校园网认证已恢复；现场测试和后续开发进度会记录在项目文档中。

## 致谢

核心认证逻辑来自 [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)，感谢原作者的开源工作。
