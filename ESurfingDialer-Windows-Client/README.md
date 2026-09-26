# ESurfingDialer Windows Client（天翼校园客户端）

面向 Windows 桌面使用的 ESurfingDialer 客户端整理版。

本仓库基于原项目整理和打包：

- 原项目： [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)
- 统一项目主页： [ESurfingDialer（天翼校园）](https://github.com/FLEETING-XHX/ESurfingDialer)

## 下载

- 最新安装包：请前往统一仓库的 [Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases) 页面。

## 适用场景

- Windows 电脑本机认证
- 不想使用软路由或 Docker 部署的用户
- 需要图形界面配置账号密码的用户

## 说明

本目录用于维护 Windows 客户端源码、安装包构建和发布文件。Docker 软路由版本位于同一仓库的：

- `ESurfingDialer-Docker/`

## 开发入口

合并仓库的开发说明集中在[项目文档](../项目文档/)：[项目说明](../项目文档/PROJECT-GUIDE.md)、[未来开发方向](../项目文档/ROADMAP.md)、[认证修复计划](../项目文档/AUTH-REPAIR-PLAN.md) 与 [开发交接记录](../项目文档/DEVELOPMENT-HANDOFF.md)。zcode 和 Codex 共同开发时使用同一个合并仓库，按 [协作规则](../项目文档/协作规则.md) 交接；根目录 `AGENTS.md` 为 Codex 规则入口。

## 致谢

核心认证逻辑来源于 [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)。本仓库主要维护 Windows 客户端打包、安装包发布和使用说明。
