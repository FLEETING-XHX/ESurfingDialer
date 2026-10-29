# Docker C++迁移阶段记录

> 最新续做方式（2026-10-29，用户明确要求）：已删除自动任务docker-c，改为用户额度恢复后在本对话发消息继续。删除自动任务不删除本对话、工作树或检查点。下文ACTIVE/定时检查描述属于历史记录，不再有效；不得擅自重建定时任务。继续时先检查git状态并阅读本文件末尾最新检查点及Native/build-msvc/ops-validation-report.json；OPS-01本地已通过，保护已有改动，不重复旧阶段。

用户2026-10-29授权先完成Docker迁移，接近五小时额度剩余5%时保存进度，恢复后续做。当前工作树为 `C:/Users/FLEETING/.codex/worktrees/2b72/ESurfingDialer`；保护Windows已有文档和Research/design。本轮不提交推送、不部署生产容器、不使用用户凭据。独立新目录 `ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Native`，旧Kotlin目录作为回退。

## 阶段与完成条件

| 阶段 | 工作与退出条件 | 当前状态 |
| --- | --- | --- |
| DCPP-0 | 固定协议、生命周期、阶段和回退边界 | 已完成本轮规格记录 |
| DCPP-1 | C++状态机、有限重试、网络与会话分离、目录所有权、原子健康上报；真实编译和离线回归 | 本地通过，包含跨进程锁与SIGTERM实际进程检查 |
| DCPP-2 | 独立C算法库适配、严格输入边界、模块ID解析、来源清单；参考对照与边界回归 | 95分支适配及参考对照通过；ARM32原生等价仍待证 |
| DCPP-3 | libcurl/libxml2传输与门户发现、XML/checksum/ticket/login/首次keep/持续keep/term；本地HTTP模拟闭环 | 核心链路、身份/周期计划、独立探测调度和实际进程回环闭环通过；完整协议异常/现场兼容仍待验 |
| DCPP-4 | Docker构建、非root运行、信号/健康/日志/配置/重启计划、隔离容器验证 | 三架构compat实验镜像、非root只读根目录和文件级回退通过；未切生产，日志/监控/长期/实体环境仍待验 |
| DCPP-OPS-01 | 旧文件日志归档/保留与schema3监控迁移，保持直接PID1和停止/手动暂停边界 | 本地完成；三架构文件日志/健康/监控边界与实际容器守卫通过；实体OpenWrt仍待验 |
| DCPP-5 | 目标校园网与算法等价、真实恢复/注销、长期资源验收 | 等待现场资源；不能由模拟替代 |

## 必须保持的行为

- Docker沿用已验证Android32/2082通道，不能因Windows候选命中而擅自切2104；未知算法明确拒绝，有界重试，不无限重新取模块。
- 保留门户返回的动态端点、会话独立身份/IP，首次keep确认前authenticated=false。
- 外网可达与本核心会话分开；外网已通但无会话是明确状态，不伪造成功也不因此重启。
- 一个状态目录一个核心所有者；停止/切账户/代次切换取消旧工作，旧写入不得覆盖新状态。
- 超时/心跳/退避用单调时钟；日志不能输出账号密码、ticket、明密文、密钥和完整敏感URL。
- 日常定时重启要求与旧周期学习重认证是不同功能，迁移不擅自新增已记录但未授权实施的策略。

## 额度与续做

开始工具实读五小时usedPercent=59（剩41%）。按阶段检查，不用空转消耗额度，不使用免费重置。已创建当前聊天heartbeat `docker-c`，每30分钟检查剩额度与阶段，<=5%安静等待，恢复后续做；阶段完成/失败/用户操作才通知。

## 本机验证环境

Windows MSVC 14.51、Visual Studio自带CMake可用；docker未在PATH发现，WSL枚举E_ACCESSDENIED。先做跨平台离线构建，容器/Linux验证另记录，不提前声称Docker包可用。

后续环境核实：正常用户权限下Docker Desktop 29.7.2/Linux引擎可用，CLI为`C:/Users/FLEETING/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe`。本轮仅创建独立测试/实验镜像，临时测试容器均退出，旧Kotlin目录与生产Compose无改动。

## 本轮已验证结果

