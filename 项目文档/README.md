# 项目文档索引

更新日期：2026-10-08。先从下方当前入口阅读；已完成修复的原因、验证和历史交付保留在记录中，不再重复列为未来工作。

## 当前入口

| 目的 | 文档 |
| --- | --- |
| 下一步做什么 | [统一待办与验收](PENDING-OPTIMIZATIONS.md)、[未来方向](ROADMAP.md) |
| Windows操作与最新本地包 | [使用说明](WINDOWS-USER-GUIDE.md)、[v2.3beta.2交付](WINDOWS-LOCAL-BETA-2026-09-30.md) |
| Windows认证与长期验收 | [现场验收计划](AUTH-REPAIR-PLAN.md)、[故障/稳定性/迁移基线](WINDOWS-STABILITY-AND-MIGRATION-BASELINE.md) |
| Docker正式版与beta3操作 | [v1.3图文教程](../ESurfingDialer-Docker/02_使用教程/ESurfingDialer-Docker部署教程.md)、[beta3操作](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/README_BETA.md) |
| Docker最新本地包与现场结果 | [10-08交付](DOCKER-LOCAL-BETA3-PORTAL-2026-10-08.md)、[门户诊断与首轮成功](DOCKER-BETA3-PORTAL-FAILURE-2026-10-07.md) |
| Linear编号、依赖与关闭依据 | [同步与10-08核查](LINEAR-WINDOWS-ISSUES-2026-09-30.md) |

## 当前产品状态

| 产品 | 交付与已验证 | 仍未完成 |
| --- | --- | --- |
| Windows | 已发布v2.3beta；本地v2.3beta.2含八项、六项及入口修复，188项包内检查/EXE启动通过，测试包未上传/未公开发布 | 现场格式/算法与真实认证、恢复/安装矩阵、长期稳定及统一发布门禁 |
| Docker | 已发布v1.3；本地`beta3-portal-fix-20261008`含门户兼容修复，用户一次登录/首次keep确认及上网通过，测试包未上传/未公开发布 | 周期心跳、自然掉线/重认证、完整部署/恢复矩阵、资源趋势与注销确认；beta仅amd64 |

一次真实登录不等于长期稳定，合成回归也不等于现场验收；源码、已生成包和已发布版本分别记录。

## 修复与交付依据

| 分类 | 记录 |
| --- | --- |
| Windows修复 | [八项后续修复](WINDOWS-CLIENT-FIXES-2026-09-29.md)、[六项离线修复](WINDOWS-OFFLINE-FIXES-2026-09-30.md)、[恢复/会话修复](RECOVERY-REPAIR-REPORT.md) |
| Windows现场与算法 | [初始化失败记录](WINDOWS-AUTH-SESSION-ISSUE.md)、[原生/设计审计](WINDOWS-DESIGN-AND-AUTH-AUDIT-2026-09-30.md)、[C参考对照](C-REFERENCE-REVIEW.md) |
| Windows历史发布与研究 | [v2.3beta发布快照](V2.3BETA-RELEASE.md)、[去JVM研究](WINDOWS-NO-JVM-MIGRATION-PLAN.md) |
| Docker修复 | [beta1](DOCKER-BETA-REPAIR.md)、[beta2](DOCKER-BETA2-REPAIR.md)、[09-28门户](DOCKER-PORTAL-CONFIG-PLAN.md)、[09-29后续](DOCKER-BETA-FOLLOWUP-2026-09-29.md) |
| Docker历史交付 | [10-07旧beta3快照](DOCKER-LOCAL-BETA3-2026-10-07.md)、[中文更新日志](../ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed/CHANGELOG.md) |
| 修复前审查与旧设计 | [Windows 09-29审查](WINDOWS-FOLLOWUP-REVIEW-2026-09-29.md)、[两端09-28审查](BETA-CLIENT-REVIEW-2026-09-28.md)、[Docker v1.3分析](DOCKER-REFERENCE-ANALYSIS.md)、[UI/恢复历史](UI-AND-RECOVERY.md) |
| Linear首次范围 | [已执行的创建范围记录](LINEAR-WINDOWS-ISSUE-PLAN-2026-09-30.md)，不能作为重复创建指令 |

历史报告保留独有根因、触发条件、回归结果和包摘要；过期开发步骤已压缩，剩余工作统一指向待办清单。旧包及包内文档保持原快照，不随本次整理重写。

外部C参考保留来源与许可，其算法/平台能力不能直接当成本项目的实现或验收。本机工具协作说明与交接仅本地维护，不加入公共上传内容。
