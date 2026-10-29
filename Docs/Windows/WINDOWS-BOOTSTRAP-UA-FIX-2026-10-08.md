# Windows beta3 初始化空响应：UA 通道修复（2026-10-08）

用户先要求只排查191124新日志，随后授权开始修复。当前在 `codex/windows-auth-review-20261008`，保留既有修改；本轮不提交、推送或公开发布。此前beta3门户修复有效，本次新增阻断发生在初始化下载，详见[现场记录](WINDOWS-AUTH-SESSION-ISSUE.md)。

10-09现场更新：用户新日志已完成真实登录及首次确认心跳，并确认可访问外网、日志正常。下面保留10-08修复/打包时的验证边界，新现场结果见文末。

## 已确认原因与受控对照

Windows固定UA为 `CCTP/android64_vpn/2085`，当前公开初始化接口在该通道返回HTTP200、text/html、chunked、0字节正文，因此客户端按实际空字节报 `ZSM_EMPTY`。不能把HTTP200当模块下载成功，也不是原生模块先加载后拒绝。

在同一公开ticket入口使用合成Client-ID、文档测试地址、全零Algo-ID及相同正文/MD5/请求头，不提交账号密码，仅改变UA。首次两行属于同一次对照；后续三行是另一测试身份下的同组对照，不能按字节数推断算法。

| UA | HTTP / 类型 | 实际正文 |
| --- | --- | --- |
| CCTP/android64_vpn/2085 | 200 / text/html | 0字节 |
| CCTP/Android8_vpn/2082（Docker） | 200 / application/octet-stream | 19294字节 |
| CCTP/android64_vpn/2093（C参考旧值） | 200 / text/html | 0字节 |
| CCTP/android11_64/2104（C参考当前值） | 200 / application/octet-stream | 19922字节 |
| CCTP/android64_vpn/2085（再次对照） | 200 / text/html | 0字节 |

这确认旧UA与本次空初始化响应之间的因果差异；没有取得官方停用公告，不能推断停用日期或其他接口/学校的支持政策。外部C仅提供待验证通道字符串线索，没有移植其密码学代码、猜测密钥或替换算法。

## 修复及验证

- 生产源码仅修改Windows `Constants.USER_AGENT` 为 `CCTP/android11_64/2104`，由门户发现与全部认证POST共同使用。保留Windows ARM64库、JNI/模拟架构、初始化正文/校验头、门户地址及重试策略；Docker源码不变。
- 新增 `BootstrapCompatibilityRegression`，重放旧通道HTTP200/空正文，实际调用生产bootstrap验证可用64位通道、模块字节、元数据、POST正文、MD5、身份/Algo-ID、Accept及Content-Type。修改前认证回归退出1，失败于收到空模块；修改后327项认证、63项恢复通过。
- 使用首次beta3原包JAR内的原ARM64库，在独立STATE_DIR验证新64位响应可创建有效原生会话并执行加密，最终释放会话/模拟器。没有将Docker ARM32响应直接交给Windows库作为修复。
- 使用新构建JAR的真实 `requestSessionBootstrap`、`Session` 与 `post`：收到19922字节初始化模块，原生会话/有效Algo-ID通过；合成设备/测试地址的加密票据请求获得真实服务端响应，成功解密并经 `AuthenticationProtocol.ticket` 解析，票据仅在内存验证非空，不输出或保存。未提交真实账号密码、登录、确认心跳或执行注销。
- 初期探针的“客户端自加密再自解密必须完全相等”试验未满足，不能据此判为生产算法故障；未据此修改解密/填充逻辑。本轮兼容依据采用上面的真实服务端票据响应解密/结构解析，用户登录仍需独立验收。
- 首轮打包在既有“崩溃后自动重启”客户端回归失败，未交付。定位测试宿主在关闭WPF后仍保留该DispatcherSynchronizationContext，`HasExited`同步引发退出回调时，`await Task.Yield()`续体可能被投递到已关闭Dispatcher。新增实际续体检查在修复前稳定失败，现于UI清理finally恢复之前的上下文，保留原12秒等待/原恢复断言；仅改测试Program.cs，不改C#生产恢复逻辑。失败候选及日志保留。

最终构建/交付JAR SHA256：`b1f0285a55e4c1728143a713534db0cf682f85d0a518fdeee651ce7135af3dff`。原生资源SHA256仍为 `656ace25d47cc453685cc4109b5f4578d466b219d759008ac5720244ee899722`。

局部日志与探针位于忽略的工程 `artifacts/bootstrap-fix-20261008`；不保存原始ZSM、真实身份/账号、票据值或完整敏感URL。受控网络实验使用合成数据，不能称为用户校园网认证成功。

