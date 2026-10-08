# Windows 原生算法与认证流程核查（2026-09-30）

状态更新：2026-10-08。本文保留当轮算法一致性与兼容风险的依据；重复的开发方向已合并到[待优化清单](PENDING-OPTIMIZATIONS.md)。后续八项运行修复、六项离线修复和实际 EXE 入口修复已进入[本地 v2.3beta.2](WINDOWS-LOCAL-BETA-2026-09-30.md)，188 项包内检查与独立启动通过，未推送或发布。Windows 真实校园网及长期验收仍未完成。

## 核查范围与结论

当轮分支 `codex/windows-client-fixes`，HEAD `2cd6af1`，包含当时尚未提交的 09-29 八项修复。仅核查 Git 历史、工作区 diff、原生资源与调用路径，没有修改运行代码或重新运行回归。

仓库最早 Windows 提交 `123cacf`、合并导入提交 `8155656`、当时 HEAD 与工作区之间，原生库、认证常量、Android 模拟器与 JNI 适配保持一致。加解密仍由原生 `DaMod` 的 `enc/dec` 执行，没有引入参考 C 项目的新算法实现。结论限定于本仓库保存的历史，不能证明服务端现有算法仍兼容，也不能排除认证流程改动的回归。

## 原生路线一致性的证据

初始文件在 `01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore`；导入后位于 `ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client/core/ESurfingDialerCore`。

| 核查文件 | 三个提交中一致的 Git blob | 当轮工作区结果 |
| --- | --- | --- |
| `src/main/resources/libdaproxy.so` | `1accf908f4b72739e316b8b4bcd42c2b9aed4550` | 无差异，二进制 hash-object 相同 |
| `Constants.kt` | `e2b5ddb947f27fd6af2bc570a73b5720b0a9b651` | 相对导入提交无差异 |
| `hook/AndroidMock.kt` | `5d2a5dcce218f5ba50b9025db3a3480afef422ff` | 相对导入提交无差异 |
| `hook/ESurfingJni.kt` | `cf9aaacb9054afcd9584b926812dd7402b66f460` | 相对导入提交无差异 |

源码与当时本地构建 JAR 中 `libdaproxy.so` 的 SHA256 均为：

```text
656ace25d47cc453685cc4109b5f4578d466b219d759008ac5720244ee899722
```

UA 为 `CCTP/android64_vpn/2085`，模拟器为 64 位、Android Resolver API 23。`Session.kt` 仍调用 `load([B)J`、`aid(J)Ljava/lang/String;`、`enc(J[B)[B` 和 `dec(J[B)[B`；包装层增加零句柄拒绝、异常分类、使用/释放互斥、一次释放、明确 UTF-8、释放方法调用及可注入接口。相关构建 diff 未修改依赖版本声明；当轮未重新解析依赖或核对所有运行时二进制。

ZSM 读取器只提取有界格式元数据与 UUID 候选，检查后仍将原始字节传给原生加载方法。没有按候选 UUID 切换算法、解压或执行独立模块；bootstrap 算法值仍重置为全零 UUID，后续 Algo-ID 来自原生 `aid()`。

## 确实改变的兼容边界

| 范围 | 流程变化与仍需现场核对的内容 |
| --- | --- |
| 门户与地址 | 固定入口改为动态发现，增加结构/地址/IP 校验、坏缓存重取与末跳参数读取；活动会话使用自己的端点/IP 快照 |
| 初始化 | 增加 HTTP 状态、大小、明显错误文本与截断检查；原生 Algo-ID 必须为 UUID 格式。真实合法响应若不满足假设，会在加解密前被拒绝 |
| 报文与响应 | 保留 ticket/login/heartbeat 字段，动态值增加 XML 转义，响应改为完整 XML 必需字段、层级、URL 与正整数检查；学校非标准结构仍须受控样本验证 |
| 传输与确认 | 认证 POST 不跟随重定向，增加请求总超时；登录必须经 keep 确认，同类确定性失败第三次后暂停，用户断开重连可重试 |
| 恢复与调度 | 增加防抖、请求快照、停止取消、单调计时、进展监督与进程恢复边界，改变会话何时开始/终止；没有替换原生加解密 |

09-23 的 v2.1/v2.2 日志已在 ticket 响应后的原生初始化阶段失败，早于 09-26/29 修复；不能将后两轮改动认定为既有失败起因，也不能因此排除其他历史或新流程回归。

## 后续状态与验收入口

当轮提出的状态反馈、密码提示、账户失败事务、进程边界和交付一致性已有 OFF 系列与本地包验证接续，不再列成未实现功能。单实例、增强恢复、多账户及日志导出也已有实现。实际安装/升级/DPI、长期资源、运行时缩减、完整协议规格和后续扩展仅在[统一待办](PENDING-OPTIMIZATIONS.md)维护。

认证兼容仍按[认证验收计划](AUTH-REPAIR-PLAN.md)取得同网证据。若确需新算法，先核对来源和固定向量，再完成真实会话及心跳；原生库摘要一致、本机 HTTP 和原生替身检查均不能代替该验收。Windows 的故障与迁移约束继续沿用[稳定性行为基线](WINDOWS-STABILITY-AND-MIGRATION-BASELINE.md)。
