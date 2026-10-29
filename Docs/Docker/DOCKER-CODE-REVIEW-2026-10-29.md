# Docker 全代码审查 · 2026-10-29

**后续状态：DR-001～DR-004已按用户授权完成源码修复，未打包或发布。** 原审查证据保留如下；最新修复方式与定向验证见文末。

审查目标是 `dist/worktrees/docker-v2.4.1` 工作树的 `codex/docker-v2.4.1`，HEAD `82b72e4c90df90d1f576ccb36711697910210365`，包含20份既有未提交修改及新增 `CoreStateOwnership.kt`。主目录仍保留 Windows 分支及全部未提交工作，没有把两端修改搬到同一工作树。下文路径相对该工作树的 `Products/Docker`。

通过本机 `codex review` 自定义全目录审查，并独立核对源码与隔离复现，确认 **2项P1、2项P2**。本次只审查，没有修复、提交、推送、生成交付包或操作真实认证/容器。现有v2.4交付包不会因源码审查改变。

## 确认的问题

### DR-001 · P1 · 正式包安装与启动使用不同的 Compose 项目名

位置：`scripts/package-release.py:185`（生成的启动命令），对照第21、145、148、158行。

安装器以 `esurfing-dialer-v241` 创建 `ESurfingDialer-v2.4.1`，生成的 `start.sh` 却以 `--project-name esurfing-dialer-v24` 执行 `up`。命令行项目名覆盖文件内的 `name`；按 `install.sh` → `start.sh` 正常流程启动时，已创建的同名容器属于另一项目，导致容器名冲突，阻断首次使用。若旧v2.4项目仍在运行，还可能在错误项目下更新资源。

隔离证据：从生产Python AST提取原样启动脚本，使用Docker替身记录实际argv，确认发出 `compose --project-name esurfing-dialer-v24 up -d --no-build --pull never`；另执行生产配置生成循环，确认安装器和Compose项目为v241。替身返回42表示预设的跨项目冲突，不是实际Docker daemon错误码；未宣称执行过真实Compose启动。

建议：生成启动脚本时复用 `PROJECT`，覆盖“安装创建后首次启动”和原地更新路径，验证项目标签一致。

### DR-002 · P1 · 启动互斥漏掉上一正式版容器

位置：`scripts/package-release.py:178`，对照第20行与上一正式版本同一文件的 `CONTAINER="ESurfingDialer-v2.4"`。

新包仅检查 `ESurfingDialer` 以及Beta/Beta2/Beta3，未检查正在运行的 `ESurfingDialer-v2.4`。从当前正式版升级时，即使旧版仍在线，启动脚本也会继续执行Compose。DR-001存在时可能误操作旧项目；单独修复DR-001后，仍会允许两个容器同时认证同一校园网络，引发会话相互干扰。新核心锁只约束同一个data目录，不能阻止不同部署目录的双容器认证。

隔离证据：Docker替身预设旧v2.4容器运行，实际调用清单从未检查该容器，仍到达Compose命令。没有启动双容器或验证校园服务器的具体踢线行为；确认的是缺少互斥检查及未阻止启动。

建议：至少覆盖上一正式版名称，或按可靠部署标签识别同产品认证实例；保留手动停止旧实例的操作要求。

### DR-003 · P2 · 损坏设备身份会被随机身份直接覆盖

位置：`src/main/kotlin/com/rsplwe/esurfing/DeviceIdentityStore.kt:25`，关联第19–33、37–42行。

已有 `device-state.json` 是损坏JSON或缺少有效身份字段时，读取解析返回null，加载器将其当成首次启动，随机补MAC/Client ID，然后无备份地覆盖原文件。这破坏了“已有身份保留”的行为基线，也丢失故障恢复依据。原子写文件只能避免本次半写，无法处理磁盘上已经损坏或被外部截断的身份文件。

隔离复现：全新合成STATE_DIR内写入 `{broken-json`，直接调用当前编译的生产 `DeviceIdentityStore.loadIntoStates()`，输出 `CORRUPT_IDENTITY_OVERWRITTEN=true`、`RANDOM_CLIENT_ID_CREATED=true`、`BACKUP_EXISTS=false`。生产类与既有2.4.1 JAR中的对应类逐字节一致，离线Gradle编译状态核对通过。未读取真实用户身份，也没有实测新身份在校园服务器上的绑定影响。

建议：区分首次初始化与已有文件损坏；已有文件无法校验时保留原件并报错，或从有效备份恢复。不要在未验证前直接复制Windows实现，Docker需自己的环境覆盖和迁移回归。

### DR-004 · P2 · 旧 beta 打包器生成的版本字段与镜像不一致

位置：`scripts/package-beta.py:143`，关联第19、131、140–143行及当前 `install.sh:4`。

