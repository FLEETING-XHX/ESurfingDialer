# ESurfingDialer Windows 客户端（天翼校园）

面向 Windows 桌面使用的 ESurfingDialer 客户端整理版。

本仓库基于原项目整理和打包：

- 原项目： [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)
- 统一项目主页： [ESurfingDialer（天翼校园）](https://github.com/FLEETING-XHX/ESurfingDialer)

## 下载

- 最新安装包：请前往统一仓库的 [Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases) 页面。

当前统一发布版本为 **v2.4**，附件为 `ESurfingDialer-Windows-v2.4-Setup.exe`，支持 Windows x64，内置 .NET 10 与 Java 21。认证通道、门户与恢复修复及新版 UI 均纳入本次包，版本/验证见[v2.4说明](../项目文档/V2.4-RELEASE.md)。真实恢复、安装矩阵与长期稳定仍待验收；账户与托盘操作见[中文使用说明](../项目文档/WINDOWS-USER-GUIDE.md)。

## 适用场景

- Windows 电脑本机认证
- 不想使用软路由或 Docker 部署的用户
- 需要图形界面配置账号密码的用户

## 说明

本目录用于维护 Windows 客户端源码、安装包构建和发布文件。Docker 软路由版本位于同一仓库的：

- `ESurfingDialer-Docker/`

## 开发入口

修复、优化和待办统一见[总记录](../项目文档/修复与优化总记录.md#windows)；各Markdown的用途见[文档清单](../项目文档/README.md)。

## 致谢

核心认证逻辑来源于 [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)。本仓库主要维护 Windows 客户端打包、安装包发布和使用说明。
