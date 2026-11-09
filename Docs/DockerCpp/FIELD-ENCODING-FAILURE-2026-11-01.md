# 现场模块编码拒绝：2026-11-01检查记录

输入为本地`Log/native-debug/`，日志事件时间为2026-10-30 16:46–16:49，不是11月1日的新一轮认证。原始文件保留在本地，不做修改。

已确认：

- docker.log与logs/dialer-2026-10-30.log逐字节相同，均672字节、SHA-256 `529b624657d45d3f33f597fd47a128c5fa427842a98d0feac190eb7085f51320`。文件日志和stdout记录正常。
- 两次启动各有三次`AUTH_FAILED MODULE_ENCODING_PROFILE_UNKNOWN`，随后paused；最终stopped。没有LOGIN_CONFIRMED，health.json的authenticated=false、autoReauthSuccessfulAuthSamples=0。
- 当前错误发生于bootstrap模块的描述字检查（高4位须为3），在解包、算法选择、ticket/login与首次keep之前。现有证据不能判断是新的编码类型、不同布局还是异常响应，不能跳过校验继续认证。
- 提交的材料没有module.bin、模块摘要、描述字或编码数字。日志本身不能恢复失败响应；旧18862字节样本只证明旧type3路径，不能补作本次输入。

后续取证采用独立`native-diagnostic-v3-module-only`包：`sh collect.sh module`仅获取初始化模块、保存原始字节和非秘密头部元数据，然后退出；不调用原生加载器、enc/dec/Inspect，不读取账户或旧配置，不进行认证。`sh collect.sh header`仅离线读取input/module.bin并输出相同元数据。旧collect/offline模式继续保留给原生对照，不能与本次module模式混用。

v3新增头部类型0/1/2/3/15以及截断/错误签名回归，共41项诊断检查通过（本地合成HTTP，无校园网请求、无原生模块执行）。已在network:none、只读根、非root、/tmp:noexec的容器核对旧真实样本，header模式记录type3、38656字节目标及原样摘要，native_module_executed=false。该旧样本验证是工具自检，不是对本次现场失败的修复或新模块验收。

取证若提示NEED_PORTAL_WHILE_ONLINE，先保持Kotlin联网；已有有效门户/ticket地址时可在新包input目录本地提供，不公开地址。否则选择允许短暂断网的时段，用随包README的一次性停止/恢复Kotlin流程收集。C++测试容器保持停止。结果目录通过WinSCP下载到开发机Log目录，原始模块及私有输入不公开。

拿到新模块后，先从原始字节核对描述字和原生加载分支，再决定增加哪一种受控解码实现；如解包后的代码也变化，需要补充新算法profile和独立双向向量。当前认证镜像未改写，现场故障仍未解决。

## 后续：收到run-iOldjG

11月1日新采集模块仍为18862字节、type3、SHA-256 `b680fa478b45ecdf06c24c1b4a3bdc0ecfcf87bd5ef8aa349276c0858a7e8c47`，与run-iCFoig逐字节相同。在原交付镜像内通过329项既有检查；新增检查中也能经真实loopback HTTP原样接收并通过生产解码。这份材料不支持“当前采样换了编码”的假设。

不能据此推断10月30日C++失败时收到相同响应：新采集时间不同，且独立工具使用固定合成Client-ID，实际C++使用持久设备身份；端点、服务端状态或运行镜像等差异尚未排除。身份是否导致响应差异也尚未证明。

因此本轮没有放宽编码检查或新增猜测算法。新增C++原位取证：每次bootstrap接收后、解码之前，记录BOOTSTRAP_RECEIVED的长度/SHA256/头部候选编码。NATIVE_CAPTURE_BOOTSTRAP默认0；显式设1后将原始响应及摘要保存到/data/diagnostics/bootstrap-1..3.bin/.json，按槽覆盖，最多约3MiB，目录0700/文件0600。只观察bootstrap，不截取ticket/login/keep的认证载荷。写入失败有固定错误码，保留原认证错误处理。

诊断包使用独立的新镜像标签，复用上次modulefix测试的Compose项目、容器名和状态卷，避免重新生成设备身份；Kotlin数据仍独立。start-capture.sh显式开启快照，export-diagnostics.sh只读导出该容器的镜像身份、日志、健康和快照，不导出.env。

验证：Linux常规20项通过，另2项私有材料测试默认skip后另行验证；包括新增真实进程取证开关测试（默认不生成快照、开启后只保存bootstrap字节）、29项快照边界/权限/链接拒绝、7项真实HTTP场景（含binary NUL和chunked）。原件/ID轮换/参数轮换各331项通过。Windows无网络核心8项通过。现场根因仍待拿到C++失败当次快照确认，不能称已修复。