beta脚本仍只替换 `v1.3`，输入安装器、Compose和环境模板已经是v2.4.1。因此脚本拟生成的镜像为 `esurfing-dialer-v1.4.0-beta.3-amd64.tar.gz`，生成的安装器却寻找v2.4.1文件，镜像标签也指向v2.4.1，无法完成安装。此项只影响继续调用旧beta入口的情形，不表示历史已交付beta包失效。

隔离证据：导入脚本而不调用main，只执行AST中原有的文本文件生成循环，核对安装器的 `VERSION/IMAGE_NAME`、Compose镜像与模块目标镜像。未构建镜像、压缩包或发布产物。

建议：显式按目标版本生成字段，或明确停用不再维护的beta入口；为保留的入口验证生成文件之间的一致性。

## 覆盖与验证

- 代码审查功能静态检查29份生产Kotlin、8份Kotlin测试、6份Python和8份自有Shell，并核对Dockerfile、Compose、Gradle、CI、环境模板及说明。生产代码整体审查；测试按覆盖范围、夹具隔离和关键边界核对，不宣称每一断言都经过独立形式验证。
- 74份当前Git输入的逐文件SHA-256与覆盖类别保存在 `dist/docker-code-review-20261029/source-snapshot.json`、`coverage.json`。第三方SO/JAR及标准Gradle wrapper只做文件清单核对，没有逆向或第三方源码审计。
- `codex review` 首次沙箱无法找到用户目录；获准用户上下文重跑完成，退出0。日志中的可选MCP初始化/关闭502未阻断本地审查。原始输出为 `codex-review-usercontext.log`；审查功能返回DR-001/002/004，主会话额外复核DR-003。
- 离线Gradle `compileKotlin compileTestKotlin --offline --no-daemon --console=plain` 成功，均UP-TO-DATE；这是当前输入与编译状态核对，没有重新生成交付JAR。
- 本轮运行现有认证回归324项、运行策略回归56项、Shell监督回归48项，共428项通过。运行策略沙箱首轮子JVM就绪断言失败；监督沙箱首轮子进程错误叠加GBK解码失败；两者用户上下文重跑通过。失败记录保留，不作为产品缺陷或通过结果。
- Python脚本AST解析及Shell语法核对通过，`git diff --check`通过。没有为了报告重新执行整套发布门禁。
- 隔离夹具、调用清单、生成配置片段、源码摘要、编译与回归日志均在主目录 `dist/docker-code-review-20261029/`。产品输入74文件复核未改变。

## 限制及后续

以上是当前源码的审查结论。没有运行真实Docker build/load/up、Alpine/arm64现场、校园认证、跨日长期稳定性或真实注销验收。新版本仍应先修复发布阻断与升级互斥，再按影响范围验证；身份损坏恢复需单独补边界用例。历史时钟/退避/CLI/日志管道/恢复上限退出死锁已有源码修复，不重复报作当前缺陷。

## 四项修复完成

- DR-001：正式和beta入口共用 `scripts/deployment_config.py`，启动脚本直接由各入口的 `PROJECT/CONTAINER` 常量生成，消除v24硬编码；安装创建和启动项目名一致。
- DR-002：启动检测包括 `ESurfingDialer-v2.4`、v2.4.1及历次beta；排除本部署自身以允许显式更新。旧实例运行时退出，不执行Compose命令，不自动停止旧实例。
- DR-003：已有身份先完整读取解析并校验MAC和Client ID；无效、缺字段、读取失败时保留原文并抛出固定错误，不随机补身份；即使提供完整环境覆盖也不覆盖损坏文件。有效旧身份无变化时不重写；首次初始化与显式覆盖沿用原子持久化。本次采用保留并报错的修复方案，没有实现自动备份恢复。
- DR-004：按字段显式生成安装器VERSION/IMAGE_NAME、Compose镜像、环境模板DIALER_IMAGE，模板缺少唯一字段时拒绝生成；不再全局替换旧版本字符串。保留旧beta入口与版本身份，未修改历史包。

定向验证：生产文本生成片段、实际安装器/启动脚本加Docker替身40项通过，覆盖正式与beta安装后首次启动、强制重建参数、旧容器互斥、非法参数及身份配置保留。身份边界26项通过，含首次初始化、有效旧身份不重写、部分/完整环境覆盖、坏覆盖不改文件、损坏JSON/缺字段/无效字段/坏UTF-8/目录目标保留；真实核心入口子JVM遇损坏身份在启动健康和网络线程之前非零退出。两项均纳入Gradle check，总计66项不同定向检查。未连带重跑未改动的认证/监督/日志全套回归。

离线Gradle编译与身份回归通过；现有门户诊断及心跳测试编译警告保留。沙箱首轮native-platform.dll加载失败，获准用户上下文重跑通过。日志在 `dist/docker-review-fixes-20261029/`，原审查目录不覆盖。未执行真实Docker或校园认证、生成镜像/JAR交付包、提交推送或发布；旧2.4.1 JAR不代表本次修改后的编译类。