- Windows MSVC/Ninja三组回归通过；Linux GCC/CMake五组通过：25项状态/所有权断言、2000项算法检查（含475条修改前参考输出）、2280项畸形密文边界、55项协议检查（含每请求时间刷新）、4个真实loopback HTTP场景。
- Linux ASan+UBSan+泄漏检查五组全部通过。发现并修正参考SM4/SNOW3G的整数提升左移UB，修正前475条合成参考输出全部保留，修正后逐条相同；来源清单记录原摘要和local_sha256，37文件及独立工厂核对通过。
- 检测环境出现默认PIE下最小空main也5/20随机ASan崩溃；仅sanitizer构建改非PIE后最小main20/20通过，完整套件通过。Release构建未因此关闭PIE。早期失败日志保留，不把失败当通过。
- 实验运行镜像`esurfing-dialer-native:experimental`当前ID `sha256:753322a08029cdfd125362086368bc391577f34f03902a618259777f2a9ece98`，镜像逻辑大小50204507字节；Release ELF374632字节，SHA256 `c5b9046638d9328e5e626aa01c99086f06064680b31def6de1573f9719d6f21d`。不是压缩下载、内存或长期认证实测。
- 实际镜像默认UID/GID10001、无Java；在`--network none`下以合成账户测试默认实验开关、第二进程锁拒绝、SIGTERM结束、健康脚本拒绝已停止状态均通过。Compose仅用.env.example执行config语法核对，未load任何真实.env。
- 来源与可移植验证脚本：Native/scripts/Verify-Sources.ps1；完整Linux/Windows/ASan与镜像构建日志在Native/build-msvc/（忽略目录）。测试向量均为合成全'a'输入，不含真实账号/票据。
- Native/build-msvc/validation-report.json记录77份源文件身份、最终ELF/镜像摘要及实际验证边界。早期ASan递归诊断产生225766448字节日志，已完整压缩为sanitize-fixed-full.log.zip（1097152字节），解压流SHA256与原文件相同后才移除原生成日志，失败证据保留。

## 续做清单（按优先级，不要跳过）

1. **DCPP-COMPAT-01 设备身份：本地完成**。环境变量逐字段优先，保留旧device-state.json与自定义ASCII ID、兼容早期实验文本身份、严格有界JSON/原子替换。损坏状态失败保留，不自动换身份；38项合成身份/JSON检查通过。不要重复实现或读取真实账户配置。
2. **DCPP-COMPAT-02 周期计划：本地完成**。旧schema1/2、UTC+8窗口、保守相关样本、回拨、写入失败/取消和运行链路已迁移，AUTO_REAUTH_ENABLED默认1正常工作，实验示例也恢复1；没有新增每天重启。计划/门户策略122项检查通过，实际进程回环认证后可见计划健康字段。
3. **DCPP-COMPAT-03 协议与运行细节：核心已离线通过，继续旧运维/诊断用例映射**。独立探测线程避免慢探测饿死心跳；同候选计数、从认证成功计算宽限、从门户请求计算冷却；请求前后拦截旧代次/新IP，实际PID与随机instanceId分开；空探测配置回落旧默认。未知算法现在保留明确ALGORITHM_UNSUPPORTED类码、确定性且无内部重新取模块；新代次实际改用另一算法和Client-ID的合成链路通过。68项旧门户兼容用例移植后修复host大小写/默认端口规范化和BOM；发现阶段沿用旧10秒超时，三跳总预算小于90秒核心进度预算。不要重做95分支或把参考模块ID冒充实际native aid。
4. **DCPP-VALIDATE-01 原生等价**：当前Docker仍Android32/2082，95分支参考输出及Windows候选不是ARM32真实等价。取得正确模块身份与可重建合成向量后做离线差分；缺现场资源时明确阻塞，继续独立兼容工作。
5. **DCPP-PACK-01 交付**：README/COMPATIBILITY与.env示例已更新；Kotlin生产配置保持。实际进程模拟闭环、健康矩阵、UID10001/只读根目录/cap_drop=ALL、合成状态复制与文件级回退、UTC/上海时区已验；amd64/armv7/arm64三种本地镜像各十一组回归和运行冒烟通过（ARM是仿真）。继续优先OPS-01，再补旧回归适用边界/长时恢复/最终交付，不加载真实.env。实验镜像没有授权推送、发布或替换用户实际容器。

下一次先读本文件和git status，保护Windows既有文档、Research/design；用get_usage_limits决定是否继续。剩余<=5%安静等待，不调用额度重置。完成以上兼容与验证之前，不得写“Docker迁移完成/真实认证通过”。

## 续做检查点

分支FLEETING/docker-cpp-migration，全部工作尚未提交。旧Kotlin产品目录无diff；现有Windows文档改动、用户Research/design完整保留。2026-10-29额度恢复后已完成COMPAT-01/02及COMPAT-03核心离线兼容；下一轮从OPS-01文件日志/监控兼容与剩余旧回归适用边界继续，不重复身份/计划/三架构已过测试或95算法审查。新CPP依赖目录已与上游应用全局状态隔离，不得重新导入Mongoose/Web层或换掉Docker通道来绕过缺失的原生向量。

