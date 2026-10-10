# ESurfingDialer Docker（天翼校园软路由版）

面向 iStoreOS / OpenWrt 软路由的 ESurfingDialer Docker 离线部署版。

本仓库基于原项目整理、修复和打包：

- 原项目： [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)
- 统一项目主页： [ESurfingDialer（天翼校园）](https://github.com/FLEETING-XHX/ESurfingDialer)

## 下载

- 最新离线部署包：请前往统一仓库的 [Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases) 页面。

当前统一发布版本为 **v2.4**，附件是 `ESurfingDialer-Docker-v2.4.zip`，仅支持 amd64 / x86_64。安装默认只创建、不启动；停止旧认证容器后显式启动。安装、迁移与配置保留见[v2.4 部署说明](01_Docker源码/ESurfingDialer-Docker-Fixed/README_RELEASE.md)。此前 beta3 首次认证已通过，v2.4 的恢复、注销与长期稳定仍待验收。

## 适用场景

- iStoreOS / OpenWrt x86_64 软路由
- 校园网认证前无法访问公网的环境
- 不方便安装 Gradle、JDK、代理或手动构建 Docker 镜像的用户

## 主要改动

- 提供真正离线部署包，支持 `docker load` 导入镜像后启动。
- 拆分普通部署和源码构建配置，默认部署不触发在线构建。
- 修复 x86_64 环境默认启用 Dynarmic 导致容器不健康的问题。
- 提供 `install.sh` 一键安装脚本。
- 增加运行日志保存和三天日志轮转。
- 改进健康检查和部署文档。

## 快速使用

下载 Release 中的离线包，上传到软路由后解压：

```sh
unzip ESurfingDialer-Docker-v2.4.zip
cd ESurfingDialer-Docker-v2.4
sh install.sh
# 停止旧认证容器后再执行：
sh start.sh
```

包内已包含镜像，不需要从公网拉取。v2.4 容器名为 `ESurfingDialer-v2.4`；保留旧部署目录、配置和设备身份，不要同时启动两个认证版本。

## 开发入口

修复、优化和待办统一见[总记录](../项目文档/修复与优化总记录.md#docker)；各Markdown的用途见[文档清单](../项目文档/README.md)。

## 致谢

核心认证逻辑来源于 [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)。本仓库主要维护 Docker 离线部署、软路由适配、启动脚本、健康检查和发布包。
