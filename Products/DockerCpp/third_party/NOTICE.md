# 独立算法来源

仅引入 BadGhost520/ESurfingClient-CVersion 的cipher代码，提交f31a19d5c9440fae421df4e4277c4d350df3bf01（v2.2.1-r7）。来源：https://github.com/BadGhost520/ESurfingClient-CVersion 。保留上游Apache-2.0文本在ciphers/LICENSE，逐文件身份和修改说明在ciphers/provenance.json。

factory.c仅保留算法构造函数，移除上游应用全局状态/线程索引及日志，入口改为es_create_cipher。算法/头文件及独立SimEvp兼容层原样复制（文件清单见provenance.json）。未引入上游Web、Mongoose、账户、网络或监管程序。C++适配增加长度/严格hex检查、每实例所有权与互斥，不改变算法。

工厂表存在及自身往返通过不等于当前Docker ARM32/2082的native等价或校园网兼容。不能根据Windows现场候选直接证明Docker算法。未知模块拒绝，不复用上游无限重取行为。

本项目后续修改：sm4_common.c的SBOX左移与snow3g_variant.c三处uint16_t提升左移改为显式uint32_t左移，消除UBSan报错。provenance.json分别保留原上游摘要与local_sha256。tests/reference-vectors.tsv是从修改前的参考镜像以全'a'合成输入生成的475条输出；修改后逐条相等。它们不是服务器或ARM32原生黄金向量，且不含用户账号/票据。复核脚本见scripts/Verify-Sources.ps1。