新增证据：Windows五组回归、Linux十一组（32状态/配置/时间、38身份/JSON、122计划/门户、2000算法、2280畸形密文、139协议、68旧门户兼容、探测线程并发、两个实际进程回环场景、五个HTTP场景）。十一组ASan+UBSan+泄漏检查通过；实际进程回环使用独立fixture可执行文件，生产ELF不包含允许回环的测试开关。amd64/ARMv7/ARM64镜像各以UID10001、--network none、只读根目录、4m临时目录、cap_drop=ALL通过运行/状态迁移冒烟；19个健康阶段/schema案例及慢报告/坏JSON/无读取权限检查通过。ARM CPU是Docker仿真，未验物理路由器；所有认证均合成，不是现场或Android32/2082原生差分。

第二个实际进程场景专门验证“暂停认证不等于停止网络监测”：未知算法确实只取三次模块后进入paused，随后模拟外网恢复，networkOnline继续变true、authenticated仍false、计划不记录认证成功，TERM正常退出。修复了runtime.cpp原先paused提前continue导致探测快照不再消费的遗漏，不会为了更新外网状态偷偷恢复认证。

本轮异常矩阵还覆盖坏缓存只能被新有效配置修复、坏缓存不得静默回落、三跳限制、重复IP拒绝、有界旧端点回落；冲突Location响应头现在明确HTTP_HEADERS_INVALID，不再采用最后一个值。未知算法的确定性错误三次即暂停（不等30次普通恢复）；心跳协议错误与传输错误在日志/健康中分开，新IP取消直接进入重新发现。

Import-Ciphers.ps1已在新临时目录重放两处unsigned修复，37算法/头文件、独立工厂和95项注册表逐字节摘要与当前相同；没有覆盖原算法文件。导入时核对固定版本的全部原始源文件、工厂与许可证，不把其他上游版本误标为当前commit；要求PowerShell7以保持生成字节。新增.gitattributes让脚本保持LF，同时禁止转换已核对第三方/注册表/向量的字节；core.autocrlf=true下工厂clean-filter摘要仍与原始字节相同。真实.env被Git和Docker上下文排除。

ARMv7曾导入time/localtime_r的GLIBC_2.4接口；最终ELF已实查为__time64/__localtime64_r@GLIBC_2.34，C++要求time_t至少64位。32位glibc显式_FILE_OFFSET_BITS=64/_TIME_BITS=64，测试2038转换及远期计划但没有改系统时间，不外推为现场全栈未来时间保证。密码文件读首行有界且去CRLF，实际回环链路以合成CRLF密码文件优先于错误环境变量通过。

镜像额外健康矩阵最初在ARM仿真失败：正例复用较早的epoch、299秒接近300秒TTL，脚本自身耗时令正例过期。保留*-smoke-timing-failure.log，测试改逐例新epoch并给正例合理余量后，三架构均通过；没有修改生产健康判定来迎合测试。

实际PID1补验（lifecycle-report.json）：只用自建合成断网容器，原生UID10001/cap_drop=ALL时，旧watchdog的docker exec --user0 kill权限被拒绝；同UID发送TERM能退出0并写stopped健康。**OPS-01不能盲搬旧--user0**，必须保持正确同UID、直接C++ PID1、手动停止/暂停不启动、无会话但外网通/paused不循环重启。旧schema2 watchdog对schema3跳过，也不能当已经有原生监督能力。

本机MSVC的中文/showIncludes前缀被CMake识别为乱码，造成一次增量构建旧对象链接失败；失败日志保留。已在忽略目录build-msvc-compat/include-prefix.cmake局部修正前缀并干净构建，实际触碰config.hpp后验证依赖对象重新编译，ninja -t deps列出该头文件。不要把原build-msvc的未经核对增量结果当新代码证据。旧build-msvc/validation-report.json保留为上一阶段镜像记录；新阶段证据写build-msvc/compatibility-validation-report.json。

已经创建heartbeat docker-c，每30分钟检查额度。它是当前聊天续做，不是创建独立任务；若应用/主机不在线，不能承诺在某秒自动恢复。保存进度后停止本轮计算，恢复后根据阶段记录继续。未调用免费重置，未向远端发送代码或账户信息。

当前是额度恢复后的核心兼容检查点，不是再次达到5%的暂停。自动续做保持ACTIVE，下一阶段OPS-01仍有独立工作；每次以get_usage_limits实读决定是否继续，不用历史百分比判断。最终镜像身份、各架构ELF摘要与96份工作区源文件身份以build-msvc/compatibility-validation-report.json为准；旧validation-report.json只对应前阶段，不能混用。

## 2026-10-30 检查点：OPS-01本地完成

本轮从人工消息继续，定时任务保持删除。五小时额度开始实读剩97%，不会为用掉额度而伪造现场证据或空转。分支和HEAD未变，仍全部未提交；旧Kotlin源码/生产Compose、Windows产品、Research/design保留。

