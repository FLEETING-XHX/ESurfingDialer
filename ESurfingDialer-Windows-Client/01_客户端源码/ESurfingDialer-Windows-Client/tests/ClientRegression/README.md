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

## Pen 界面与窗口缩放检查

只核对界面、内嵌字体、设置跳转和实际窗口尺寸，不启动认证核心：

```powershell
dotnet build tests/ClientRegression/ClientRegression.csproj --no-restore -c Release
dotnet run --project tests/ClientRegression/ClientRegression.csproj --no-build --no-restore -c Release -- artifacts/pen-ui-check-new --ui-only
```

输出目录必须是全新目录。使用独立账户夹具和浏览器替身，检查按实际显示器DPI/工作区适配的首开尺寸、比例与边界、拖动/最小尺寸/后台恢复、最大化禁用、无标题栏按钮、导航固定、上下渐隐与键盘焦点可达、后台提示保存、账户单层遮罩及圆角像素、内嵌字体；不写用户配置或系统自启。10-10适配后191项UI检查及304项普通回归通过。`continuous-resize.json`记录八边/角640次连续尺寸更新，核对增长/缩小方向、比例及拖动期间无原生圆角重设。`compact-*.png`是真实窗口客户区，含设置入口获得焦点后的无外框截图；`pen-*.png`是449×912稿件对照。只有`pen-home.png`暂用原稿曲线和账户文字，截图后立即恢复，不用于产品运行。

`startup-size.json` 保存实际工作区、DPI、期望与实际启动尺寸；`window-metrics.json` 保存 HWND 外框、客户区、DPI 和画布尺寸。以125% DPI下449×912为舒适基准，首开跟随系统缩放并限制为扣除边缘余量后工作区的90%，最小拖动尺寸为适配首开尺寸的80%。当前实机工作区2560×1380、120 DPI，仍为449×912。67项新增适配检查覆盖低分辨率、96–288 DPI、窄工作区和副屏偏移；这些是离线运算，跨DPI显示器的实际拖动仍需验收。实际窗口断言读取所在显示器工作区，不再固定要求所有屏幕均为449×912。

字体维护使用现有 Python 和 fontTools：`python scripts/Update-UiFonts.py --source 本地NotoSansSC可变字体路径`。仅在新目录生成待审阅字体，默认 `artifacts/fonts-rebuild`，不会覆盖正式字体、联网下载或自动安装依赖；参数见 `--help`。正式采用前核对覆盖、许可和 WPF 实际字重。

## 发布 EXE 启动检查

上面的回归直接创建 MainWindow，不会执行 App.OnStartup。打包后还需检查实际程序入口，防止窗口回归通过但 EXE 启动失败：

```powershell
./tests/ClientRegression/Test-PublishedStartup.ps1 -PublishedDirectory ./dist/v2.3beta.3/ESurfingDialerLite -OutputDirectory ./artifacts/published-startup-beta3
```

脚本使用独立空账户配置、禁止自动连接，启动包内 EXE，严格要求消息循环就绪、本次PID的WPF主窗口存在且响应消息；观察后检查启动日志、配置未改变及没有核心健康文件。结束本轮进程树、确认退出、释放句柄并二次核对配置后才写通过；失败记录Passed=false且退出非零。输出目录必须是新目录。独立的 `Test-StartupGateRegression.ps1 -OutputDirectory 新目录` 使用假进程验证就绪/清理失败边界，不启动真实进程。

本地beta统一执行 `pwsh -NoProfile -File scripts/Build-LocalBeta.ps1 -Offline`。它使用产品发布文件的隔离副本、匹配的自包含回归宿主和内置Java执行 `--core --stress`，并核对产品DLL/JAR/Java/coreclr摘要；实际产品EXE的宿主跟踪确认包内.NET加载。部分回归夹具仍经PATH的dotnet启动，不把它等同所有子进程环境都已单独跟踪；不测试正常退出、安装升级/卸载或真实认证。

10-08后续：UI检查关闭WPF后立即恢复原同步上下文，再执行真实核心进程检查；新增Task.Yield续体可运行检查，避免退出事件被投递到已关闭Dispatcher而使崩溃恢复偶发失败。仍使用原12秒恢复等待与原断言，不延长超时或跳过检查。

## 结果与失败处理

- 退出码 0 表示本轮通过；非零表示失败。result.json 保存本轮结果与检查数量，机器字段使用英文。
- 失败时中文 stderr 与 failure.log 保留脱敏堆栈；普通托管异常与 WPF Dispatcher 异常均不能计为通过。
- 输出目录保留旧失败和截图，不自动清空；重复运行时以最新 result.json、退出码与对应日志判断本轮结果。
- resource-stress.json 保存托管堆、工作集、私有提交、句柄、线程与各阶段数据。资源是短时测试进程采样，不能证明真实登录后的内存或长期稳定性。
- 退出清理仅处理本轮创建且启动时间匹配的测试进程；清理失败同样返回失败。
- 新增测试覆盖密码失败、状态映射、账户事务、异常报告、进程所有权以及日志容量/轮转。内部故意失败模式由回归自动调用，正常看到其失败日志不代表父回归失败。

资源检查的句柄趋势比较两个等量循环批次，先完成包含恢复的预热并让 Dispatcher 消息正常运行；不以一次较冷的样本断言泄漏。真实连接、切网/休眠、72 小时与 7–14 天验收仍需现场。

正式版本的统一入口为 `pwsh -NoProfile -File scripts/Build-Release.ps1 -Offline`，复用相同门禁，并新增发布副本的 `--ui-only` 检查。v2.4 文件版本为2.4.0.0，发布目录默认 `dist/v2.4`；门禁成功只表示可交付，GitHub 发布需按用户授权执行。
