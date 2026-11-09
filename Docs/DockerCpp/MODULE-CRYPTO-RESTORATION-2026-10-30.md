# Android32/2082 模块加载与加解密还原

日期：2026-10-30。已完成当前现场模块以及同代码、同布局换ID/换参数变体的离线还原和原生差分。此阶段交付为诊断解析器、参数化C++原语和验证证据；**尚未接入产品认证主流程，没有生成新认证镜像或证明现场上网恢复**。

## 样本与实现识别

- 模块SHA256：b680fa478b45ecdf06c24c1b4a3bdc0ecfcf87bd5ef8aa349276c0858a7e8c47，18862字节。
- Android32原生库SHA256：69948f5005c308166f7de48a0520edde792ad786ed3bf52d6d0d674d6b055a3c。
- 解包ARM32 ELF：38656字节，SHA256=03a5a57b8b80198b65ba6d7a8d8b6b3e89ea8280da4cd70bba3f2295d0d6a86e。
- 同一实现profile将选定56个参数字节置零后校验完整ELF，摘要=5d0d38e512f0c25f8f6423d2f66754f28e2af78cc9826b7776f2eff5d5a7e7d7。它同时锁定代码、导入绑定、重定位、初始化及其它数据，允许的变化仅限所选密钥/IV；外层头UUID独立解析。

头UUID不是密码函数的选择器。JNI `aid()`实际只是返回头部第二字符串，函数实现来自解包后的ELF。JNI `key()`返回第一头字符串，也不能把它直接当作已证明的网络载荷密钥。工具不调用key()，不在源码/报告/标准输出放入真实密码参数。

## 容器解包与加载

当前受控类型的布局：

```text
ASCII "001"
u8长度 + 头字段0
u8长度 + 头字段1（UUID）
5字节LZMA属性
u32小端描述字：高4位=容器编码类型，低28位=解包目标长度
编码后的压缩流
```

现场body位于文件偏移105，payload位于114；描述字0x30009700，编码类型3。流程为：**ZUC128流解码 → 按末字节计数去外层对齐填充 → LZMA1 raw解压 → ARM32 ET_DYN ELF**。本例外层填充计数4，前面填充字节并不固定，不能用PKCS7或必须全零的规则替代。LZMA字典16MiB；流没有EOS，按声明目标长度停止，与原生FINISH_ANY形式一致。

ZUC在这里用于模块容器编码，**不是该模块认证载荷的密码算法**。固定加载参数从已识别原生库的代码字面量读取，原生库完整摘要不符则拒绝。

原加载器使用PT_LOAD映射代码/数据及BSS，加载liblog/libc/libm/libstdc++/libdl，处理本样本中的R_ARM_RELATIVE、GLOB_DAT和JUMP_SLOT。通过SysV ELF hash找ZsmInit2，保留Thumb函数指针位。重建ELF的.text/.plt/.rodata与模拟器映像逐字节一致，参数池与原ELF对应区域也逐字节一致。

加载ABI：`ZsmInit2(callback, "1.1.0", out24)`，其中out+8为Code/enc、out+0x10为DeCode/dec、out+0xc为FreeResult、out+0x14为Inspect。样本相对入口分别为0x31c5、0x32d9、0x33b5、0x3455，ZsmUnLoad为0x3f0d。原加载器还存在旧ZsmInit/Code/DeCode导出兼容分支，但本样本没有使用该分支。

通用诊断工具**只解析已识别数据，不映射执行ARM ELF**；不需要在Windows宿主运行原始原生模块。

## 实际密码原语

已确认载荷原语是三层修改版TEA的CBC组合，与常见标准XTEA公式不可直接互换：

- 分组8字节；块和密钥字均为大端32位。
- 密钥48字节，分成3组16字节；IV为8字节。
- 每层32轮，32位无符号溢出；delta=0x9e3779b9。
- 加密层顺序为最后一组、第二组、第一组；解密反向。
- CBC在三层加密前异或前一密文块，首块使用模块IV；每次调用重新使用该IV。
- 原生Code把`strlen(serialized_xml)+1`传给原语，因此**包含结尾NUL**，再补零到8字节倍数。不是PKCS7；若字符串本身已对齐，NUL会使结果多出一个分组。
- 输出为大写十六进制；DeCode执行相反原语，不做XML后处理，JNI结果长度受C字符串NUL终止约束。

单层加密的已验证公式（所有运算mod 2^32）：

```text
sum = 0
重复32轮：
  a += (b XOR sum) + K[sum AND 3] + ((b << 4) XOR (b >> 5))
  sum += delta
  b += K[(sum >> 11) AND 3] + (a XOR sum) + ((a << 4) XOR (a >> 5))
```

参数来源是ELF可写段中的256字节池，不是头UUID或头字段0。当前profile由四处Code/DeCode的PC相对引用定位同一池；`skip=pool[0xfe]`、`keyBytes=pool[0xfb]`、`ivBytes=pool[0xf9]`，本例为20/48/8。取池中skip之后48字节为key，紧随8字节为IV。ZsmInit2未修改该池，无需执行ARM即可提取。实际参数只可显式写到新的私有文件，已有文件拒绝覆盖。

## XML及上下文

