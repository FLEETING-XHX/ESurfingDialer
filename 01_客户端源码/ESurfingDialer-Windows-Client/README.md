# ESurfingDialer Lite Windows Client

当前版本：1.00

这是 ESurfingDialer 的 Windows 轻量客户端工程，用来替代臃肿的官方天翼校园客户端做测试。

## 当前能力

- WPF 图形界面。
- 托盘后台运行。
- 账号、密码配置保存。
- 密码使用 Windows DPAPI 按当前用户加密保存。
- 可选开机自启。
- 可选启动后自动连接。
- 内置日志查看和日志包导出。
- 安装包内置 .NET 运行环境和精简 Java 运行环境。
- 认证核心从 `core/ESurfingDialerCore` 源码重新构建，不反编译旧 `client.jar`。

## 版本规则

- `1.00`：当前第一版测试安装包。
- 后续只要修改功能或修复 bug，就递增为 `1.01`、`1.02`。
- 发给同学测试时，只发 `dist/ESurfingDialer-Lite-v1.00-Setup.exe`。

## 构建

```powershell
powershell -ExecutionPolicy Bypass -File scripts\Build-Installer.ps1
```

构建完成后安装包输出到：

```text
dist\ESurfingDialer-Lite-v1.00-Setup.exe
```

## 日志位置

运行后日志保存在：

```text
%LOCALAPPDATA%\ESurfingDialerLite\logs
```

客户端里可以点击“导出日志包”，桌面会生成 zip，方便后续排查问题。
