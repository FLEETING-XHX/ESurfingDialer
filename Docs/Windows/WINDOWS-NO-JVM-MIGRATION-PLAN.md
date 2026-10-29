# Windows 认证核心去 JVM 可行性与迁移路线（2026-09-30）

2026-09-30 用户确定：先修好现有客户端并完成真实长时间稳定性验收，再按后续授权实施语言迁移。本计划当前为可行性研究；资源估算与持续记录的问题原因/回归要求见[稳定性与迁移基线](WINDOWS-STABILITY-AND-MIGRATION-BASELINE.md)。

## 可行性结论

可以将门户发现、HTTP/XML 协议、会话、登录、心跳和恢复状态机移植到 C# 或 Go。完全移除 JVM 还必须替换当前依赖 Unidbg 的 Android 原生加解密路线：为实际使用的算法实现本机版本，或从参考 C 源码抽取经过验证的算法库。本次是可行性审查，没有开始改写生产认证核心。

当前运行结构是 WPF/.NET 界面 → Kotlin/JVM 核心 → Unidbg Android 模拟环境 → ARM64 原生库。09-30 后续已完成既有安装包分组体积与无账号短时资源测量，工程估算见[资源基线](WINDOWS-STABILITY-AND-MIGRATION-BASELINE.md)。真实认证后的长期资源与新核心尚未实测，预计降幅不属于实现结果或保证。

## 本地可用的技术起点

参考 [BadGhost520/ESurfingClient-CVersion](https://github.com/BadGhost520/ESurfingClient-CVersion) 的本地 2.1.1-r4 源码快照（仅供研究，不随本仓库提交），其中包含：

- `esurfingclient/main/src/cipher/CipherFactory.c`：按 Algo-ID 选择算法，覆盖 Linux 与新版 Android 路线；部分旧 Android 分支已注释停用，不能将文件存在等同于当前支持。
- `cipher/algo/android`：双层 AES、双层 3DES、六层 DES、改版 TEA、SM4 和 SNOW3G 等可读实现。
- `cipher/algo/ios/ios_zsm.c`：部分动态 ZSM 的 TEA 解包、LZMA 解压和 JS 格式字段提取路线；这不是对任意动态模块的通用支持。
- `DialerClient.c`：初始化、票据、登录和心跳流程；`CipherInterface.h` 定义加密、解密和销毁接口。

已具体阅读 Android 双层 AES-CBC：它包含两次分层加密、层内 IV 前缀和零填充；不能仅调用普通 AES-CBC 的默认填充模式就认为兼容。其他变体也需要分别确认层次顺序、字节序、截断/补齐规则、密钥材料、IV、文本编码与十六进制格式。

参考实现是可用于研究和对照的起点，不是本校算法已被验证的证据。当前校园网的 ZSM 格式与 Algo-ID 尚未确认，头部 UUID 还可能是模块标识。不能根据算法名字选择参数或根据旧版静态映射猜测现场密钥。

## C# 与 Go 的选择

| 路线 | 适用目标 | 实施要点 |
| --- | --- | --- |
| C# 认证库接入现有 WPF | 优先做好当前 Windows 客户端 | 复用现有 .NET 环境和界面，使用异步 HTTP/定时任务；纯托管算法验证通过后可在一个进程运行，仍需隔离错误与取消边界 |
| C# 核心 + 本机 C 算法 DLL | 分阶段复用可验证的 C 算法 | 抽取算法与解包模块，改为独立会话句柄和明确缓冲区所有权，通过 P/Invoke 调用；前期可以使用独立 helper 隔离原生崩溃 |
| Go 独立认证核心 | 后续同时维护 Windows、Docker 等平台 | 保留 WPF 界面，通过受控 IPC 接入 Go 核心；纯 Go 算法便于独立构建，复用 C 需 cgo 和相应平台工具链 |

本项目优先推荐 C# 核心：Windows 界面已经采用 C#/.NET，可以减少一套语言和额外运行环境。若未来跨平台后台核心是首要目标，Go 更值得评估；不根据语言名称断言哪种方案一定占用更小。

当前 `libdaproxy.so` 为 ELF 64 位 ARM 原生资源，依赖 Android/JNI 模拟环境；迁移到 Windows 的本机调用路线需重新实现或编译适配的 Windows 算法库，不能将原有二进制直接当作 Windows DLL 使用。参考 C 工厂依赖线程索引与全局程序状态，日志也可能输出明文/密文，应改为每会话状态、明确内存释放和本项目的脱敏规则，不能原样嵌入客户端。

## 学习与验收顺序

1. **整理规格和资源基线**：将现有请求字段、状态机、超时、恢复预算、会话生命周期写成可核对的规格；分别测量 WPF 和 Java 进程的内存、CPU、启动耗时、I/O 与长时间增长。
2. **确认真实格式/算法**：使用受控现场诊断确认通道、ZSM 格式与 Algo-ID；对照同网已知可用客户端。原始 ZSM、密码、票据及密钥不进入公共记录。
3. **建立固定向量**：从一个已确认算法开始，使用合成测试材料和空串、块边界、中文、特殊字符等输入；新实现与可信参考逐字节比较密文、解密输出及报文校验。只有自身加密再解密成功，不足以证明协议兼容。
4. **搭建独立新核心**：先迁移协议与可验证算法，在隔离目录测试生命周期、异常、暂停、停止及恢复；保留现有实现作为对照。所有已验证的通道/算法均需明确注册能力，未知格式明确报告不支持。
5. **现场与性能验收**：验证 ticket→有效会话→票据→登录→确认心跳，之后覆盖切网、休眠、手动停止和长期运行；测量整个客户端的资源总量。新核心通过后再按用户授权接入正式安装包并移除 JVM 依赖。

当前没有现场格式/Algo-ID 和真实算法向量，因此不能给出可靠的完整迁移工期。近期以现有客户端修复、现场证据和规格整理为主，新语言核心及算法移植在稳定基线形成后开展。整体难度主要取决于实际算法与动态 ZSM 覆盖范围，不是 HTTP/XML 的语法翻译。

## Native AOT 的适用边界

C# 独立核心可以评估 Native AOT，它将 IL 提前编译为本机代码，官方说明其启动与内存占用有相应收益；依赖仍需通过兼容性检查。Native AOT 保留所需运行时组件，不能理解为所有运行时开销归零。现有 WPF 使用反射且官方未启用 trimming 支持，不能直接把整个 WPF 客户端当作可完成 AOT 的方案。初期使用普通 .NET 认证库已经可以移除额外 JVM；AOT 是之后的独立优化选项。

技术依据：[.NET P/Invoke](https://learn.microsoft.com/en-us/dotnet/standard/native-interop/pinvoke)、[Go cgo](https://pkg.go.dev/cmd/cgo)、[Native AOT](https://learn.microsoft.com/en-us/dotnet/core/deploying/native-aot/)、[WPF trimming 限制](https://learn.microsoft.com/en-us/dotnet/core/deploying/trimming/incompatibilities)。算法与动态 ZSM 判断基于本地参考源码，没有运行该程序或声称它覆盖当前校园网。根 LICENSE 为 Apache-2.0，实际复用前仍需逐个检查源文件与第三方声明并保留必要说明。
