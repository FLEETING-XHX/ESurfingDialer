# 原生迁移兼容与不可重复的错误

本文件对应本地实验核心，不能作为生产切换或校园网验收证明。旧Kotlin源码、生产Compose及真实data未改动。

## 设备身份（DCPP-COMPAT-01）

按字段依次使用：非空环境变量`DIALER_MAC_ADDRESS`/`DIALER_CLIENT_ID`、旧`device-state.json`、前阶段实验`native-mac`/`native-client-id`、首次生成。MAC与ID转小写，环境变量去首尾空白；MAC严格六组十六进制，生成MAC为本地单播、ID为UUID v4。保留旧自定义ASCII ID，不强制转换UUID。

规范化后的文件继续使用旧JSON字段`macAddress`/`clientId`，临时文件加唯一后缀后原子替换；旧实验文本保留作证据。读取/写入期间必须持有整个状态目录的所有权锁。损坏、重复字段、过大、字段类型不符或不受支持的ID（超过256字节、控制字符、非ASCII）会失败，不悄悄生成新身份；这比旧版遇到坏JSON自动换身份更保守。显式设置两项有效覆盖值可修复坏状态。

**不能把换实现当作换设备。** 不要删除旧device-state.json，不要复用正在运行的Kotlin卷（Kotlin不认识原生锁）。后续获准试运行时，仅把稳定快照中的device-state.json、reauth-plan.json复制到独立的新状态目录；不要复制健康文件或混跑两个写入者。本轮仅操作合成临时文件。

## 周期学习（DCPP-COMPAT-02）

迁移旧schema1/2的reauth-plan.json及字段名；schema1原样计数不当作可信学习样本。默认48小时估计，范围1小时到7天，提前最多90分钟，最小间隔30分钟；窗口默认UTC+8的04:00–06:00，支持跨午夜和全天。窗口对齐及超期兜底沿用旧计算；它可能在某些估计下对齐到下一天，不能称作“固定每天重启”。

只在同网络IP组合、观测到门户、心跳协议响应不合格、单调与墙钟时长相差不超过120秒时接受相关样本；相关性并非运营商会话策略证明。进程重启、计划触发、断网、传输错误、换IP和不合格时长不学习。回拨重置计划，持久化失败保留内存恢复并通过autoReauthPersistenceOk上报。首次keep确认后才记录认证成功，计划触发仍走新代次/首次keep确认；停止后不得用迟到结果复活会话。

## 调度与状态（DCPP-COMPAT-03已覆盖子集）

- 网络探测由独立线程执行，只发布带序号的完整快照；认证核心保留单一串行所有者。不能再把最长探测超时放在心跳前面，导致探测慢被误判为会话失败。
- 门户计数按同候选累计；换候选或非门户结果打断累计。宽限从认证成功开始，不能被每次成功心跳不断延长；冷却从门户重认证请求开始，不能用系统开机时间判断首次请求。
- 新IP观测、旧代次或已经超时的响应不能推进后续请求；请求前后检查当前代次与网络组合。会话端点/IP冻结，local-time仍逐请求刷新。
- networkOnline表示外网探测，authenticated表示本进程已通过首次keep的会话。无会话且外网可达明确为external_online，不能显示伪造的认证成功或为此反复重启。
- 认证暂停后仍消费网络快照；实际进程用例验证未知算法三次即paused，之后外网可达更新但authenticated保持false，认证样本保持零。暂停不应把旧网络值当实时值，也不能自动重新认证。
- schema3的processId是实际PID，instanceId是随机实例标识；暂停恢复不属于死进程。健康脚本验证类型、阶段和报告新鲜度，明确拒绝停止状态；不能复用schema2脚本。有限重试到限后进入paused，原生版本当前需要操作者检查后重启，不继续无界自动认证。
- NETWORK_CHECK_URLS去空项和空白，空配置使用旧三条默认探测URL。主要数值参数及窗口默认值见config.cpp。旧PORTAL_AUTH_FRESH_SECONDS在检查到的Kotlin源码中仅有定义，没有有效调用；不能声称原生已实现一个旧版实际也未执行的TTL策略。旧HEALTH_AUTH_MAX_AGE_SECONDS属于schema2脚本规则，schema3改用核心单调心跳进度与健康报告新鲜度，不能直接套旧字段。
- 移植旧PortalCompatibilityRegression等用例后发现并修复URL规范化缺口：scheme/host转小写、移除默认端口、保留查询顺序和只解码一层实体；BOM加XML声明兼容。68个旧门户行为案例覆盖属性内裸查询分隔符、引号内`>`、嵌套相同端点、CDATA/PI/注释、严格坏XML拒绝。大小写localhost也不能绕过生产回环限制。
- 新代次重取模块、切换不同算法/Client-ID的合成请求检查，不复用前代算法或工厂状态；参考算法之间能切换仍不是2082实际模块等价证明。
- ARMv7旧构建导入time/localtime_r的GLIBC_2.4接口；现已要求64位time_t，并加入2038边界转换及远期计划测试。Linux构建启用两个time64宏，运行时格式化检查所有返回值；这不涉及修改主机时间。现场内核/路由器仍需实测。
- DIALER_PASSWORD_FILE按最多4097个原始字节有界读首行，兼容LF/CRLF；有效密码最多4096字节，控制超大文件内存占用。只读取合成文件验证，未读取任何真实账户文件。

