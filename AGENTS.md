# ESurfingDialer 项目规则入口

详细的 zcode/Codex 共用开发规则见[项目文档/协作规则.md](项目文档/协作规则.md)。开始任务前先读它、[项目说明](项目文档/PROJECT-GUIDE.md)和[开发交接记录](项目文档/DEVELOPMENT-HANDOFF.md)。用户在当前会话中的明确指令优先。

- 唯一共同开发根目录：`C:\Users\FLEETING\Documents\Project\ESurfingDialer-Docker`。
- 先查看 `git status` 和 diff，保留另一工具与用户的未提交改动。
- Windows 源码与 Docker 源码分别位于 `ESurfingDialer-Windows-Client/01_客户端源码/ESurfingDialer-Windows-Client` 和 `ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed`。未经验证，不跨产品覆盖协议或原生库。
- zcode 与 Codex 不同时编辑同一个文件；文件范围、验证和遗留问题写入 `项目文档/DEVELOPMENT-HANDOFF.md`。
- 不默认提交、推送、打包或发布；2026-09-26 用户已明确授权 v2.3beta 打包、代码同步和 GitHub 预发布，后续按最新明确指令执行。
- 认证现场 Algo-ID 与算法尚未确认；不得猜测密钥或宣称本地测试已证明校园网认证恢复。
