# ESurfingDialer（天翼校园）

**ESurfingDialer / Tianyi Campus** 是校园网认证工具，提供 Windows 客户端和 Docker 软路由版本，可以根据使用环境选择。

如果你更熟悉“天翼校园”这个名字，也可以用“天翼校园认证”“校园网认证”或“ESurfingDialer”等关键词找到本项目。

### Windows 客户端

在 Windows 电脑上使用校园网，下载后运行安装器。**v2.4** 内置 .NET 和 Java，无需另行安装运行环境；支持 Windows x64。

[下载 Windows 客户端 v2.4](https://github.com/FLEETING-XHX/ESurfingDialer/releases/download/v2.4/ESurfingDialer-Windows-v2.4-Setup.exe)

[查看 Windows 使用说明](项目文档/WINDOWS-USER-GUIDE.md)

v2.4 汇入认证通道、门户解析和连接恢复修复，更新界面、内嵌中文字体、托盘提示及窗口缩放。启动尺寸会按显示器缩放和工作区自动适配，支持手动等比调整。

升级前完全退出旧客户端并备份账户配置。此前 UA2104 修复版已通过一次真实登录、确认心跳和外网访问；v2.4 的真实切网、休眠恢复、安装升级矩阵与长期稳定仍需继续验收。Windows v2.2 有已知认证失败问题，建议使用新版。

### Docker 软路由版

使用 iStoreOS / OpenWrt 软路由，可选择 **v2.4 离线部署包**，仅支持 **amd64 / x86_64**。设备需要已安装 Docker 和 Docker Compose。

[下载 Docker v2.4 离线部署包](https://github.com/FLEETING-XHX/ESurfingDialer/releases/download/v2.4/ESurfingDialer-Docker-v2.4.zip)

[查看 Docker v2.4 部署说明](ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/README_RELEASE.md)

离线包包含镜像、安装脚本和配置模板，准备好文件后无需公网拉取镜像。解压后运行 `sh install.sh`，安装默认只创建容器；停止旧认证容器后运行 `sh start.sh`。

v2.4 汇入门户兼容、会话恢复、健康监督、日志限额与审查修复。此前 beta3 已通过一次真实认证及上网；v2.4 的恢复、注销和长期稳定仍待验收。Docker v1.3 的 14 天稳定性记录属于历史版本。

## 版本说明

- 从本次发布起，Windows 和 Docker 统一使用 **v2.4**，放在[同一个 Release](https://github.com/FLEETING-XHX/ESurfingDialer/releases/tag/v2.4) 中。
- Release 的两个产品附件分别是 Windows `.exe` 安装器与 Docker `.zip` 离线包，按文件名选择。
- 两个产品分别维护源码与认证核心；旧版本仍可从[历史 Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases) 查看。
- 真实认证与长期验收范围见[版本说明](项目文档/V2.4-RELEASE.md)，本地回归结果不能替代现场长期验证。

## 项目文档

| 文档 | 用途 |
| --- | --- |
| [v2.4 发布说明](项目文档/V2.4-RELEASE.md) | 更新内容、两个下载文件、验证与待验收范围 |
| [修复与优化总记录](项目文档/修复与优化总记录.md) | 按 Docker / Windows 查看历史改动与当前待办 |
| [文档清单与用途](项目文档/README.md) | 各产品与共用文档的入口 |
| [Windows 使用说明](项目文档/WINDOWS-USER-GUIDE.md) | 账户、连接、托盘、设置和故障排查 |
| [Docker v2.4 部署说明](ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/README_RELEASE.md) | 离线安装、启动、迁移与配置保留 |
| [Docker 历史图文教程](ESurfingDialer-Docker/02_使用教程/ESurfingDialer-Docker部署教程.md) | v1.3 的截图与旧版安装步骤 |

## 致谢

核心认证逻辑来自 [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)，感谢原作者的开源工作。