## 本地修复包

沿用已授权beta3版本，显示/文件版本仍为 `2.3beta.3` / `2.3.0.3`。最终交付已完成，首次beta3和首轮失败候选保留；不能只根据显示版本区分新旧包，请核对UA2104目录/README和安装器摘要。

```text
artifacts/packages/Windows/Windows客户端_v2.3beta.3_UA2104修复版_20261008/
  ESurfingDialer-Windows-v2.3beta.3-Setup.exe
  README_TEST.txt / SHA256SUMS.txt / BUILD-MANIFEST.json
  PUBLISHED-FILES.json / SOURCE-FILES.json / SOURCE-INPUTS.zip / 本地验证结果/
```

| 项目 | 实际结果 |
| --- | --- |
| 安装器大小 / SHA256 | 157776692字节；`efe2d2685fd2021d0d4895314da34c0e0b2651bdca0a95c6a36ff3046fe01fc7` |
| 发布目录 | 工程 `dist/v2.3beta.3-ua2104-final/ESurfingDialerLite`，885文件、315359342字节 |
| 核心/恢复/发布客户端回归 | 327 / 63 / 189项通过，含短压和新增关闭UI后续体检查 |
| 启动门禁 / 实际EXE | 13项通过；InputIdle、WPF窗口响应、配置不变、进程清理均通过，确认包内coreclr加载 |
| 源码与保护 | 104项源码输入/归档逐项匹配；80份Docker/旧交付文件、36项本轮范围外既有改动/删除保持 |
| 运行时 | 包内Java21.0.11、.NET/WPF10.0.12，Windows x64自包含 |
| 最终包无账号验证 | 用最终包Java/JAR/原生库，收到18705字节初始化模块，创建有效会话、执行加密、解密并解析真实票据响应，释放完成；未做用户账号登录 |

统一入口 `scripts/Build-LocalBeta.ps1 -Offline` 以新的OutputRoot/ValidationDirectory/DeliveryDirectory运行退出0，最终验证在工程 `artifacts/local-beta3-ua2104-final-20261008`。独立 `artifacts/bootstrap-fix-20261008/verify_fix.py`退出0，核对安装器、885项发布文件、104项源码归档/当前输入、4个生产类的2104常量及原生库摘要；无Regression类进入JAR。首次失败候选为 `dist/v2.3beta.3-ua2104`，对应失败验证记录保留，不作为交付。

没有安装到用户程序目录或验证升级/卸载/安装器提取。完全退出旧客户端后安装本修复包，复测导出日志的 `LOGIN_ATTEMPT` 应显示 `CCTP/android11_64/2104`；服务端模块长度可变化，不以固定字节数作为成功条件。

打包时真实账号登录、首次确认心跳尚未验收；10-09补齐本次现场通过证据。后续定时心跳、切网/休眠恢复、注销确认及72小时/7–14天稳定性仍见[统一待办](../修复与优化总记录.md#windows-todo)。官方故障码及设置页服务入口继续暂缓。

<a id="site-20261009"></a>

## 2026-10-09 现场验收

- 用户提供`ESurfingDialer-logs-20261009-155457.zip`，并确认外网可访问、日志正确。ZIP SHA256：`34ef1b9e3a70d24c4dbdf2b2e33b15cbc1556b626c2b70e94a12b2a2d9bd5571`；只读核查，不公开原始日志。
- 导出版本2.3.0.3、UA为`CCTP/android11_64/2104`。初始化取得200/application/octet-stream和17215字节；随后SESSION_READY、TICKET_READY、LOGIN_CONFIRMED attempt=1、LOGIN_SUCCESS均出现。版本/UA与修复版链路吻合，ZIP不含安装器摘要。
- 15:51:40登录成功；确认事件由成功的keep心跳之后输出。登录后约2分45秒，15:54:25用户主动停止，与CLIENT_THREAD_EXITED、Shutting down一致，未见认证错误、心跳失败或异常恢复。
- 健康记录最后更新15:54:25、网络检查15:54:21，登录/心跳成功时间均15:51:40，失败数0、错误为空；进程及两条线程均已退出。authenticated=true保留最后认证状态，不证明停止后仍在线或服务端已注销。
- 本次真实登录、首次确认心跳及外网通过。核心日志跨度2分48秒，没有后续定时心跳/恢复证据；已在首次后续心跳到期前停止，不能重复计算健康时间为第二次心跳。连续心跳、恢复、注销和长期资源继续验收，WOPT-01只更新本次通过范围。
