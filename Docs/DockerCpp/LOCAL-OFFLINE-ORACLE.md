# 本机可重复离线原生对照

2026-10-30，本机对照环境已修复。适用边界：Docker Desktop的linux/amd64容器、指定Kotlin v2.4 JAR与已采集Android32/2082模块；不是C++认证算法已修复，也不是响应方向或现场登录验收。

## 确认的原因与处理

1. 路由器的v1诊断容器`/tmp`未显式允许执行，无法加载解压出的JNI后端库；v2通过专用`exec`临时挂载解决。根目录保持只读，不开启容器特权。
2. 本机Alpine环境的原生模拟器初始化失败，故本机oracle改用固定摘要的glibc Java17镜像。并未修改用户原Kotlin容器或依赖。
3. 排查期间的临时调试构建额外调用`NativeLoader.loadLibrary`，随后Unidbg工厂再次加载同一后端库。在同一glibc运行时的最小探针中，不加载任何现场模块：正常初始化/关闭退出0；额外预加载后触发动态加载器SIGSEGV，容器退出127。正式入口仅由Session/Unidbg管理加载，不保留调试预加载。
4. XML输入的原生结果依赖运行时钟。前10组非XML向量跨时间相同，第11组XML结果会变化。仅在`offline`模式显式设置`DIAGNOSTIC_EPOCH_SECONDS`时，替换模拟器libc的`gettimeofday`，微秒固定0，Java和容器时区固定Asia/Shanghai。现场`collect`禁止固定时钟。本轮不宣称覆盖其他时间函数或所有模块的随机状态。

## 可复现入口

脚本：`Products/DockerCpp/tools/native-diagnostic/Run-OfflineOracle.ps1`。

运行时固定为：

```text
eclipse-temurin:17-jre-jammy@sha256:c35794d1348777e6bc310b9b7f420098ef8f37e988199549b545fded79a895a2
```

镜像必须已在本机。脚本只使用`--pull never`，不存在时提示准备命令，不自动下载。三个输入均为本地文件：模块、Kotlin完整JAR、包含离线时钟支持的诊断JAR。原始模块和原生库不在Windows宿主进程中执行。

示例（从仓库根目录；将路径替换为私有材料实际位置）：

```powershell
& ./Products/DockerCpp/tools/native-diagnostic/Run-OfflineOracle.ps1 `
  -ModulePath './private-input/module.bin' `
  -KotlinJar './private-input/client.jar' `
  -DiagnosticJar './artifacts/packages/DockerCpp/native-diagnostic-local-oracle-v3/native-diagnostic.jar' `
  -ReferenceDirectory './private-input/field-result' `
  -EpochSeconds 1793339591 `
  -Repeat 3
```

`ReferenceDirectory`须包含`report.properties`和`native-enc.tsv`，必须来自同一模块。上例时刻来自本次现场日志中的模拟器`gettimeofday`，不是随意调整时钟来掩盖比较差异。其他现场记录应使用其实际采集时刻；没有现场参考时可不传`ReferenceDirectory`，默认使用合成时刻1767225600（UTC 2026-01-01 00:00:00）建立新的重复运行基线。

脚本每轮创建新容器/新合成状态：network:none、非root UID10001、只读根目录、cap_drop=ALL、no-new-privileges、独立exec临时挂载、768MiB内存上限、core dump禁用。只读挂载所选的模块和两个JAR，仅输出目录可写。不挂载真实.env/data，不调用旧容器或传入真实身份，不登录、不申请ticket、不注销。

输出保存在新建的`artifacts/build/native-oracle/<UTC时间>-<随机后缀>`。每轮保留摘要、模块副本、合成enc向量和私有日志；顶层`validation.json`记录运行时/输入摘要、时钟、各轮状态及现场比对结果。输出已存在、模块/参考摘要不符、任一轮失败、向量或原生ID不匹配时停止，不自动换后端、放宽容器权限或继续重试。

## 本轮结果

- 输入模块SHA256：b680fa478b45ecdf06c24c1b4a3bdc0ecfcf87bd5ef8aa349276c0858a7e8c47。
- 原生库SHA256：69948f5005c308166f7de48a0520edde792ad786ed3bf52d6d0d674d6b055a3c。
- Kotlin JAR SHA256：3e6341584f6e3c55ccef0a08dfd357c77d4d510f196a6fade9cdd2c14602e041。
- 三轮独立容器均退出0，原生ID一致；全部11组输入/输出与路由器参考精确一致。
- 三轮`native-enc.tsv`文件SHA256均为c53318aaf7918329cb42a2a2e260573eceea0e5261437aeb22f82c51e655dca0，与路由器文件一致。
- 证据：`artifacts/build/native-oracle/validated-fixed-clock-reference/validation.json`，最小控制探针在同目录级别`backend-control-single`和`backend-control-preload`；全部仅本地保存。

本轮没有修改C++认证算法或产品版本，没有生成新认证镜像。响应方向、未知/轮换模块等价、实体路由器认证与长期稳定性仍需后续工作。原始模块、原生密钥、私有日志及结果文件不可整批加入公开仓库。