## 运维兼容（DCPP-OPS-01，本地实现）

当前C++进程直接作为PID1，独立线程通过128条有界队列写入日期文件与stdout，LOG_DIR/LOG_RETENTION_DAYS/LOG_FILE_MAX_BYTES/LOG_TOTAL_MAX_BYTES/LOG_ROTATED_FILES已经移植，具体默认值/边界见README。先为活动文件预留额度，再按旧文件mtime删除；旧超大文件用64KiB缓冲保留尾部，避免按文件大小分配内存。独立目录锁、链接/非普通文件拒绝、轮转链预检查和精确文件名保护非目标文件；不支持与不可信并发写入者共享目录。正常停止排空，磁盘错误/队列溢出/满或关闭stdout管道使进程失败退出。不可中断的内核磁盘I/O仍需Docker停止宽限兜底，不能称作任意存储故障都能由线程自行取消。

旧OpenWrt watchdog仍保持原样；新增Native/scripts/openwrt-watchdog.sh供独立选择，schema3检查复用容器内healthcheck.sh，拒绝重复JSON字段、多文档、错误类型/范围与不新鲜报告。未知schema/其他实现跳过。默认关闭，且要求unless-stopped与cpp镜像标签；实验Compose仍restart:no。监控不启动手工停止/暂停容器，不因外网已通但无本会话或paused而重启，保留失败阈值、冷却和墙钟回拨重置；没有每天重启功能。对不可变容器ID、容器启动时间、Docker启动程序、PID1启动tick和实例ID再检查后，使用默认同UID TERM；旧--user 0在cap_drop=ALL下无权向UID10001发信号，不能照搬。

旧回归适用性：HeartbeatLogRegression的首次摘要、30分钟单调边界、计数、恢复优先/一次与新会话重置已移植；逐次DEBUG不迁移，避免敏感值进入日志。RecoveryExitRegression的“清理无死锁/一次”通过真实子进程和SIGTERM/日志故障验证，但Kotlin恢复超限退出1在Native改为paused，需要人工检查后重启，这是避免未知模块重启循环的明确差异。CommandLineRegression的JCommander/JVM参数不适用，凭据采用环境/文件，生产程序没有CLI凭据解析。PortalDiagnosticRegression的结构计数/专用--diagnose-portal命令尚未移植；正常链路只输出固定失败码，原有门户/XML/HTTP拒绝和隐私用例有覆盖，不能声称诊断命令等价。
state-migration-smoke.sh已经在非root、只读根文件系统、断网容器中用合成旧状态演练：仅复制身份/计划、原生升级schema1计划、健康文件隔离，结束后旧文件逐字节摘要不变。这证明文件级回退保留；没有运行Kotlin生产认证或真实容器回退。

## 尚未通过的边界

实际Android32/2082模块身份与方向性原生算法差分、真实门户/算法变化/切网/长时恢复、资源峰值、实体OpenWrt/ARM监控安装与其他CPU架构、可选门户诊断命令仍未验收。95参考分支及Windows候选不是Docker原生等价证据；不切换为Windows2104通道绕过缺口。没有使用真实账户或替换生产容器。

监控的ARM仿真修正：实测/proc/1/exe会指向/usr/bin/qemu-arm，不能据此否认C++入口。改由Docker .Path精确确认/app/esurfing-native，保留cpp标签、不可变容器ID、StartedAt、PID1启动tick与instanceId检查。覆盖错误启动程序/替换进程/替换实例拒绝；不放宽为任意解释器路径。失败布局证据保留在build-msvc/ops-armv7-emulation-failure-layout.log。
