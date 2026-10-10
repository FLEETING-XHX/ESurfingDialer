ESurfingDialer Windows v2.4 使用说明（2026-10-10）

安装器：ESurfingDialer-Windows-v2.4-Setup.exe，支持 Windows x64。
内置 .NET 10、Java 21 和认证核心，无需另行下载运行环境。

升级前先从托盘完全退出旧版本，并备份 %LOCALAPPDATA%\ESurfingDialerLite。
配置按当前 Windows 用户用 DPAPI 加密，换用户时可能需要重新录入密码。

在账户页添加并保存账号密码，选择账户，点击主页校园网卡片开始连接；再次点击可停止。
关闭窗口隐藏到托盘，完全退出请使用右键托盘的退出入口。
设置可控制开机自启、后台运行、自动连接、增强恢复及后台提示。
界面包含内嵌中文字体，启动按显示器 DPI/工作区缩放，支持手动等比调整。

v2.4 汇入门户/会话/恢复与 UA2104 认证通道修复，保留 Windows 原生库。
此前 UA2104 修复版已通过一次真实登录、确认心跳和外网访问。
v2.4 的持续心跳、切网/休眠恢复、安装/升级/卸载及长期稳定仍待验收。

日志目录：%LOCALAPPDATA%\ESurfingDialerLite\logs。可在设置导出脱敏日志。
反馈提供版本、发生时间、步骤和错误提示，不公开账号、密码、ticket、密钥或原始 ZSM。
使用前停止其他认证客户端，避免同时认证造成会话冲突。
发布和文档：https://github.com/FLEETING-XHX/ESurfingDialer/releases/tag/v2.4
