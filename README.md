# ESurfingDialer（天翼校园）

**ESurfingDialer** 是一款开源的天翼校园网认证工具，支持 Windows 电脑及 OpenWrt / iStoreOS 软路由，提供自动认证、心跳保活和断线恢复等功能。

## 下载与安装

### Docker C++ 版（v3.0.0）

采用原生 C++ 实现，资源占用更低，支持 **amd64、arm64、armv7** 三种架构。

适用于安装了 Docker 和 Docker Compose 的 OpenWrt / iStoreOS 等设备。

[**下载最新版本 v3.0.0**](https://github.com/FLEETING-XHX/ESurfingDialer/releases/tag/v3.0.0)

[查看 Docker 版本安装运行教程](https://github.com/FLEETING-XHX/ESurfingDialer/blob/main/Docs/%E6%95%99%E7%A8%8B/Docker%E6%96%B0%E6%89%8B%E5%AE%89%E8%A3%85%E6%95%99%E7%A8%8B.md)

推荐下载 `ESurfingDialer-Docker-v3.0.0-auto.zip` 自动适配版本，也可以根据设备架构选择对应安装包。

主要功能：

- 自动认证与心跳保活，支持异常恢复。
- 每日定时重启，减少长期运行故障。
- 中文运行日志，支持自动清理。
- 保留设备身份和配置，方便后续升级。

**注意：** arm64 / armv7 已通过模拟环境测试，暂未完成真机长期运行验证。

### Windows 客户端（v2.4）

适用于 Windows x64，提供图形化操作界面，内置所需运行环境。

[**下载 Windows 客户端 v2.4**](https://github.com/FLEETING-XHX/ESurfingDialer/releases/download/v2.4/ESurfingDialer-Windows-v2.4-Setup.exe)

[查看 Windows 使用说明](Docs/Windows/WINDOWS-USER-GUIDE.md)

Windows C++ 版目前为 0.1.1 测试版，可从源码构建。构建和打包方法见 [WindowsCpp README](Products/WindowsCpp/README.md)。

## 版本状态

| 产品 | 当前版本 | 状态 |
| --- | --- | --- |
| Docker C++ | v3.0.0 | 已发布 |
| Windows 客户端 | v2.4 | 已发布，C++ 0.1.1 测试中 |
| OpenWrt 原生软件包 | v3.0.0 | 已发布，提供 IPK 与 APK |

历史版本可在 [Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases) 中下载。

## 项目文档

- [v3.0.0 发布说明](https://github.com/FLEETING-XHX/ESurfingDialer/releases/tag/v3.0.0)
- [Docker 版本安装运行教程](https://github.com/FLEETING-XHX/ESurfingDialer/blob/main/Docs/%E6%95%99%E7%A8%8B/Docker%E6%96%B0%E6%89%8B%E5%AE%89%E8%A3%85%E6%95%99%E7%A8%8B.md)
- [Windows 使用说明](Docs/Windows/WINDOWS-USER-GUIDE.md)
- [文档目录](Docs/README.md)
- [项目目录与源码说明](Docs/目录与产物说明.md)

## 致谢

本项目核心认证逻辑参考 [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)，感谢原作者的开源贡献。
