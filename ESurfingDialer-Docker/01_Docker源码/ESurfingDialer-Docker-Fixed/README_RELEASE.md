# Docker v2.4 离线部署说明

下载 `ESurfingDialer-Docker-v2.4.zip`，解压并进入包含 `install.sh` 的目录。本包仅支持 **Linux amd64 / x86_64**，软路由需要已安装 Docker 和 Docker Compose，至少留出 1 GB 空间。镜像、程序和脚本已包含，不需要公网拉取镜像。

1. 首次安装运行 `sh install.sh`，按提示输入校园网账号和密码。安装只创建容器，暂不认证。
2. 停止已有天翼校园客户端或旧 Docker 认证容器，再运行 `sh start.sh`。
3. 运行 `docker logs --tail 100 ESurfingDialer-v2.4` 查看日志；只有 `LOGIN_CONFIRMED` / `LOGIN_SUCCESS` 表示登录已确认。
4. 停止使用 `docker compose --project-name esurfing-dialer-v24 stop`；启动使用 `sh start.sh`。不要同时启动两个认证版本。

## 从旧版本迁移

先停止旧容器并备份其 `.env` 与整个 `data` 目录，再复制到本包目录，保留设备身份和计划记录。备份含账户信息，请妥善保存。安装器保留既有凭据和重认证设置，只更新镜像标签、补缺失配置项；已有密码若手动填写，`$` 写成 `$$`、反斜线写成 `\\`、双引号写成 `\"`。旧部署目录及容器保留，确认新版可用后再自行整理。

如果本包容器已经存在，安装器会拒绝重复创建。保留配置及 `data`，在原 v2.4 目录执行 `sh start.sh --force-recreate` 更新；先确认其他认证容器已停止。不要执行 `down -v` 或删除 `data/device-state.json`。

## 版本与验证范围

v2.4 汇入门户兼容、会话恢复、健康监督、计划重认证、日志限额以及 10-10 审查修复，包括恢复上限退出锁等待。Windows 与 Docker 认证核心分别维护，没有跨产品替换原生库或算法。

镜像沿用已校验的 v1.3 amd64 Java 17 / Alpine 运行环境，追加当前 JAR 和运行脚本；`BUILDINFO.json` 记录来源和摘要，`SHA256SUMS` 可用于校验。离线包已验证 OCI/Docker 清单、层摘要与程序一致性；维护机没有运行 Docker daemon 的导入/启动验收。此前 beta3 已由用户完成一次真实登录、确认心跳及外网访问；v2.4 的真实恢复、注销和长期稳定仍需继续验收，不能将 v1.3 的 14 天结果套用于 v2.4。

运行日志在 `data/logs`，健康状态在 `data/health.json`。反馈请提供版本、时间、步骤和脱敏日志，不公开账号、密码、票据、密钥或原始 ZSM。
