# ESurfingDialer Lite Windows Client

当前测试版本：v2.3beta（GitHub 预发布测试版，校园网实测待完成）

当前源码已包含新版 UI、连接恢复修复和认证状态图标；旧的 1.01 安装包不会随源码修改自动更新。
v2.3beta 更新、下载与测试范围见 [测试版说明](../../../项目文档/V2.3BETA-RELEASE.md)；2.0 UI 历史见 [UI 与连接恢复历史记录](../../../项目文档/UI-AND-RECOVERY.md)。
后续规划见 [未来开发方向](../../../项目文档/ROADMAP.md)。
本次修复顺序、验证结果与待现场确认项见 [认证修复计划](../../../项目文档/AUTH-REPAIR-PLAN.md)。
zcode 与 Codex 共同开发时，请先阅读根目录的 [项目说明](../../../项目文档/PROJECT-GUIDE.md)、[协作规则](../../../项目文档/协作规则.md) 和 [交接记录](../../../项目文档/DEVELOPMENT-HANDOFF.md)，以及根目录 [AGENTS.md](../../../AGENTS.md)。

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
- `2.0`：重做客户端界面，加入多账户、日志导出、连接恢复和认证状态托盘图标。
- `2.1`：完成轻量增强连接策略，加入有边界的异常恢复与恢复事件日志。
- `2.2`：完善认证核心的健康信号、异常恢复边界和回归覆盖；此 Windows 客户端未经真实长期环境验证，仅供尝鲜测试。
- `2.3beta`：补齐会话初始化诊断、动态门户入口、严格响应校验、会话 IP 隔离、无效句柄边界、有限协议重试与有时限的阶段监督；真实认证兼容性待现场验证。
- 后续只要修改功能或修复 bug，就递增版本。
- v2.2 有已知认证失败问题。v2.3beta 提供测试安装包，下载请到 [Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases/tag/v2.3beta)；不应据本地回归结果认定校园网兼容性已恢复，测试前保留可用认证方式。

## 构建

```powershell
powershell -ExecutionPolicy Bypass -File scripts\Build-Installer.ps1
```

构建完成后安装包输出到：

```text
dist\ESurfingDialer-Windows-v2.3beta-Setup.exe
```

## 日志位置

运行后日志保存在：

```text
%LOCALAPPDATA%\ESurfingDialerLite\logs
```

在“设置”里点击“导出日志”，选择保存位置后生成 ZIP。导出包不包含账户配置文件，并对已知账号、密码等敏感字段脱敏；分享前仍建议检查内容。

