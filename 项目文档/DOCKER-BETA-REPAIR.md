# Docker v1.4.0-beta.1 修复与本地测试记录

日期：2026-09-26。仅用于个人测试，未上传 GitHub。源码版本 `1.4.0-beta.1`，镜像标签 `esurfing-dialer:v1.4.0-beta.1`。

## 已完成的三组修复

| 范围 | 修改后的行为 | 验证 |
| --- | --- | --- |
| 协议响应与请求 | 安全 XML 解析；拒绝缺失、重复、嵌套、空字段及非法间隔；兼容 CDATA/实体；动态请求字段转义；响应读取和解密 XML 有上限 | 错误心跳不会确认登录，即使网络探测 SUCCESS；DTD/外部实体、超大/分块响应、特殊字符回归 |
| 动态门户 | 保存首跳 URL 和页面；有限跟随相对/绝对跳转，提取 auth/ticket 配置并验证端点/IP；无配置且合法 IP 才回退旧入口；探测客户端复用 | 本机 HTTP 首跳无 IP → 相对跳转 → 配置页；无效配置不回退；重复 IP、跳转环和超大页面失败 |
| 会话与原生生命周期 | 每次认证固定端点、IP、client-id、MAC、Algo-ID 请求头；探测只更新下次认证候选；使用/释放互斥；零句柄不调用 aid，初始化异常清理，一次性 free；退出终止并清理 | 真实 Client 搭配传输/原生替身，探测改变后心跳/term 保持旧参数；零句柄、aid 异常、清理异常、并发 use/free、重复 shutdown 回归 |

Docker 原有 Android 32 位架构、UA、原生库、持久设备身份、认证算法、计划器和恢复阈值保持不变。借用的是同仓库已验证的 Java/Kotlin 解析和生命周期边界，不跨产品替换原生通道。

## 本地验证

在 Docker 源码目录，以 JDK17 和本机 Gradle 8.4 运行：

```powershell
$env:JAVA_HOME='D:\Java\JDK17'
& 'C:\Users\FLEETING\.gradle\bootstrap\gradle-8.4\bin\gradle.bat' check shadowJar --offline --console=plain
```

结果：BUILD SUCCESSFUL；72 项专用回归通过。Kotlin 编译无警告，Gradle 提示现有插件未来不兼容 Gradle 9，暂不升级插件。JAR 的入口和两份原生库字节已核对，测试代码不包含在发布 JAR 中。回归状态仅写到忽略的 build/authentication-regression-state，不使用真实账户或现有 data。

## beta 打包与共存

本机 Docker daemon 未运行。使用 SHA256 已核对的现有 v1.3 amd64 镜像环境，在其文件层上追加本轮 JAR 和当前运行脚本；更新 rootfs、config、OCI index/manifest 及 Docker manifest.json。逐个内容摘要、层摘要、包内 JAR 和标签验证，不保留旧镜像的构建证明来声称新镜像已验证。

打包脚本：[package-beta.py](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/scripts/package-beta.py)。安装/个人测试步骤见 [README_BETA.md](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/README_BETA.md)。

正式版 `ESurfingDialer` 和测试版 `ESurfingDialer-Beta` 使用不同镜像标签、Compose 项目和目录。安装器不停止或替换正式版；检测到已存在同名 beta 时拒绝创建。beta 的 .env、data 和设备身份不与正式版共用。本包不启动可选 watchdog。

## 验证限制与遗留

- 本包只提供 linux/amd64 镜像；arm64 beta 尚未构建或验证。
- 未进行实际 docker load、容器启动、软路由部署或校园网端到端认证；未知现场 Algo-ID/算法不能用离线回归推定兼容。
- 正式/Beta 两个容器可以共存；同账号能否同时在线取决校园网策略，建议测试时自行选择启用哪个容器。
- watchdog 手动停止边界、health 时间戳、计划器采样/未来时刻、日志空间和持久化原子写入等后续项没有在本轮修复。

## 后续版本

本文保留 beta1 当时的验证范围与遗留情况。后续三组修复见 [v1.4.0-beta.2 修复记录](DOCKER-BETA2-REPAIR.md)。
