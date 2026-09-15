# ESurfingDialer Lite Windows Client

当前版本：1.01

当前源码包含尚未发布的新版 UI 和连接恢复修复，旧的 1.01 安装包不会随源码修改自动更新。
本轮功能、构建方法和验证范围见 [UI 与连接恢复说明](docs/UI-AND-RECOVERY.md)。

这是 ESurfingDialer 的 Windows 轻量客户端工程，用来替代臃肿的官方天翼校园客户端做测试。

## 当前能力

- 按 Pen 草图实现的 WPF 主页、简易日志、设置和账户管理弹层。
- 托盘后台运行。
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
- 后续只要修改功能或修复 bug，就递增为 `1.02`、`1.03`。
- 发给同学测试时，只发 `dist/ESurfingDialer-Lite-v1.01-Setup.exe`。

## 构建

```powershell
powershell -ExecutionPolicy Bypass -File scripts\Build-Installer.ps1
```

构建完成后安装包输出到：

```text
dist\ESurfingDialer-Lite-v1.01-Setup.exe
```

## 日志位置

运行后日志保存在：

```text
%LOCALAPPDATA%\ESurfingDialerLite\logs
```

在“设置”里点击“导出日志”，选择保存位置后生成 ZIP。导出包不包含账户配置文件，并对已知账号、密码等敏感字段脱敏；分享前仍建议检查内容。

