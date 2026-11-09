# Docker C++认证算法诊断包 v3

开发机SM4响应对照：`Run-SessionInterop.ps1 -ResponseWithoutNul`让非空合成响应直接按XML字节做原生PKCS7加密，再用原生DeCode验证；默认模式仍包含NUL。两者都不是服务器响应。空响应仍用NUL以满足原生加密primitive的非空输入要求。现场无需重跑Java采集，详见[响应修复](../../../../Docs/DockerCpp/SM4-RESPONSE-FIX-2026-11-01.md)。

## 本次 MODULE_ENCODING_PROFILE_UNKNOWN 取证

v3增加模块头描述及`module`/`header`两种模式，用于确认当前服务器返回的模块编码。两种模式均不执行模块/原生库，不申请认证ticket、不登录、不心跳、不注销，不读取账户或旧.env。生产C++已接入已识别type3实现；目前现场新响应未通过编码检查，日志本身没有其描述字，不能仅靠截图判断实际编码。

新包请解压到独立目录（例如`/root/native-diagnostic-v3-module-only`），执行：

```sh
cd /root/native-diagnostic-v3-module-only
sha256sum -c SHA256SUMS
sh collect.sh module
```

`module`仅使用本地Kotlin v2.4镜像发一次全零Algo-ID模块初始化请求，原样保存module.bin并输出长度、摘要、头候选ID、编码类型及声明大小，然后退出。程序不会主动停止Kotlin；但自动门户发现可能在已联网时返回`NEED_PORTAL_WHILE_ONLINE`。若本目录已有当前有效的input/ticket-url.txt或input/portal-url.txt，可保持Kotlin运行。地址不应公开。没有有效地址时，在允许短暂断网的时段手动执行下面的一次性流程：

```sh
(
  trap 'docker start ESurfingDialer-v2.4 >/dev/null' EXIT
  docker stop ESurfingDialer-v2.4 || exit 1
  sh collect.sh module
)
```

先确认C++测试容器已停止。这个流程会短暂断网，退出时尝试恢复Kotlin；恢复失败应手动处理，不能当作已经恢复。诊断包不会自动重试，不应循环采集。

结果位于`results/run-XXXXXX/`。请将这次新建的整个结果目录通过WinSCP下载到开发机的`Log`目录，由本地分析读取；原始模块和地址文件不要公开发布。先可查看report.properties的`container_encoding_type`、`module_sha256`、`native_module_executed=false`、`login_attempted=false`。旧module.bin只能验证旧样本，不能解释新现场响应。

`header`只读取input/module.bin，在`network:none`容器内记录相同头部信息，完全不联网、不执行原生库。`collect`/`offline`仍为v2的原生oracle流程，不用于本轮轻量取证。

## 原v2流程与边界

v2修正v1的临时目录挂载：模拟器需要从专用`/tmp`加载原生库，显式允许该临时挂载执行；根目录仍只读，保留cap_drop与无特权。摘要增加失败异常类型、原生后端与更细阶段；原生ID一旦取得立即落盘。JVM崩溃日志保存在私有结果目录，不应公开上传。

本机重复离线对照使用新增的 `Run-OfflineOracle.ps1`，详见[本机离线对照说明](../../../../Docs/DockerCpp/LOCAL-OFFLINE-ORACLE.md)。它固定glibc Java17运行时、单次JNI加载和仅离线生效的模拟器时间。此入口不操作路由器，也不使用Alpine现场镜像作为本机oracle运行时。

模块数据解析与参数化C++原语已完成已识别profile的离线还原及生产接入，详见[生产接入验证](../../../../Docs/DockerCpp/CPP-MODULE-INTEGRATION-2026-10-30.md)。当前文件夹中的解析/对照工具仍是诊断工具，不是认证程序；不需要用户在路由器上运行开发探针。

