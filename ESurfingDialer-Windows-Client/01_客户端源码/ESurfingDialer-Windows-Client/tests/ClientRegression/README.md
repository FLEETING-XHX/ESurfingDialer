# 客户端离线回归与压力检查

从 Windows 客户端源码目录运行，需要本机现有 .NET 10 SDK 和已还原依赖。测试使用输出目录下独立的 test-data 和合成账户，不使用真实账户，不修改系统自启。

普通界面、配置和进程边界回归：

```powershell
dotnet run --project tests/ClientRegression/ClientRegression.csproj --no-restore -- artifacts/client-regression
```

同时执行 Java 子进程恢复回归和短时离线压力：

```powershell
dotnet run --project tests/ClientRegression/ClientRegression.csproj --no-restore -- artifacts/client-regression-stress --core --stress
```

--core 需要已构建的 core/client.jar 与 Java；本地 HTTP 服务替代联网探测，不进行校园网登录。--stress 使用 30 次含恢复的预热、200 次启停及 40 次恢复、2000 条界面事件、200 次配置保存，并按阶段采样测试进程资源。

## 结果与失败处理

- 退出码 0 表示本轮通过；非零表示失败。result.json 保存本轮结果与检查数量，机器字段使用英文。
- 失败时中文 stderr 与 failure.log 保留脱敏堆栈；普通托管异常与 WPF Dispatcher 异常均不能计为通过。
- 输出目录保留旧失败和截图，不自动清空；重复运行时以最新 result.json、退出码与对应日志判断本轮结果。
- resource-stress.json 保存托管堆、工作集、私有提交、句柄、线程与各阶段数据。资源是短时测试进程采样，不能证明真实登录后的内存或长期稳定性。
- 退出清理仅处理本轮创建且启动时间匹配的测试进程；清理失败同样返回失败。
- 新增测试覆盖密码失败、状态映射、账户事务、异常报告、进程所有权以及日志容量/轮转。内部故意失败模式由回归自动调用，正常看到其失败日志不代表父回归失败。

资源检查的句柄趋势比较两个等量循环批次，先完成包含恢复的预热并让 Dispatcher 消息正常运行；不以一次较冷的样本断言泄漏。真实连接、切网/休眠、72 小时与 7–14 天验收仍需现场。
