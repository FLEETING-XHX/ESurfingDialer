# C++认证接入与双向离线验证（2026-10-30）

## 当前结论

当前源码已把上一阶段恢复的受控模块解码和密码实现接入生产Session。原始模块、只换头ID、同ID只换密钥/IV三种模块，均通过21组请求、21组独立原生合成响应及生产会话链。真实校园网登录、服务端响应、首次keep和长期稳定性仍未验收；本阶段未操作路由器、使用实际账户、更新实验ZIP、提交或推送代码。

这是对[模块恢复记录](MODULE-CRYPTO-RESTORATION-2026-10-30.md)的后续接入；该记录中“尚未接入”的结论仅对应前一阶段。

## 实现

生产默认流程为：获取module →校验001/type3容器→ZUC解码→有界LZMA raw解包→受控ARM32 ELF数据校验→动态提取key/IV及头ID→发送ticket→login→首次keep/后续keep→term。不会执行ARM代码，也不会因未知模块而猜测旧工厂ID或反复重取模块。

`src/module_loader.cpp`不依赖UUID选择算法；除选中56字节参数以外，对解包完整ELF进行masked SHA-256校验，保留代码、导入、重定位与其余数据约束。支持已识别实现的相同布局密钥/IV及头ID变化，拒绝未知代码、编码、布局和原生库。输入/解包限1MiB，LZMA字典限16MiB。缺失依赖或解码错误转为固定错误码，不把路径、参数或模块正文写日志。

`src/native_cipher.cpp`是生产和诊断CLI共用的三层modified TEA CBC实现。包括XML末尾NUL及8字节补零，输出大写hex；接收方向严格检查hex、长度、终止NUL和补零。每实例参数独立、调用互斥。`src/protocol.cpp`处理XML声明、实体、空标签、注释/CDATA和local-time，每个请求取一次时间。请求checksum覆盖最终密文。

生产以`profiles/android32-2082-loader.so`作为30KiB只读数据资产，来自仓库原Docker资源，完整摘要须匹配`69948f5005c308166f7de48a0520edde792ad786ed3bf52d6d0d674d6b055a3c`。默认路径`/app/module-profiles/android32-2082-loader.so`；可通过`NATIVE_MODULE_LIBRARY`指定路径，但仍须满足相同摘要。不会dlopen/JNI，也没有Java运行时。Linux网络构建新增LibLZMA/OpenSSL Crypto依赖，Dockerfile及Dockerfile.test已补齐；Windows默认无网络构建不引入它们。

旧95工厂仍用于参考算法回归；旧Session mock通过仅测试源包含的工厂注入继续运行。真实001容器在测试夹具内也走生产解码，生产可执行文件没有零头fixture绕过路径。

## 证据与覆盖

| 检查 | 结果 |
| --- | --- |
| C++数据解码器正/负例 | 14项，含ID/参数轮换、未知库/代码、截断、超限与未知编码 |
| 原件及两种轮换模块 | 每种329项生产接入检查 |
| 每种原生请求 | 21组，含真实形状ticket/login/keep/term、中文/emoji、XML五种转义、CR/LF/tab、1/7/8/9等边界至4096字节载荷、注释/CDATA/空标签 |
| 每种独立原生响应 | 21组，含ticket/login/keep、中文实体、空文本及1至4096字节边界、合法加密但无效XML/缺字段 |
| 会话链 | 两个新Session均重新取模块；核对动态Algo-ID、Client-ID、密文checksum、动态keep/term端点、连续keep与3秒term预算 |
| 损坏数据 | 错误hex/长度/补零、错误XML、未知/损坏模块均拒绝；模块失败仅发bootstrap，没有ticket或登录请求；已知旧工厂ID也不绕过模块校验 |
| ASan/UBSan | 14项解码检查及三种模块各329项接入检查通过，无报告 |
| 无私有文件常规回归 | native-cipher-regression保存公开合成参数的独立原生黄金密文；私有模块测试无输入明确skip，不能计入实测通过 |
| Linux/Windows构建 | Linux常规18项通过，2项私有输入测试在默认CTest中skip后单独实测；Windows无网络构建8项通过 |
| 原参考来源/Python解析器 | 37文件+独立工厂身份、475参考向量/95分支；20项Python受控解析回归通过 |

独立响应来源：`SessionInteropProbe.java`先校验原生代码指纹，再仅替换已识别原生加密primitive的合成明文输入；key/IV、CBC与轮函数仍由原模块提供。每组先经原生DeCode核对，再交给生产C++解密；响应黄金值不是本C++加密产生。因此比C++自身往返更强，但这些响应仍不是运营商服务器抓取的响应。

生产C++本地长度上限是64KiB，常规用例检查该边界的自身往返及超限拒绝；不能将此写成原生64KiB等价。对照探针的64KiB响应在原生路径发生`UC_ERR_READ_UNMAPPED`/`NATIVE_ENCRYPT_FAILED`，没有形成完整向量；本轮独立原生响应覆盖到4096字节。真实Android接口/sysinfo条件分支、其它模块实现和其它CPU架构本轮也未验收。

所有运行均为无网络隔离容器，未调用Inspect、ticket服务器或登录接口。`tests/module_session_regression.cpp`中的HTTP实现只是内存合成服务器。

## 重复运行

先以当前源码准备本地amd64 builder（会运行常规CTest）：

```powershell
docker build --target builder -t esurfing-diagnostic:cpp-integration-builder -f Products/DockerCpp/Dockerfile Products/DockerCpp
```

准备已固定的glibc Java17对照镜像后，使用本地Kotlin参考JAR、模块和已构造的未知代码变体调用：

```powershell
./Products/DockerCpp/tools/native-diagnostic/Run-SessionInterop.ps1 `
  -ModulePath <私有模块> -KotlinJar <同版本Kotlin参考JAR> `
  -UnknownCodeModule <篡改代码模块> -OutputDirectory <新的本地artifacts目录>
```

脚本从当前NativeDiagnostic/SessionInteropProbe源码构建探针，C++测试生成纯合成输入，原生探针生成向量，最后生产C++核对。所有容器`--network none`、只读根、非root、无capabilities；只有对照模拟器使用可执行的临时目录。不会拉取镜像或覆盖已有输出目录。换ID/参数变体可用相同脚本另选新目录运行。

本轮原始中间证据位于本地忽略目录`artifacts/build/production-integration/`和`artifacts/build/module-runtime/integration-*`。现场模块、私有参数、解包ELF、原始日志不进入源码；公开合成黄金参数为`key=00..2f, IV=a0..a7`，不含现场值。最终验证摘要保存在`production-integration/validation.json`。

下一步需要构建更新的目标架构实验镜像和离线包，再在保留Kotlin回退方案的前提下进行一次真实认证验收。不能继续使用旧ZIP来验证本次源码修复。
