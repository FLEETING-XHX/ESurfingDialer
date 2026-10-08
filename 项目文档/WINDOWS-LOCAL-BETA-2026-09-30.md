# Windows v2.3beta.2 本地测试包（2026-09-30）

用户授权本地打包测试，**测试包未上传、未公开发布**。打包当时未推送源码，后续源码同步不改变该历史包。分支为 `codex/windows-client-beta`；旧 v2.3beta 安装包保留，新包位于：

```text
ESurfingDialer-Windows-Client/00_最终安装包/Windows客户端_v2.3beta.2/
  ESurfingDialer-Windows-v2.3beta.2-Setup.exe
  README_TEST.txt
  README_LOCAL-TEST.md
  SHA256SUMS.txt
  BUILD-MANIFEST.json
```

安装包包含 Windows x64 WPF 客户端、自包含 .NET 10.0.12、Java 21 与 Kotlin 认证核心。客户端版本为 `2.3beta.2`，文件版本为 `2.3.0.2`，核心 Gradle 版本仍为 `1.4.0`。本次未迁移语言、裁剪运行时或更换原生加解密库。

## 包含的修复

- 09-29 的 S1、W1–W7 八项运行修复，基线提交 `3a3d3f1`，见[八项修复记录](WINDOWS-CLIENT-FIXES-2026-09-29.md)。
- 09-30 的 OFF-01–06 六项离线修复，提交截至 `5739e3d`，见[六项修复记录](WINDOWS-OFFLINE-FIXES-2026-09-30.md)。
- 打包启动检查发现并修复的 `PKG-STARTUP-01`：实际 EXE 启动时，`App.OnStartup` 将 WPF 的 `StartupUri` 设为 null，属性 setter 抛出 ArgumentNullException，程序无法打开。此前测试直接创建 MainWindow，没有经过这个入口。移除 XAML 中的 StartupUri 和运行时清空操作，由已有单实例流程创建主窗口；新增独立的发布 EXE 启动检查。验证后单独本地提交 `8f4533e`。

构建时 HEAD 为 `5739e3d`，启动修复当时尚未提交，版本、打包脚本与中文说明也来自工作区。因此 DLL 的 InformationalVersion 带有该构建时提交后缀，不能仅凭后缀认为没有启动修复。保存后的运行源码提交为 `8f4533e`；包旁的 BUILD-SOURCE-PATCH.diff 从构建基线导出补丁，SOURCE-FILES.json 记录输入文件摘要，BUILD-MANIFEST.json 区分构建 HEAD、后续源码提交与未提交的打包元数据。

## 校验与验证

| 项目 | 实际结果 |
| --- | --- |
| 安装器大小 | 157765078 字节，约 157.77 MB |
| 发布文件 | 885 个，合计 315345789 字节；包含运行时及许可 |
| 安装器 SHA256 | `6025e825ff7132ea914a5a265a2c3cdfe4a1dae5cd89a625d943c95a843dab88` |
| Kotlin 核心 | 离线重建成功，167 项认证模拟、63 项恢复检查通过 |
| 发布文件回归 | 使用新包发布 DLL、内置 .NET/Java/JAR 的隔离副本，188 项通过，退出码 0；含短时压力 |
| 实际 EXE 入口 | 空账户、AutoConnect=false、独立配置目录启动成功，消息循环与启动日志正常，配置未改变，无核心健康文件；本轮测试进程已结束 |
| 原生库 | JAR 中 libdaproxy.so 与源码资源 SHA256 一致：`656ace25d47cc453685cc4109b5f4578d466b219d759008ac5720244ee899722` |
| 旧包 | SHA256 仍为 `4d2182a6c48ca40d6007f652e38f4c6109402f2f01c210021c0a99d394082dba`，未覆盖 |

短时压力包含 30 次预热、200 次启停/40 次恢复、2000 条界面事件与 200 次配置提交。压力阶段约 29.9 秒，测试进程句柄 851→843、GC 后托管堆 4764288→4646272 字节；没有本轮核心或临时密码残留。它是测试进程的短时结果，不是已登录产品的长期资源测量。

工程内验证记录位于 `artifacts/local-beta-20260930`：最终构建日志 build-v2.log、最终回归 package-regression-fixed.log/目录、入口检查 exe-smoke-fixed/目录。初次无法启动的包保留于 dist/v2.3beta.2-startup-failed，不用于交付。第一次自包含测试宿主配置不匹配而无法解析 CoreCLR，随后使用匹配的测试宿主；一次带完整宿主跟踪的旧产物压力运行被主动终止，不能计为通过。最终压力运行未开启该跟踪，实际 EXE 的独立跟踪确认加载包内 coreclr.dll。

## 本地使用与后续验收

安装与账户/托盘操作见[中文使用说明](WINDOWS-USER-GUIDE.md)。测试前退出原客户端，备份原账户配置；目前不能现场认证时，可先关闭自动连接，再检查窗口、账户操作、托盘与日志导出。此次没有安装到真实程序目录，没有改真实账户和系统自启。

安装器编译、发布文件回归及 EXE 入口通过，不表示真实安装升级/卸载流程已经验收。校园网初始化/Algo-ID、真实登录和心跳、实际切网/休眠、72 小时及 7–14 天稳定性仍待约七天后的现场测试。原生库一致也不能单独证明认证兼容恢复。

## 重建命令

在 Windows 源码目录执行。本地已缓存所需工具与依赖；离线还原使用空文件夹作为包源，只能取得已有缓存，缺少依赖会明确失败。

```powershell
New-Item -ItemType Directory -Force artifacts/cache-only-feed
dotnet restore app/ESurfingDialerLite/ESurfingDialerLite.csproj -r win-x64 --source artifacts/cache-only-feed
./scripts/Build-Installer.ps1 -Offline -Regression -OutputRoot "$PWD/dist/v2.3beta.2-rebuild"
./tests/ClientRegression/Test-PublishedStartup.ps1 -PublishedDirectory ./dist/v2.3beta.2-rebuild/ESurfingDialerLite -OutputDirectory ./artifacts/startup-beta2-rebuild
```

发布脚本从 csproj 读取版本，生成独立版本目录，拒绝覆盖已有输出；多个候选核心 JAR 会报错。默认目录为 dist/v2.3beta.2，重复构建应指定新目录。打包命令不会提交、推送或操作 GitHub Releases。
