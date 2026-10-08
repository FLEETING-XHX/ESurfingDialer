# ESurfingDialer Docker（天翼校园软路由版）

面向 iStoreOS / OpenWrt 软路由的 ESurfingDialer Docker 离线部署版。

本仓库基于原项目整理、修复和打包：

- 原项目： [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)
- 统一项目主页： [ESurfingDialer（天翼校园）](https://github.com/FLEETING-XHX/ESurfingDialer)

## 下载

- 最新离线部署包：请前往统一仓库的 [Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases) 页面。

正式离线版为v1.3；最新本地beta3为`beta3-portal-fix-20261008`，未公开发布。已包含09-28/29及10-08门户兼容修复，用户一次登录/首次keep及上网通过，长期/恢复/注销仍待验收；[交付记录](../项目文档/DOCKER-LOCAL-BETA3-PORTAL-2026-10-08.md)保留包摘要与证据。使用正式包见[中文图文教程](02_使用教程/ESurfingDialer-Docker部署教程.md)，beta3安装默认只创建不启动，共存与交替启动见[测试说明](01_Docker源码/ESurfingDialer-Docker-Fixed/README_BETA.md)。

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
unzip ESurfingDialer-Docker-v1.3.zip
cd ESurfingDialer-Itsuwarii-Docker-Fixed-v1.3
sh install.sh
```

发布附件名称为 `ESurfingDialer-Docker-v1.3.zip`；解压后的目录名以实际包内容为准，进入包含 `install.sh` 的目录。安装脚本会检测架构、导入本地镜像、生成 `.env`、创建数据目录并启动容器。历史包安装器尚未包含 09-29 的特殊字符凭据转义修复，相关字符填写方法见[离线说明](01_Docker源码/ESurfingDialer-Docker-Fixed/README_OFFLINE.md)。

## 开发入口

后续计划见[中文文档索引](../项目文档/README.md)、[待优化清单](../项目文档/PENDING-OPTIMIZATIONS.md) 和 [未来开发方向](../项目文档/ROADMAP.md)。Windows 的认证修复有独立计划，不据此推断 Docker 需要同样的协议变更。

## 致谢

核心认证逻辑来源于 [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)。本仓库主要维护 Docker 离线部署、软路由适配、启动脚本、健康检查和发布包。
