# Linear 同步与完成条件核查

首次同步：2026-09-30；最近核查：2026-10-08。项目：[ESurfingDialer](https://linear.app/fleeting-xhx/project/esurfingdialer-0181c615ecee)，团队FLEETING。

## 本次结果

逐条读取项目全部11条Issue的完整正文、状态、验收条件、依赖和评论，包含已归档项，列表无下一页。当前为7条Todo、4条Backlog；**本次0条满足完整Done条件**，没有为清空待办而关闭任务。全部仍属于Windows或后续平台，不能用Docker beta3本次认证成功替代。

已在FLE-11正文追加10-08核查依据，状态保持Todo；其余状态、优先级、标签、负责人和依赖不改。没有新建Issue、发表评论或上传用户日志。

## 实际编号与剩余验收

| 稳定编号 | Linear Issue | 当前状态 | 本次不能关闭的原因 |
| --- | --- | --- | --- |
| AUTH-INIT / WOPT-01 | [FLE-5 · [Windows] 定位现场认证失败并确认 Algo-ID 与算法兼容](https://linear.app/fleeting-xhx/issue/FLE-5/windows-定位现场认证失败并确认-algo-id-与算法兼容) | Todo | Windows现场初始化根因/Algo-ID及真实认证未确认；Docker成功不适用 |
| WIN-RECOVERY / WOPT-03 | [FLE-6 · [Windows] 验收真实切网、休眠、停止与恢复状态](https://linear.app/fleeting-xhx/issue/FLE-6/windows-验收真实切网休眠停止与恢复状态) | Todo | 缺真实切网、休眠、停止和恢复矩阵；前置FLE-5未完成 |
| WIN-ACCEPT / WOPT-09 | [FLE-7 · [Windows] 验收安装升级、账户迁移、自启卸载与 DPI](https://linear.app/fleeting-xhx/issue/FLE-7/windows-验收安装升级账户迁移自启卸载与-dpi) | Todo | 缺安装/升级/卸载、跨用户DPAPI与DPI完整实机矩阵 |
| WIN-LONGRUN / WOPT-04 | [FLE-8 · [Windows] 建立 72 小时与 7–14 天真实运行稳定性基线](https://linear.app/fleeting-xhx/issue/FLE-8/windows-建立-72-小时与-7-14-天真实运行稳定性基线) | Todo | 缺真实登录后72小时及7–14天运行/资源基线 |
| WIN-RUNTIME / WOPT-08 | [FLE-9 · [Windows] 评估并缩减 Java 模块与多余原生依赖](https://linear.app/fleeting-xhx/issue/FLE-9/windows-评估并缩减-java-模块与多余原生依赖) | Todo | 仍ALL-MODULE-PATH，多套原生依赖未逐项缩减及验收 |
| WIN-CONTRACT / WOPT-10 | [FLE-10 · [Windows] 固化协议、状态机与故障行为规格及测试向量](https://linear.app/fleeting-xhx/issue/FLE-10/windows-固化协议状态机与故障行为规格及测试向量) | Todo | 缺完整可移植协议规格及经现场确认的可信算法向量 |
| WIN-RELEASE-CHECK / MOPT-02 | [FLE-11 · [Windows] 自动化发布入口、版本、摘要和文档检查](https://linear.app/fleeting-xhx/issue/FLE-11/windows-自动化发布入口版本摘要和文档检查) | Todo | 单次包检查有效，统一发布入口/失败门禁尚未完成 |
| WIN-PERIODIC / WOPT-11 | [FLE-12 · [Windows] 评估认证周期学习与安全窗口主动重认证](https://linear.app/fleeting-xhx/issue/FLE-12/windows-评估认证周期学习与安全窗口主动重认证) | Backlog | Windows可信周期学习/安全窗口计划及控制未实现 |
| WIN-NO-JVM / WOPT-12 | [FLE-13 · [Windows] 评估 C# 或 Go 核心迁移并验证协议算法等价](https://linear.app/fleeting-xhx/issue/FLE-13/windows-评估-c-或-go-核心迁移并验证协议算法等价) | Backlog | 仍为Java认证核心，无C#/Go等价原型及认证验收 |
| WIN-UPDATES / WOPT-13 | [FLE-14 · [Windows] 评估可由用户控制的版本更新检查](https://linear.app/fleeting-xhx/issue/FLE-14/windows-评估可由用户控制的版本更新检查) | Backlog | 更新检查入口及离线/取消/配置保留验收尚缺 |
| WIN-MACOS / WOPT-13 | [FLE-15 · [后续平台] 评估 macOS 认证通道与客户端实现方案](https://linear.app/fleeting-xhx/issue/FLE-15/后续平台-评估-macos-认证通道与客户端实现方案) | Backlog | 无macOS产品实现及平台认证兼容证据 |

WOPT-05的剩余验收并入WOPT-03/FLE-6，WOPT-06/07及MOPT-01并入WOPT-09/FLE-7，旧编号作为追溯别名保留。源码修复已完成不代表对应实机验收Issue已完成。

## 发布自动化的具体缺口

FLE-11要求的是统一可重复、失败停止交付的流程。09-30的188项包内回归与`InputIdle=true`实际EXE启动仍是有效单次结果，但`Build-Installer.ps1`/`Publish-Client.ps1`未组合客户端回归、EXE、版本/摘要和文档检查。

`tests/ClientRegression/Test-PublishedStartup.ps1`目前将`WaitForInputIdle`返回值写入结果，false仍可到达`Passed=true`；通过JSON又早于finally进程清理，清理失败可能留下通过文件。需补明确就绪与清理门禁，本轮没有修改Windows代码或重跑发布。

## 首次同步事实与依赖

09-30用户只批准7条待处理、4条储备；OFF-01至06、PKG-STARTUP-01和WOPT-02这8条已完成草稿没有创建为Issue。范围记录见[首次同步记录](LINEAR-WINDOWS-ISSUE-PLAN-2026-09-30.md)，已完成原因/验证见[六项修复](WINDOWS-OFFLINE-FIXES-2026-09-30.md)和[本地交付](WINDOWS-LOCAL-BETA-2026-09-30.md)。

- FLE-6、10依赖FLE-5；FLE-8依赖FLE-5、6、7。
- FLE-9、12、14依赖FLE-8；FLE-13、15依赖FLE-8和10。
- FLE-5、7、11没有前置阻塞；可先执行独立离线部分，关闭前仍需完整验收。

首次创建时未分配负责人、Cycle或硬到期日。现有集成中FLE-5至FLE-15分别关联GitHub #1至#11；这是既有同步关系，本轮未另行调用GitHub写接口或发布版本。

原19条草稿、批准11条范围及`sync-results.json`保存在Windows工程`artifacts/linear-issue-plan-20260930/`。本次完整只读核查快照保存在Docker工程忽略的`build/document-cleanup-20261008/`，不上传真实账户/票据、原始ZSM或本机协作文件。
