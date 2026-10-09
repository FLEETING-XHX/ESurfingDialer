# ESurfingDialer Lite Windows 客户端源码

当前源码版本：v2.3beta.3；最近本地包采用统一验证入口，实际交付以包旁的 BUILD-MANIFEST.json 和校验文件为准。10-09/10 的 Pen 界面还原、整体缩放和设置跳转仅在源码，本轮先不打安装包。GitHub 已发布测试包仍为 v2.3beta；UA2104版本首次真实认证/心跳确认和外网已通过，后续连续心跳/恢复/长期待验。

当前源码已包含新版 UI、连接恢复修复和认证状态图标。09-29 的 S1、W1–W7 八项后续修复、09-30 六项离线修复及实际 EXE 启动修复已进入本地 v2.3beta.2 包；历史安装器保持原有代码。文件摘要、验证范围与来源见[本地测试包记录](../../../项目文档/修复与优化总记录.md#windows)。
v2.3beta 更新、下载与测试范围见 [测试版说明](../../../项目文档/修复与优化总记录.md#windows)；2.0 UI 历史见 [UI 与连接恢复历史记录](../../../项目文档/修复与优化总记录.md#windows)。
后续规划见 [未来开发方向](../../../项目文档/修复与优化总记录.md)。
本次修复顺序、验证结果与待现场确认项见 [认证修复计划](../../../项目文档/修复与优化总记录.md#windows)。
中文操作步骤见[Windows 使用说明](../../../项目文档/WINDOWS-USER-GUIDE.md)；后续工作统一见[待优化与待验收清单](../../../项目文档/修复与优化总记录.md)。

这是 ESurfingDialer 的 Windows 轻量客户端工程，用来替代臃肿的官方天翼校园客户端做测试。

## 当前能力

- 按 Pen 的449×912窗口预览实现主页、日志、设置和账户弹层；固定悬浮导航和上下渐隐，右上角只留最小化/关闭。修正连续缩放回跳和账户圆角遮罩缺口；设置入口只留悬停反馈。
- 设置页支持滚动，新增宽带充值、修改密码的默认浏览器跳转（仅最新源码）。
- 内嵌 Noto Sans SC 静态字体，换机器无需另外安装字体；来源和许可见 `app/ESurfingDialerLite/Fonts/NOTICE.txt`。
- 托盘后台运行，可设置后台连接状态提示。
- 多账户添加、编辑、删除和切换，兼容旧版单账户配置。
- 密码使用 Windows DPAPI 按当前用户加密保存。
- 可选开机自启。
- 可选启动后自动连接。
- 可选开机启动后后台运行，以及增强连接恢复。
- 内置日志查看和日志包导出。
- 安装包内置 .NET 运行环境和精简 Java 运行环境。
- 认证核心从 `core/ESurfingDialerCore` 源码重新构建，不反编译旧 `client.jar`。

## 版本规则

- `1.00`：当前第一版测试安装包。
- `1.01`：界面底部增加免费开源说明和原作者项目致谢链接。
- `2.0`：重做客户端界面，加入多账户、日志导出、连接恢复和认证状态托盘图标。
- `2.1`：完成轻量增强连接策略，加入有边界的异常恢复与恢复事件日志。
- `2.2`：完善认证核心的健康信号、异常恢复边界和回归覆盖；此 Windows 客户端未经真实长期环境验证，仅供尝鲜测试。
- `2.3beta`：补齐会话初始化诊断、动态门户入口、严格响应校验、会话 IP 隔离、无效句柄边界、有限协议重试与有时限的阶段监督；真实认证兼容性待现场验证。
- `2.3beta.2`：本地测试包，加入八项与六项后续修复、修复实际程序入口崩溃；文件版本 2.3.0.2，核心仍为 1.4.0。未推送或发布，真实连接与长期使用仍待验收。
- `2.3beta.3`：加入门户属性查询串/等值auth兼容与分阶段XML诊断；修复启动验证误通过，统一执行核心回归、发布副本回归、实际EXE就绪/清理及来源/摘要检查。文件版本2.3.0.3，核心仍为1.4.0；UA2104修复版10-09首次认证已通过，长期待验。
- v2.2 有已知认证失败问题。v2.3beta 提供测试安装包，下载请到 [Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases/tag/v2.3beta)；不应据本地回归结果认定校园网兼容性已恢复，测试前保留可用认证方式。

10-08后续beta3认证通道修复：旧Windows UA触发初始化HTTP200/空正文，改为经对照验证的64位通道，原生库保持。无账号测试身份已完成初始化与真实票据响应解析；修复包仍为2.3beta.3/2.3.0.3，使用独立目录与摘要区分，见[修复交付记录](../../../项目文档/WINDOWS-BOOTSTRAP-UA-FIX-2026-10-08.md)。10-09真实用户登录/首次确认心跳与外网已通过；连续心跳、恢复和长期稳定尚待验收。

## 源码验证

以下命令在本目录执行，要求已准备 .NET 10 SDK、JDK 17/21 与 Gradle/依赖缓存。JDK 路径不同的机器替换参数；此流程不生成安装器。

```powershell
./scripts/Build-Core.ps1 -Offline -Regression -Jdk17 'D:/Java/JDK17' -Jdk21 'D:/Java/JDK21'
dotnet build ./app/ESurfingDialerLite/ESurfingDialerLite.csproj --no-restore
dotnet run --project ./tests/ClientRegression/ClientRegression.csproj --no-restore -- artifacts/client-regression --core
```

缺少还原资产时先执行 `dotnet restore ./tests/ClientRegression/ClientRegression.csproj --ignore-failed-sources`；忽略不可用源不等于缺少依赖也能离线构建。09-29 本地结果为 167 项认证、63 项恢复、121 项客户端检查，使用本机 HTTP/原生替身；实际学校认证仍需验证。

## 安装包构建

使用 PowerShell 7 与已有 Inno Setup 6、SDK/JDK/依赖缓存。统一入口按版本创建新的dist、验证和交付目录，拒绝覆盖；只有核心回归、包内DLL/JRE/.NET/JAR副本回归、真实EXE就绪及清理、文档和保护检查均成功，才复制最终安装器并记录通过。源码验证不需要打包。

```powershell
pwsh -NoProfile -File scripts/Build-LocalBeta.ps1 -Offline
```

构建完成后安装包输出到：

```text
dist\v2.3beta.3\ESurfingDialer-Windows-v2.3beta.3-Setup.exe
```

最终交付另存于仓库的 `ESurfingDialer-Windows-Client/00_最终安装包/Windows客户端_v2.3beta.3`。重复构建需同时指定新的 `-OutputRoot`、`-ValidationDirectory` 和 `-DeliveryDirectory`，各自必须位于工程dist、artifacts和产品最终安装包根目录内。`Build-Installer.ps1`仅为底层编译步骤，不代表发布门禁通过；此流程不安装到用户程序目录，不验证真实升级/卸载或校园网认证，不提交、推送、上传。

## 日志位置

运行后日志保存在：

```text
%LOCALAPPDATA%\ESurfingDialerLite\logs
```

在“设置”里点击“导出日志”，选择保存位置后生成 ZIP。导出包不包含账户配置文件，并对已知账号、密码等敏感字段脱敏；分享前仍建议检查内容。
