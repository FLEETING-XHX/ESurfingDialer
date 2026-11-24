# WindowsCpp 原生认证依赖

- DockerCpp 模块加载及原生算法源码来自本仓库提交 `a6227d52615e1110d728b756025f5261bd89e10b`。Windows 适配用 Qt Core 计算 SHA-256，并用 LZMA SDK 解码同一有界数据流。算法指纹和参数提取规则沿用原实现。
- AES、SM4、ZUC 基础实现来自 DockerCpp 已记录的 Apache-2.0 代码。分发目录附带算法许可。上游和修改记录见 DockerCpp 的 `third_party/NOTICE.md` 及 `third_party/ciphers/provenance.json`。
- `lzma/` 的五个文件来自 [7-Zip 26.06 源码](https://github.com/ip7z/7zip/tree/26.06/C)，保留 Igor Pavlov 的 public-domain 声明。只编译 LZMA 解码器，无需安装 7-Zip。
- `Products/Docker/src/main/resources/libdaproxy.so` 作为只读数据嵌入，与 DockerCpp 2082 loader data 相同。加载器检查 SHA-256 `69948f5005c308166f7de48a0520edde792ad786ed3bf52d6d0d674d6b055a3c`，只读取字节，Windows 不加载或执行该 ELF。该数据的权利状态按原仓库资产处理，Apache-2.0 算法许可以外的材料不继承该许可。
