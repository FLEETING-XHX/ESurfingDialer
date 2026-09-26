# C 项目对照与本轮修复记录

日期：2026-09-26。任务：REF-01。执行工具：Codex。适用于 Windows 2.3 开发源码，当时暂不打包；后续已按用户授权制作 v2.3beta，见 [测试版说明](V2.3BETA-RELEASE.md)。

## 对照对象与范围

用户提供的本地附件为 `ESurfingClient-CVersion-2.1.1-r4(另一个Github相关开源项目源码)`。附件 [README](../ESurfingClient-CVersion-2.1.1-r4(另一个Github相关开源项目源码)/README.md) 标注来源为 BadGhost520/ESurfingClient-CVersion，根 [LICENSE](../ESurfingClient-CVersion-2.1.1-r4(另一个Github相关开源项目源码)/LICENSE) 为 Apache-2.0。本轮对照的是当前本地文件，没有联网核对它与上游 Release 的一致性，也没有编译、运行或修改参考程序。

主要阅读 [DialerClient.c](../ESurfingClient-CVersion-2.1.1-r4(另一个Github相关开源项目源码)/esurfingclient/main/src/DialerClient.c) 中的 `auth`、`init_session`、`load_cipher`、`get_ticket`、`login`、`heartbeat`，以及 [PlatformUtils.c](../ESurfingClient-CVersion-2.1.1-r4(另一个Github相关开源项目源码)/esurfingclient/main/src/utils/PlatformUtils.c) 的 `xml_parser` 和 [CipherFactory.c](../ESurfingClient-CVersion-2.1.1-r4(另一个Github相关开源项目源码)/esurfingclient/main/src/cipher/CipherFactory.c) 的算法分派。

## 已落实的修复

| 对照发现 | 原有 Windows 行为与影响 | 本轮修复 |
| --- | --- | --- |
| C 的心跳在缺少 `interval` 时返回失败 | `substringAfter` 找不到字段仍返回原字符串，数字解析再回退，错误页可能被当作成功心跳，进而确认登录 | 用完整 XML 解析必需字段；缺失、重复、嵌套、空字段、畸形 XML、非正整数或溢出均明确失败。合法正数仍限制在 5 秒至当前配置上限 |
| C 的 ticket/login 分别检查必需标签 | ticket 缺结束标签仍可能流入登录；登录 URL 只接受紧贴的 CDATA，缺失标签可留下整段 XML | 验证 ticket、keep-url、term-url、keep-retry；URL 支持 CDATA 和 XML 实体，验证 HTTP(S) 地址后才整体设置会话字段 |
| C 在取门户配置后从 ticket URL 读 IP | 首次 Location 不含 IP 时，Windows 丢掉门户地址并持续等待 | 保留无 IP 的门户跳转或内联配置；认证时按已有请求上限读取最终配置并提取 IP。最终仍没有有效配置/IP 就失败，不使用空参数认证 |
| 对照暴露出请求与响应边界需要统一 | 密码或票据含 `&`、`<` 等字符会破坏 XML；后续加密响应采用无界 `string()` 读取 | 对所有动态请求 XML 字段转义；后续加密响应限制 1 MiB，解密后 XML 限制 64 Ki 个字符；禁用 DTD、外部实体、外部 Schema 和 XInclude |
| Windows 自身网络线程与会话共享 IP | 后台发现另一门户可能修改当前会话心跳/退出请求的 IP | 当前会话请求使用该次发现的不可变 `AuthenticationEndpoints.userIp`；新门户供下一次认证使用 |
| Windows 监督宽限比一次合法请求流程短 | 首次认证没有历史成功心跳，可能在 HTTP 请求或原生初始化仍执行时被增强模式重启 | 健康状态增加 portal/session/ticket/login/confirm 阶段与开始时间；当前进程、健康状态新鲜且关键线程存活时，每阶段最多宽限 90 秒。确认阶段每次有界尝试重新登记；成功、失败、会话重置清理标记 |
| 修复需要保留完整失败预算 | 若只在初始化失败时计数，ticket/login/确认中的确定性错误仍可能循环恢复 | 同类确定性认证错误累计三次后暂停；仅完整登录确认成功才重置内部预算；暂停状态保留分类错误和手动重连入口 |

实现位于 Windows 核心 `AuthenticationProtocol.kt`、`Client.kt`、`NetClient.kt`、`PortalConfiguration.kt`、`NetworkConnectivity.kt`、`DialerApp.kt`、`HealthStatus.kt` 和客户端 `HealthSnapshot.cs`、`EnhancedConnectionPolicy.cs`。没有将 C 的字符串解析器、算法代码、密钥、动态模块加载器或 Web 服务复制到本项目。

## 保留的差异与后续方向

1. 当前附件的 `load_cipher` 已包含动态 ZSM 路径，并说明头部 UUID 可能是模块 ID；`CipherFactory` 也包含 Android 新算法变体。候选 UUID 不能直接证明应该选择某个密码算法。下一步仍是现场格式、Algo-ID 和固定向量验证，再决定增加独立算法提供者或替换原生路径。
2. 参考 C 的 `xml_parser` 是首个起止标签截取；数字转换失败可能得到零。本轮独立实现了更完整的校验，没有照搬这些边界。
3. C 会按操作超时调整服务端间隔；本轮保留 Windows 已有间隔上下限，不引入没有现场时间数据支撑的扣减公式。
4. C 的详细日志包含响应或票据内容；本轮只记录分类错误、入口主机/端口和必要元数据。
5. 参考程序的跨系统实现、UA 通道和算法能力需要分别验证。Windows 仍使用现有 Android 64 位原生路径，Docker 的独立认证实现保持当前状态；新算法/动态模块尚未启用。

## 验证与限制

工作目录为 `ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client`：

```powershell
.\scripts\Build-Core.ps1 -Offline -Regression
dotnet run --project tests/ClientRegression/ClientRegression.csproj --no-restore -- artifacts/reference-repair-ui --core
```

- 核心构建通过：87 项认证检查、21 项恢复检查。覆盖 XML 错误/重复/嵌套/外部实体、特殊字符、间隔、非法 URL、空及超限加密响应、无 IP 跳转后的配置发现与阶段标记清理。
- Windows 回归通过：90 项检查，覆盖有时限的阶段宽限、旧进程/未来时间/未知阶段拒绝、线程死亡、过期健康、WPF、核心崩溃恢复、模式切换、手动停止和取消延迟恢复。截图在源码 `artifacts/reference-repair-ui/`。
- HTTP 与协议用例使用本机模拟服务；原生加载使用可注入替身。没有真实校园网端到端验证，没有证明新算法已经兼容，也没有生成安装包、提交、推送或发布。

接手方式见 [开发交接记录](DEVELOPMENT-HANDOFF.md)，现场证据需求见 [认证修复计划](AUTH-REPAIR-PLAN.md)。
