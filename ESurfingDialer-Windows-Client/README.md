# ESurfingDialer Windows 客户端（天翼校园）

面向 Windows 桌面使用的 ESurfingDialer 客户端整理版。

本仓库基于原项目整理和打包：

- 原项目： [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)
- 统一项目主页： [ESurfingDialer（天翼校园）](https://github.com/FLEETING-XHX/ESurfingDialer)

## 下载

- 最新安装包：请前往统一仓库的 [Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases) 页面。

当前已发布Windows版本仍为v2.3beta；最新本地[beta3 UA2104修复包](../项目文档/WINDOWS-BOOTSTRAP-UA-FIX-2026-10-08.md)保留既有修复并修正初始化空响应通道，327/63/189项及实际EXE就绪/清理通过，原ARM64库无账号真实初始化/票据响应解析通过，未上传或发布。10-09真实登录、首次确认心跳及外网访问已通过；连续心跳、恢复和长期稳定待验。安装与账户/托盘操作见[中文使用说明](../项目文档/WINDOWS-USER-GUIDE.md)。

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