新增C++有界异步文件日志：迁移五个LOG_*变量，日期/轮转/总量/mtime保留、目录独占、链接/非普通文件拒绝、64KiB旧大日志尾部裁剪。128条队列避免文件/stdout I/O进入认证和心跳线程；失败与溢出退出1，正常停止排空。stdout满管道2秒预算，紧急stderr仅尝试非阻塞输出；不可中断磁盘I/O仍依赖Docker停止宽限兜底，没有子进程孤儿。首次keep摘要、30分钟单调摘要、失败后恢复一次已迁移。CLI原有argc==1限制并未缺失，本轮只把不支持参数的笼统78改为明确64及固定提示；不回显凭据/URL，六个实际CLI案例拒绝前不读配置/状态。

新增独立Native/scripts/openwrt-watchdog.sh，默认EXPECTED_RUNNING=0且只监督运行中的unless-stopped、cpp标签、Docker .Path=/app/esurfing-native的容器；实验Compose仍restart:no。不会docker start/restart/stop，不会因external_online/paused或外网探测失败误循环。保留阈值/冷却/回拨/PAUSE_FILE，并按不可变容器ID、StartedAt、PID启动tick、instanceId复查。实际验证--user 0向UID10001发TERM被拒绝；同UID守卫可以退出，错误启动标识/实例会被拒绝。

ARM仿真实测发现/proc/1/exe=/usr/bin/qemu-arm，最初守卫因此拒绝正常进程。失败布局保留；现改用Docker记录的启动程序，保留其余身份边界，三架构实际退出守卫均通过。测试工具也修正了Docker平台warning与ID混合输出解析，增加唯一test-run标签，并清理了本轮该问题留下的一个合成测试容器。没有操作用户生产容器。

证据：Windows七组；amd64/ARMv7/ARM64实验镜像各17组构建回归；amd64完整17组ASan/UBSan/泄漏检查通过。Linux63项文件日志/异步/心跳摘要，两个真实子进程满/闭stdout管道，第三个实际运行场景专门验证日志故障取消网络/会话、退出1、最终stopped及一次term。三架构另以当前源码重跑22健康阶段/schema案例加慢报告/重复字段/多JSON/权限检查、26监控边界、原身份/计划复制和日志排空冒烟。监控最后一处Docker .Path调整只涉及宿主脚本，已用当前脚本另做三架构完整回归及实际守卫验证；不把构建时脚本旧副本冒充新证据。ARM仍是CPU仿真。资源只采集无会话空闲快照，不是认证峰值或长时验收。

最新完整报告Native/build-msvc/ops-validation-report.json，旧compatibility-validation-report.json与更早validation-report.json保留作历史。三架构新镜像tag为ops-20261030 / ops-armv7-20261030 / ops-arm64-20261030，尚未推送或发布。下一步整理本地离线实验检查包；不得因此解除实验开关或切生产。

原生/现场后续规格见DOCKER-CPP-FIELD-ACCEPTANCE.md：源码确认旧Docker为Android32/2082，libdaproxy.so SHA256=69948f5005c308166f7de48a0520edde792ad786ed3bf52d6d0d674d6b055a3c；只有该库没有正确动态模块、实际aid和可重建方向性向量，不能补齐等价。本轮没有运行官方库或联网取模块。实体ARM/OpenWrt、真实校园网、至少一个重认证周期、服务器注销确认与认证资源峰值仍待相应资源和授权。

## 2026-10-30 检查点：PACK-02离线实验交接包完成

本地目录Native/build-msvc/experimental-bundle-20261030，包含native-source.zip（105个已核对源文件）、三种平台镜像tar.gz、28份测试/容器证据、阶段/现场规格和SHA256SUMS。镜像压缩归档合计143536062 字节；这是本次Docker保存归档的压缩文件大小，不能替代下载大小/实际内存或认证峰值。逐个核对Docker manifest标签，gzip流式解压SHA256与原导出tar相同，源码ZIP每个entry摘要与验证报告相同。没有执行docker load、推送、发布或部署。离线包为本地实验检查产物，production_ready=false、NATIVE_EXPERIMENTAL默认0；不包含真实.env/用户状态和旧Kotlin发行包。

最新报告ops-validation-report.json；停止自动化的决定保持，不重建定时任务。下一次继续先验证报告源文件身份和git状态，再按DOCKER-CPP-FIELD-ACCEPTANCE.md解决正确2082模块/实际aid/方向性向量与真实环境资源。可选旧门户诊断CLI仍明确未迁入；正常服务没有依赖它。核心离线迁移和运维兼容已完成当前可验证部分，不能把现场阻塞改写成整体生产验收通过。全部本地代码仍未提交。
