# Docker beta3 本地交付记录

日期：2026-10-07。版本：v1.4.0-beta.3。打包当时未提交或推送源码；测试包仅本地交付，未上传或公开发布。10-08后续源码同步不改变本页历史包。

后续交付：2026-10-08已按用户授权重新打包门户兼容修复，版本仍beta3，构建标识为`beta3-portal-fix-20261008`；请使用[新的交付记录](DOCKER-LOCAL-BETA3-PORTAL-2026-10-08.md)对应ZIP。本文保留10-07旧包的历史摘要与验证。

现场后续：用户于当天测试后仍在门户配置前置阶段失败，详见[失败与诊断记录](DOCKER-BETA3-PORTAL-FAILURE-2026-10-07.md)。本文的离线构建/包验证不能证明真实认证可用；后续源码诊断改动未进入本文记录的 ZIP。

## 交付产物

- 文件：`ESurfingDialer-Docker/00_最终部署包/Docker_v1.4.0-beta.3/ESurfingDialer-Docker-v1.4.0-beta.3.zip`
- 大小：238778490 字节。
- SHA256：`109d3cc6137a86feaf28b246703f8da8acb736fcdcfc2987ee0ca6ecd0e727be`。
- 支持 linux/amd64（x86_64），不支持 arm64。
- 容器 `ESurfingDialer-Beta3`；Compose 项目 `esurfing-dialer-beta3`；镜像 `esurfing-dialer:v1.4.0-beta.3`。
- 包含离线镜像、重建的 beta3 JAR、独立 Compose/配置模板、安装与启动脚本、可选运行镜像构建文件、中文 README、BUILDINFO 和文件校验和；不包含真实 .env/data、工具协作文件或测试类。

## 源码与版本对应

当前 Docker 核心包含 beta2 及 09-28/29 门户、探测/待认证请求/凭据修复。本轮调整版本与打包部署流程，不改原生库、UA 或认证算法。已从当前仓库 HEAD `8f4533e5d0b0d97091dbfc6b7195a332eb53d279` 创建 `codex/docker-beta3`；Docker 运行代码在此 HEAD 与原 Docker 基线 `2cd6af1` 一致，Windows 及全部既有未提交改动保留。

BUILDINFO 的 sourceRevision 表示合并仓库 HEAD，不是本次新提交；sourceDirty=true 反映版本、打包器和说明等未提交文件。sourceFilesSha256 保存当时 Docker 跟踪源码摘要，jarSha256 保存新 JAR 摘要。本轮仅创建新的 beta3 目录，未操作历史包；此前记录的 beta1/beta2 包路径当前未在工作区发现，未复核旧包摘要。

## 部署与交替启动

步骤见[beta3 包说明](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/README_BETA.md)。默认 `sh install.sh` 只导入镜像、生成独立配置并创建容器，不启动。准备测试时先 `docker stop ESurfingDialer`，再 `sh start-beta3.sh`；切回时先 `docker stop ESurfingDialer-Beta3`，再 `docker start ESurfingDialer`。启动脚本检查约定的正式版/beta1/beta2 名称，拒绝其他版本运行时启动 beta3；不自动停止它们。外部宿主任务、并发手动操作或绕过脚本的启动仍需自行核对。

包内 `docker-compose.build.yml` 与 `Dockerfile.runtime` 可用同一 beta3 JAR/脚本重建运行镜像。只构建不会启动；此路线需要联网获取基础镜像及 Alpine 软件包。本次交付的离线镜像以已校验 v1.3 运行环境追加新层生成，不声称实际运行过 docker build。

## 实际验证

工作目录：Docker 源码目录。日志：`build/beta3-verification/`，仅本机。

| 项目 | 结果 |
| --- | --- |
| JDK17 / Gradle8.4：check shadowJar --offline --console=plain | BUILD SUCCESSFUL，2分47秒；138项认证、113项运行策略、41项监督、18项日志检查通过 |
| 镜像 | 基础镜像 SHA256、所有 blob/rootfs 摘要、OCI/Docker 清单、Beta3标签、新 JAR 与脚本字节通过 |
| JAR | 入口、原生库资源字节、无回归测试类、构建材料中的同一 JAR 通过 |
| 真实 Docker Compose v5.5.1 config | 独立项目/容器/标签/data、host网络/重启策略，以及构建覆盖文件通过；不访问 daemon |
| 合成凭据 | 中文、空格、#、单/双引号、美元符号、连续/末尾反斜线、制表符，经交互安装与真实 Compose 原始插值环境保持原值；config的可复用序列化会再次转义美元符号 |
| 安装替身 | 仅create、不up/start；已有凭据与身份保留；同名容器/arm64拒绝，未调用真实daemon |
| 启动替身 | 正式版/beta1/beta2运行时拒绝；daemon不可用时退出；空闲时只启动beta3，不操作其他容器 |
| 包 | 全部文件校验和、ZIP CRC/内容对应、无真实配置/状态/本地协作文件通过 |

已有回归重跑通过，但没有补全09-29记录中全部网络探测/认证请求竞态行为矩阵；DOPT-01保留待办。D2本轮补充真实Compose解析，未验证实际容器内环境。没有进行真实 docker build/load/启动、软路由资源、校园网登录/心跳、未知算法兼容或长期稳定验收；DOPT-02/03实机部分及DOPT-04/05保留。
