# Windows 连接恢复与原生会话修复记录

修复日期：2026-09-26，任务 REC-02。状态更新：2026-10-08。本文保留根因、行为边界和当轮回归依据；已完成步骤不再列作下一轮开发任务，剩余验收统一见[待优化清单](PENDING-OPTIMIZATIONS.md)。

本轮在修复前检查点 `c99dce5` 之后实施，随后纳入公开 v2.3beta。09-29 又补齐请求快照、执行优先级及进展监督等遗漏，见[八项修复记录](WINDOWS-CLIENT-FIXES-2026-09-29.md)。这些与六项离线修复、EXE 入口修复均已进入[本地 v2.3beta.2](WINDOWS-LOCAL-BETA-2026-09-30.md)，188 项包内检查及独立启动通过；本地新包未推送或发布。

## 根因与修复后的行为

| 已确认问题 | REC-02 修复结果 |
| --- | --- |
| 已登录后遇到缺 IP 门户只报错，不能恢复 | 带门户地址/内联配置的结果进入同一防抖策略，允许读取最终配置；没有门户证据不触发认证 |
| 新网络仍受旧会话登录宽限/冷却限制 | 连续两次发现同一有效新用户 IP/AC IP 才申请恢复；单次、交替及夹杂成功/错误的探测不累计。同网仍遵守原宽限/冷却 |
| 网络成功或较早认证完成覆盖已接受恢复 | 使用单调版本号保留请求，普通网络成功不能清除，较早认证不能确认较新请求。完整候选快照与执行交接由后续 W6 补齐 |
| 唤醒直接采用旧健康文件，可能提前重启 | 系统恢复只向已请求且存活的核心发 `network recheck`，唤醒等待并刷新健康；固定 20 秒监督窗口采用单调计时，不随轮询延长 |
| 手动停止后延时恢复仍等待或继续执行 | 取消延时任务本身；停止或待恢复时关闭增强模式立即撤销，保留进程代数检查以阻止旧账号任务复活 |
| 原生会话在使用中释放或重复 free | `NativeSessionHandle` 串行化使用/释放；异常释放也不再次 free，释放后拒绝使用；加解密异常只输出分类，文本统一 UTF-8 |
| ZSM 诊断耦合原生加载，格式信息不足 | 独立 `ZsmHeaderReader`，只读有界头字段与固定类型/长度字，记录动态打包候选，未知布局仍交原生提供者 |

动态 ZSM 只作诊断。头部 UUID 可能是模块 ID，不能直接选算法；没有解压/执行模块、导入 C 密钥或启用新算法，兼容性未知项仍为 AUTH-INIT。

## 资源与停止边界

- 常态网络与健康写入频率未增加；恢复通知才触发额外重查。门户只持有一个候选与有上限计数，请求版本不保存历史账号内容。
- 退出解除静态电源事件订阅，避免窗口被事件持有；当时依据为 [Microsoft SystemEvents 文档](https://learn.microsoft.com/en-us/dotnet/api/microsoft.win32.systemevents.powermodechanged?view=windowsdesktop-10.0)。
- 手动停止后，唤醒不会启动核心，也不清除确定性认证失败的暂停预算。真实认证后的资源与长期行为仍需实测。

## 当轮验证

在 Windows 源码目录执行：

```powershell
.\scripts\Build-Core.ps1 -Offline -Regression
dotnet build app/ESurfingDialerLite/ESurfingDialerLite.csproj --no-restore
dotnet run --project tests/ClientRegression/ClientRegression.csproj --no-restore -- artifacts/recovery-repair-ui --core
```

核心离线构建成功，109 项认证、46 项恢复检查通过；客户端编译 0 警告/0 错误，103 项回归通过。覆盖门户一致性、防抖、版本保留、原生使用/释放并发、重复/异常释放、有界格式头、唤醒窗口、保守模式立即重查、崩溃和停止。历史发布包检查数见[v2.3beta 测试说明](V2.3BETA-RELEASE.md)，最新包结果见上方交付记录。

原生操作使用可注入替身，网络与 Java 子进程使用本机模拟 HTTP；唤醒使用回调和真实控制命令，没有实际系统休眠或校园网登录。截图保存在 Windows 工程 `artifacts/recovery-repair-ui/`。当轮未改变 Docker 协议，未操作外部 C 参考实现。

现场算法证据、真实登录/心跳、切网/休眠/停止及长期资源的剩余条件见统一待办 WOPT-01/03/04；原因和行为继续按[稳定性基线](WINDOWS-STABILITY-AND-MIGRATION-BASELINE.md)记录，不重复建立已完成修复任务。
