# Windows 客户端认证会话初始化失败问题记录

记录日期：2026-09-23

当前状态：已复现并定位失败阶段，尚未修改代码

本次日志中观察到的版本：Windows 客户端 v2.1、v2.2

## 问题概述

部分 Windows 客户端在检测到校园网认证门户后，会长期显示“连接中”，但认证核心实际上没有完成登录。

日志表明，客户端能够启动认证核心、识别用户 IP 和 AC IP，并向 ticket 接口发起请求；失败发生在原生认证组件解析 ticket 响应、创建加密会话的阶段。会话 ID 返回 `0` 后，认证流程停止，后续的票据获取、账号密码提交和登录确认均不会执行。

增强连接功能能够检测到认证长期未确认，并按退避策略重启认证核心，但重启后仍在相同阶段失败，因此它只能触发恢复，不能解决会话初始化失败。

## 日志证据

本次日志包中的 `settings.json` 记录客户端版本为 `2.2.0.0`，可以确认测试者已经更新到 v2.2。

2026-09-23 的 v2.2 日志反复出现以下顺序：

```text
LOGIN_ATTEMPT
Initializing Android Mock...
Initializing Session...
LOGIN_FAILED failed to initialize session
```

对应的健康状态为：

```text
authenticated: false
lastLoginSuccessAt: 0
lastHeartbeatSuccessAt: 0
lastError: failed to initialize session
```

2026-09-21 的 v2.1 日志中已经存在相同的 `LOGIN_FAILED failed to initialize session`，因此该现象不是 v2.2 新增的健康检查或增强恢复逻辑造成的。

日志中没有出现 `Session ID`、`Ticket`、`LOGIN_SUCCESS` 或登录确认记录，说明失败发生在账号密码提交之前。现有证据不支持把问题归因于账号或密码错误。

## 代码路径

认证流程首先调用 `initSession()` 请求 ticket 接口，再把响应字节交给 `Session`。`Session` 调用原生 `DaMod.load([B)J` 创建会话；返回值为 `0` 时，客户端记录 `failed to initialize session` 并等待重试。

相关文件：

- [`Client.kt`](../core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/Client.kt)
- [`Session.kt`](../core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/hook/Session.kt)
- [`AndroidMock.kt`](../core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/hook/AndroidMock.kt)
- [`Constants.kt`](../core/ESurfingDialerCore/src/main/kotlin/com/rsplwe/esurfing/Constants.kt)

## 与 Docker／旧终端版本的差异

Windows 客户端与 Docker／旧终端版本虽然共用相似的业务流程，但认证兼容层并不相同：

| 项目 | Docker／旧终端路线 | Windows 客户端路线 |
| --- | --- | --- |
| User-Agent | `CCTP/Android8_vpn/2082` | `CCTP/android64_vpn/2085` |
| Portal Node | `125.88.59.131:10002` | `61.140.12.23:10002` |
| 模拟架构 | 32 位 | 64 位 |
| Android Resolver | API 19 | API 23 |
| 原生兼容处理 | 包含针对 `ipv4` 的 `strcmp` Hook | 未包含该 Hook |
| `libdaproxy.so` | 32 位路线使用的二进制 | 与 Docker 文件内容及 SHA-256 均不同 |
| Java 运行时 | Java 17 | Java 21 |

这些差异都位于或影响当前失败的会话初始化阶段。因此，“Docker 可以正常认证”不能证明 Windows 客户端的原生会话实现也能正确处理同一校园网返回的数据。

## 当前判断

已确认的直接原因是：Windows 客户端取得 ticket 接口响应后，原生认证组件未能创建有效会话，返回的 Session ID 为 `0`。

较高概率的原因包括：

1. Windows 客户端使用不同的 User-Agent 或 Portal Node，导致服务器返回了与其原生组件不匹配的响应。
2. Windows 客户端采用的 64 位 `libdaproxy.so` 或 64 位 Unidbg 模拟环境与当前响应不兼容。
3. Windows 路线缺少旧 32 位实现中的 `ipv4` 原生兼容 Hook，导致响应加载失败。

目前还不能在这三个方向中确定唯一根因，因为现有日志没有记录 ticket 响应的 HTTP `Content-Type`、响应长度或安全摘要，也没有同一网络环境下 Docker 与 Windows 的对照结果。

## 已排除或暂不支持的方向

- 不是单纯的 UI 状态显示问题：认证核心确实未完成登录。
- 不是测试者仍在运行 v2.1：导出配置明确记录为 v2.2.0.0。
- 暂无证据表明是账号或密码错误：失败发生在提交账号密码之前。
- 不是增强连接恢复本身导致：v2.1 已存在同一会话初始化失败，恢复重启只是重复触发该失败。

## 后续诊断建议

在不记录账号、密码、票据或响应正文的前提下，为 ticket 初始化阶段补充以下诊断信息：

1. 请求所使用的 User-Agent、Portal Node，以及脱敏后的用户 IP／AC IP。
2. HTTP 状态码和 `Content-Type`。
3. 响应长度与 SHA-256，不记录响应原文。
4. 原生 `load()` 返回的 Session ID。
5. 在同一账号、同一设备、同一网络时段下，分别采集 Docker／旧终端与 Windows 客户端结果。

取得对照数据后，再依次验证 User-Agent／Portal Node、32／64 位原生组件以及 `ipv4` Hook，避免同时修改多个变量而无法确认真正原因。
