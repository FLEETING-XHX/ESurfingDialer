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

修复进度、优化和待办现在统一记在一份文件里，按 Docker / Windows 分开看。

| 文档 | 用途 |
| --- | --- |
| [修复与优化总记录](项目文档/修复与优化总记录.md) | 历史改动、当前源码/测试包状态及全部待办 |
| [文档清单与用途](项目文档/README.md) | Docker、Windows和共用Markdown各自做什么 |
| [Windows使用说明](项目文档/WINDOWS-USER-GUIDE.md) | 账户、连接、托盘、设置和故障排查 |
| [Docker部署教程](ESurfingDialer-Docker/02_使用教程/ESurfingDialer-Docker部署教程.md) | v1.3软路由图文安装步骤 |

Windows最新本地包为[beta3 UA2104修复版](项目文档/WINDOWS-BOOTSTRAP-UA-FIX-2026-10-08.md)，10-09用户真实登录、首次确认心跳及外网访问已通过；后续定时心跳、恢复和长期稳定待验。Docker最新本地[beta3门户修复包](项目文档/DOCKER-LOCAL-BETA3-PORTAL-2026-10-08.md)已完成一次真实认证并可上网，持续心跳/恢复/注销仍待验收。两端测试包均未公开发布，正式发布另按授权安排。

## 致谢

核心认证逻辑来自 [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)，感谢原作者的开源工作。