已经取得模块时无需再次联网收集：将原`results/run-XXXXXX/module.bin`复制到新包的`input/module.bin`，运行`sh collect.sh offline`。此模式强制`network:none`，可以保持Kotlin正常运行。默认Unicorn2后端；只有明确原服务使用Dynarmic或排查后端时，才用`DIAGNOSTIC_BACKEND=dynarmic sh collect.sh offline`。

适用于当前x86_64/iStoreOS和本地`esurfing-dialer:v2.4`镜像。不含镜像、Java运行时或账号，使用本地Kotlin镜像中的`/app/client.jar`和Java17，独立运行，绝不执行旧容器的停止/重启/exec，也不挂载旧data或.env。

## 路由器操作

1. 将整个诊断目录上传到一个独立的新目录，例如`/root/native-diagnostic-v1`。不要覆盖旧部署目录。
2. SSH执行：

```sh
cd /root/native-diagnostic-v1
sha256sum -c SHA256SUMS
sh collect.sh
```

所有结果保存在每次新建的`results/run-XXXXXX`；先只提供其中的`report.properties`。不要公开`module.bin`、`private-tool.log`、地址文件或合成加密结果。每轮最多90秒；退出124表示超时，应保留本轮结果，不循环执行。

如果结果为`NEED_PORTAL_WHILE_ONLINE`：当前Kotlin已经联网，自动探测没有门户。先保持Kotlin运行；如果保留了校园网登录页完整地址，用WinSCP在本包的`input`目录新建`portal-url.txt`，只填该地址的一行，再执行`sh collect.sh`。地址只在本地使用，不要发到公开聊天。

如果没有保留门户地址，不要猜IP或强制改URL。需要另约短暂断网窗口，手动停止Kotlin后，从连此路由器的电脑访问HTTP页面取得校园网重定向登录页完整地址；保存到上述文件后再收集。诊断不登录，完成后回原Kotlin目录启动旧版恢复网络。这一步会中断上网，应等方便时再做。

如果已明确取得当前通道的`ticket.cgi`完整地址，可改用`input/ticket-url.txt`；优先级高于门户地址。只向此地址发送一次全零Algo-ID初始化请求，使用固定合成Client-ID，不发送账号、密码、真实MAC或登录XML。不接受重定向，不关闭TLS校验，不自动换真实身份重试。

## 会收集什么

- 模块原始字节、长度、SHA256；模块头候选ID。
- 当前镜像内原生库SHA256、原生load→aid结果。
- 原生enc的合成输入/输出以及顺序，包括字节边界和合成XML；不是用户认证流量。
- 镜像ID、工具时间及阶段。原始文件只保留在本地。

原生库调用仍可能因模块格式、模拟器环境或上下文失败。模块先保存、摘要先落盘，然后才调用原生库；失败或超时保留已收集部分。`DIAGNOSTIC_FAILED`并不表示已证明服务端拒绝合成身份，需根据私有材料继续定位。

当前工具**不请求ticket、不登录、不心跳、不注销**；模块初始化请求仍会访问校园服务器，不能绝对保证服务端无任何副作用。它不会主动关闭现有会话。不要同时启动C++认证容器。

## 验证边界与后续修复

此包是独立取证工具，不是修复镜像。摘要确认模块身份与原生ID后，再定位C++候选解析、缺失算法或上下文差异。`native-enc.tsv`只用于请求方向比较；未获得独立合法响应方向向量，不能用自身enc→dec往返冒充原生等价。全局身份采用合成值，不宣称覆盖真实设备绑定或所有会话条件。

诊断代码不调用`key()`，不导出原生密钥。不要将原始模块、研究产物或路由器配置整批提交公开仓库。源码修复与现场首次登录、首次keep、恢复及长期验收分开记录。

开发机构建：`Build-Diagnostic.ps1 -KotlinJar <本地v2.4完整JAR路径>`；编译器需JDK17。打包仅包含诊断class、脚本和中文说明，不替换Kotlin JAR或旧运行镜像。
