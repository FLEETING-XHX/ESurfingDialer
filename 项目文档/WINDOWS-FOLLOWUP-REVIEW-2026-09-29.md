# Windows 八项问题的修复前检查（2026-09-29）

状态更新：2026-10-08。本记录的 S1、W1–W7 已完成源码修复，并进入本地 **v2.3beta.2**，不再作为待开发问题。修复结果见[八项修复记录](WINDOWS-CLIENT-FIXES-2026-09-29.md)；新包还包含六项离线修复和 EXE 入口修复，188 项包内检查及独立启动通过，见[本地交付记录](WINDOWS-LOCAL-BETA-2026-09-30.md)。公开 v2.3beta 仍为旧包。

下文仅保留修复前根因与复现证据。当前未完成的真实认证、恢复、安装和长期验收统一见[待优化清单](PENDING-OPTIMIZATIONS.md)，不沿用当时的实施顺序。

## 当时的审查范围

审查对象为 Windows v2.3beta 源码，基线 `codex/docker-beta / 4f10b0b`，当时 Windows 产品源码无未提交改动。编号沿用[09-28 审查](BETA-CLIENT-REVIEW-2026-09-28.md)。Docker 修复仅作设计对照，未移植协议、UA 或原生库。

W1、W5 用当时 C# 源码构建并执行隔离状态检查；其余项由源码控制流与回归覆盖确认。这些证据均不是校园网复现，P1/P2 为当轮修复优先级。

## 修复前根因

以下行号对应当时源码，仅用于追溯。

| 编号 | 优先级 | 触发条件与已确认原因 | 当时位置 |
| --- | --- | --- | --- |
| S1 | P1 | HTTP 200 无已识别门户配置/IP 时，`parsePortalBody` 返回 null，调用方立即判 SUCCESS；登录 HTML、JS 跳转或错误页会遮蔽认证，也不再尝试后续探测地址 | 核心 `utils/NetworkConnectivity.kt:67–71`、`Client.kt:105–110` |
| W1 | P1 | 心跳/JNI 挂起时线程仍存活，独立健康线程继续刷新文件；已认证加新鲜文件被当作在线，缺少心跳及重认证前 `term()` 的进展期限 | `HealthSnapshot.cs:80–81`、`EnhancedConnectionPolicy.cs:45–46`；核心 `Client.kt:121–144`、`HealthStatus.kt:44–57` |
| W5 | P1 | authenticated=true 但关键线程已停止时，前半段先 `RecordHealthyConnection()` 清空计数，后半段只累积一次异常；每轮重复，始终达不到恢复门槛 | `MainWindow.xaml.cs:203–229,256–279`、`EnhancedConnectionPolicy.cs:41–44` |
| W2 | P1 | 精确标签正则拒绝合法空白/属性/混合 CDATA；坏内联缓存在 GET 前抛错，阻止重新发现；HTTP 200 门户正文未保存来源 URL | 核心 `PortalConfiguration.kt:40–44,65`、`utils/NetworkConnectivity.kt:105–114`、`Client.kt:50–55` |
| W6 | P1 | REQUEST_ERROR/DEFAULT 先于待认证请求执行；请求只有版本、没有门户快照，已接受 B 可被未达防抖的 C 覆盖；认证 A 期间观测到 B 也可能被 A 的 SUCCESS 掩盖 | 核心 `Client.kt:85–102,148–161`、`States.kt:18,33–45`、`DialerApp.kt:94–118` |
| W3 | P2 | 无配置回退只采用首跳 IP，遗漏最终 URL 中完整 IP 对，可能报 PORTAL_IP_INVALID 或沿用旧 IP | 核心 `PortalConfiguration.kt:67–79` |
| W4 | P2 | 为恢复停止旧核心后，新启动暂时失败会清除 `_requested` 并取消恢复；日志称继续退避，但后续条件要求 requested/running，实际无重试任务 | `MainWindow.xaml.cs:219,295–301`、`DialerCoreProcess.cs:52–58` |
| W7 | P2 | 启动前无跨进程单实例保护；同配置目录共享账户、日志和 health.json，新核心删除/竞争健康文件，时间判断无法隔离两个存活实例 | `App.xaml.cs:12–19`、`AppPaths.cs:7–18`、`MainWindow.xaml.cs:86`、`DialerCoreProcess.cs:65–68` |

W6 说明 REC-02 的“请求版本不被 SUCCESS 清除”仍不足以覆盖完整交接：执行优先级、已接受门户快照及认证完成确认必须同时正确。W7 的重复认证是否造成校园网互踢取决于现场策略，当轮没有实测。

## 保留的隔离复现

W1 构造进程/线程存活、文件新鲜、上次心跳为 20 分钟前的快照，当时策略返回：

```text
connected=True, recoveryReason=null
```

当时 `tests/ClientRegression/Program.cs:65–67` 还明确接受该陈旧心跳状态。这个复现只证明判定缺口，没有制造真实 JNI 卡死；修复后的合法心跳间隔与有界操作期限见八项修复记录。

W5 使用合成健康文件和只等待标准输入的替身进程，调用真实 `MainWindow.RefreshHealthAsync()`，连续三轮均返回：

```text
unhealthyChecks=1 recoveryAttempts=0 status=已连接
```

认证线程捕获 `InterruptedException` 后会在 finally 中将 clientThreadAlive 改为 false，却不清除 authenticated，其他线程仍可刷新健康文件，因此该状态存在生产控制流来源。

夹具位于 Windows 工程 `artifacts/windows-review-20260929`，当时执行以下命令，退出码为 0：

```powershell
dotnet restore artifacts/windows-review-20260929/Review.csproj --configfile artifacts/windows-review-20260929/NuGet.Config --verbosity quiet
dotnet run --project artifacts/windows-review-20260929/Review.csproj --no-restore --verbosity quiet --no-launch-profile
```

当轮没有改产品源码或更新安装包；检查退出成功表示复现缺陷。随后完成修复的 167 项认证、63 项恢复、121 项客户端检查，以及 v2.3beta.2 的 188 项包内检查属于后续结果。真实校园网、JNI 停滞、切网/休眠、升级卸载和长期资源验收仍按统一待办推进。
