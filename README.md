# ESurfingDialer（天翼校园）

**ESurfingDialer / Tianyi Campus** 是校园网认证工具，提供 Windows 客户端和 Docker 软路由版本，可以根据使用环境选择。

本项目在同一个仓库维护多个产品。Windows 版有 C#/WPF 客户端（`Products/Windows`）和 C++/Qt Quick 测试版（`Products/WindowsCpp`）。软路由版有 Kotlin Docker（`Products/Docker`）和 DockerCpp（`Products/DockerCpp`）。

源码和本地产物位置见[目录与产物说明](Docs/目录与产物说明.md)。

如果你更熟悉“天翼校园”这个名字，也可以用“天翼校园认证”“校园网认证”或“ESurfingDialer”等关键词找到本项目。

### Windows 客户端

在 Windows 电脑上使用校园网，下载后运行安装器。**v2.4** 内置 .NET 和 Java，无需另行安装运行环境；支持 Windows x64。

[下载 Windows 客户端 v2.4](https://github.com/FLEETING-XHX/ESurfingDialer/releases/download/v2.4/ESurfingDialer-Windows-v2.4-Setup.exe)

[查看 Windows 使用说明](Docs/Windows/WINDOWS-USER-GUIDE.md)

v2.4 汇入认证通道、门户解析和连接恢复修复，更新界面、内嵌中文字体、托盘提示及窗口缩放。启动尺寸会按显示器缩放和工作区自动适配，支持手动等比调整。

升级前完全退出旧客户端并备份账户配置。此前 UA2104 修复版已通过一次真实登录、确认心跳和外网访问；v2.4 的真实切网、休眠恢复、安装升级矩阵与长期稳定仍需继续验收。Windows v2.2 有已知认证失败问题，建议使用新版。

### Windows C++ 测试版

**0.1.1** 面向 Windows x64，采用 Qt Quick 界面和原生 C++ 认证核心，使用 2082 认证通道。安装包包含所需运行库，支持无需 Qt 开发环境启动。

默认精简安装包实测 **29.38 MiB**，安装并首次启动后 **96.84 MiB**。省磁盘构建的安装包为 **32.57 MiB**，安装后为 **88.73 MiB**，两种口径均包含各自的完整文件。

当前可从源码构建测试安装包。已完成离线回归及本机安装、启动和卸载验证，真实校园网登录与长期运行仍待现场验收。构建命令和两种打包方式见 [WindowsCpp README](Products/WindowsCpp/README.md)。

### Docker 软路由版

使用 iStoreOS / OpenWrt 软路由，可选择 **v2.4 离线部署包**，仅支持 **amd64 / x86_64**。设备需要已安装 Docker 和 Docker Compose。

[下载 Docker v2.4 离线部署包](https://github.com/FLEETING-XHX/ESurfingDialer/releases/download/v2.4/ESurfingDialer-Docker-v2.4.zip)

[查看 Docker 软路由图文部署教程](Docs/Docker/教程/ESurfingDialer-Docker部署教程.md)

离线包包含镜像、安装脚本和配置模板，准备好文件后无需公网拉取镜像。解压后运行 `sh install.sh`，安装默认只创建容器；停止旧认证容器后运行 `sh start.sh`。

v2.4 汇入门户兼容、会话恢复、健康监督、日志限额与审查修复。此前 beta3 已通过一次真实认证及上网；v2.4 的恢复、注销和长期稳定仍待验收。Docker v1.3 的 14 天稳定性记录属于历史版本。

## 版本说明

- [v2.4 Release](https://github.com/FLEETING-XHX/ESurfingDialer/releases/tag/v2.4) 提供 Windows C#/WPF `.exe` 安装器和 Kotlin Docker `.zip` 离线包。
- 两个产品分别维护源码与认证核心；旧版本仍可从[历史 Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases) 查看。
- 真实认证与长期验收范围见[版本说明](Docs/V2.4-RELEASE.md)，本地回归结果不能替代现场长期验证。

## Docs

| 文档 | 用途 |
| --- | --- |
| [v2.4 发布说明](Docs/V2.4-RELEASE.md) | 更新内容、两个下载文件、验证与待验收范围 |
| [修复与优化总记录](Docs/修复与优化总记录.md) | 按 Docker / Windows 查看历史改动与当前待办 |
| [文档清单与用途](Docs/README.md) | 各产品与共用文档的入口 |
| [Windows 使用说明](Docs/Windows/WINDOWS-USER-GUIDE.md) | 账户、连接、托盘、设置和故障排查 |
| [Docker 软路由图文部署教程](Docs/Docker/教程/ESurfingDialer-Docker部署教程.md) | WinSCP 上传、SSH 登录、v2.4 离线安装、启动及迁移 |

## 致谢

核心认证逻辑来自 [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)，感谢原作者的开源工作。