真实边界Hook已确认：原语收到的字节确为序列化XML加NUL；原生解密输出缓冲确为带补零的明文，之后DeCode仅strdup，没有XML后处理。这补强了上一阶段只通过self-dec建立的等价性结论。

Code在密码原语之前解析/修改/序列化XML，替换local-time，处理声明、注释、实体等。条件性平台路径还会访问wifi.interface、接口ioctl、uname、gsm.network.type、ro.build.version.sdk，并可能修改接口信息或增加sysinfo。初始化callback/版本参与dcrztag路径与内部上下文建立，但没有参与本profile密钥派生。

用户、Client-ID、MAC等正常字段来自输入XML；时间来自模块时钟。当前模拟器继承Kotlin已有strcmp Hook。节点比较本身没有首字符预筛选，但no-time/empty-time/duplicate-time实测均保留原合成IP，没有发生此前静态假设的IP覆写。不要把未触发的Hook交互当成事实，也不将未覆盖的真实Android接口/sysinfo条件视为已验收。

JNI调用ABI是`char* f(const char*)`，无独立context参数，模块可持有全局状态；不能据此声称可并发调用。返回字符串必须由模块FreeResult释放，主handle释放还包括ZsmUnLoad、头字段、解压缓冲和映射/依赖清理，不能混用分配器。后续接入仍需保留现有会话锁与释放互斥。

可选Inspect涉及额外系统/线程/网络能力，本次未调用；纯密码适配不需要它。

## 源码与复现

目录：`Products/DockerCpp/tools/native-diagnostic/`。

- `module_profile_diagnostic.py`：仅Python标准库的数据解析器。校验原生库、受控实现profile及边界，派生参数，不按UUID查算法。公开ZUC表从仓库既有zuc.c读取并校验摘要。
- `test_module_profile.py`：调用者提供私有模块的20项正/负例；仓库不包含原始模块fixture。
- `recovered_cipher.hpp/.cpp`：参数化C++20原语，无样本密钥或ID映射。
- `recovered_cipher_cli.cpp`和独立`CMakeLists.txt`：构建比较/合成向量工具，不连接认证主程序。
- `ModuleRuntimeProbe.java`、`CipherBoundaryProbe.java`：隔离模拟器私有映像和精确原语边界验证；后者须先校验代码指纹。

解析示例：

```text
python Products/DockerCpp/tools/native-diagnostic/module_profile_diagnostic.py --module <私有模块> --native-library <同版本libdaproxy.so> --private-parameters-output <新私有参数文件>
```

诊断工具构建：

```text
cmake -S Products/DockerCpp/tools/native-diagnostic -B artifacts/build/recovered-module-cipher
cmake --build artifacts/build/recovered-module-cipher --config Release
```

`recovered-cipher-check <私有56字节参数文件> <比较向量TSV> verify`验证原生结果；`generate <新输出TSV>`生成独立合成密文，拒绝空集合与已有输出覆盖。原始模块、解包ELF、私有参数、内存映像和原始日志仅留本地。

## 实际验证

| 验证 | 结果 |
| --- | --- |
| C++加密对照当前模块39组原生结果 | 39/39逐字节一致，C++反向得到预期带NUL/补零字节 |
| 独立C++生成的32组密文交给原生Dec | 32/32解出预期合成文本，包含空串、字节边界、Unicode和合成响应XML |
| 真实原语边界截获 | XML+NUL、原始密文hex、解密补零字节全部通过 |
| 只改头UUID的同代码变体 | aid随头变化，11组原生加密输出与原件一致 |
| 同ID、同代码、只改48+8参数的变体 | 11组原生密文改变，静态派生新参数后C++11/11一致 |
| 新参数C++独立32密文→新参数原生Dec | 32/32通过 |
| 静态派生参数与模拟器参数池 | 原件逐字节一致；新参数与合成预期一致 |
| 受控解析器边界回归 | 20项通过，含截断、超大目标/字典、未知编码/代码/原生库、越界、改导入/重定位、非法布局、输出覆盖拒绝 |

以上均不登录、不申请ticket、不连接校园网。它们建立的是当前函数的离线双向等价，不是运营商真实响应、首次keep或长期运行验收。

主要本地证据在`artifacts/build/module-loader-analysis/`、`native-wrapper-analysis/`、`module-runtime/`、`recovered-module-cipher/`。`module-runtime/boundary-2`和`boundary-rotated`记录原语/独立Dec检查，`rotation-runtime-check.json`记录轮换关系，`profile-source-tests.json`记录20项回归。

## 支持边界与后续

已完成的是**当前已识别实现及同布局密钥/IV和UUID轮换**。未知代码、其它ELF数据/导入绑定变化、其它编码类型0/1/2、新参数布局全部拒绝；只静态识别过的编码分支没有差分支持声明。将来出现新实现必须新增证据和profile，不能随意增加UUID别名。

后续认证接入需要把受控模块解析、XML处理及此原语接到C++会话流程，保留错误分类、并发/释放边界，再构建目标架构镜像并做现场验证。当前诊断解析器还依赖同版本原生库作为只读参数来源；产品化时需明确这份依赖的来源与交付方式。现有实验ZIP及认证主程序未被本轮改写。
