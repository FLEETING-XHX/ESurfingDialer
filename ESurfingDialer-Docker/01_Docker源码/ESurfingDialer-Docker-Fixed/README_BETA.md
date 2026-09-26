# Docker v1.4.0-beta.1 个人测试包

这是本地测试版，没有发布到 GitHub。目前包含 **linux/amd64（x86_64）** 离线镜像，不含 arm64 镜像。

## 与正式版同时保留

| 项目 | 正式版 v1.3 | 本测试版 |
| --- | --- | --- |
| 容器 | ESurfingDialer | ESurfingDialer-Beta |
| Compose 项目 | 原有项目 | esurfing-dialer-beta |
| 镜像 | esurfing-dialer:v1.3 | esurfing-dialer:v1.4.0-beta.1 |
| 解压目录 | 原有目录 | ESurfingDialer-Docker-v1.4.0-beta.1 |
| 配置/状态 | 原有 .env 和 data | beta 目录下独立 .env 和 data |

把 ZIP 上传到软路由，**解压到新的目录**，不要覆盖正式版目录，不要共用 data，也不要复制正式版的 device-state.json。正式版安装目录、镜像、容器和设备身份均保留。

```sh
unzip ESurfingDialer-Docker-v1.4.0-beta.1.zip
cd ESurfingDialer-Docker-v1.4.0-beta.1
sha256sum -c SHA256SUMS
sh install.sh
```

安装器会询问测试账号、密码，仅导入 beta 镜像并创建 `ESurfingDialer-Beta`，不下载任何依赖。已存在同名 beta 容器时会停止安装；更新它请在当前 beta 目录使用下方明确项目名称的 Compose 命令。

两个容器可以同时存在，也可以同时运行；同一账号是否允许同时认证取决于校园网策略，可能发生互踢。为了分清测试结果，可以保留正式版容器、测试时自行暂停它，测试结束再启动。beta 安装器不会停止正式版。

## 查看、停止、恢复 beta

```sh
docker logs --tail 100 ESurfingDialer-Beta
cat data/health.json
docker compose --project-name esurfing-dialer-beta stop
docker compose --project-name esurfing-dialer-beta up -d --no-build --pull never
```

如需手动创建：

```sh
gzip -dc images/esurfing-dialer-v1.4.0-beta.1-amd64.tar.gz | docker load
cp .env.example .env
vi .env
docker compose --project-name esurfing-dialer-beta up -d --no-build --pull never
```

`.env` 中填写 `DIALER_USER` 和 `DIALER_PASSWORD`。请使用包内 Compose 文件，它已设置独立容器名和项目名。本包不附带或自动启动宿主 watchdog，以免影响手动停止的 beta/正式版。

## 本次修复与测试重点

1. ticket/login/heartbeat 改为安全 XML 解析，必需字段和间隔校验，账号、密码、ticket 等动态字段转义。无效心跳不能确认登录。
2. 保留缺 IP 的门户跳转，有限跟随并发现 auth/ticket 配置；无配置且 IP 合法时才回退旧入口。动态入口、响应大小均有校验。
3. 会话固定门户、IP、设备身份和请求头；新探测只更新候选。原生零句柄不再调用 aid，初始化失败清理，使用和释放互斥，释放只执行一次。

建议记录首次登录、正常心跳、校园网强制下线后的恢复、网络 IP 改变、停止/重启结果。只提供去掉账号、密码、ticket 和完整敏感 URL 的日志；不要分享 .env 或设备状态文件。

## 验证范围

已进行 JDK17 构建、离线协议/HTTP/原生操作替身回归及镜像内容/校验和验证。镜像沿用已校验 v1.3 amd64 运行环境，增加新 JAR 与当前入口脚本；OCI 与 Docker manifest 同时更新，构建信息见 BUILDINFO.json。镜像归档结构按 [OCI image layout](https://github.com/opencontainers/image-spec/blob/main/image-layout.md) 校验。

打包电脑未运行 Docker daemon，没有实际 docker load/容器启动或校园网认证测试。当前原生库、32 位 Android 模拟架构和 UA 保持原样；未知现场算法尚未验证，不能把离线测试通过理解为校园网认证已恢复。
