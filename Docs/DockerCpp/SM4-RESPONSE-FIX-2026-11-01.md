# SM4响应终止字节兼容修复（2026-11-01）

本包针对上一版type0/SM4测试的 `CIPHERTEXT_PADDING_INVALID`。截图中的19084字节模块摘要仍是 `79ec708a1ac6be6f611ecbc95744f564a3c1b439643f1cb77ed88a36a7c5b131`，未出现模块解析错误。该错误码此前同时表示PKCS7错误和解密后没有末尾NUL，单靠截图无法区分。

## 已确认的缺陷与修复

客户端原生Code的输入含末尾NUL，不代表服务器响应也必须含NUL。离线用同一模块的原生SM4 primitive加密不带NUL的合成XML，原生DeCode能还原XML，上一测试镜像中的C++却报出与截图相同的错误。

本次修复在严格验证每个PKCS7字节后，接受无NUL的明文，或移除一个可选的末尾NUL。请求加密保持原生Code的NUL规则。内部/多个NUL仍拒绝，单独报告 `PLAINTEXT_NUL_INVALID`，不再与真正的填充错误混在一起。没有通过关闭PKCS7校验来放行响应。

新增17个原生公开合成参数黄金响应（覆盖全部16种填充长度及中文XML），以及空明文、65536字节上限、超长、内部NUL和损坏填充边界。实际模块、改头ID和改参数三种模块，分别比较含NUL与无NUL响应的完整ticket/login/keep/term合成会话；旧TEA路径也回归。Windows核心9组测试通过，最终Linux/会话/镜像验证结果见随包OFFLINE-VALIDATION.json和BUILDINFO.json。

**这证明并修复了一个离线兼容性缺陷，尚未证明截图中的真实服务器响应恰好采用此形式。** 本轮没有取得真实ticket响应，没有访问校园网或使用账号；原生响应向量来自合成输入，而非服务器。真实认证、首次心跳及持续上网仍需下面的一次现场测试。

## 路由器更新

将ZIP解压到新目录，复制上一 `type0-sm4-fix` 测试目录的 `.env` 到新目录。保留 `NATIVE_EXPERIMENTAL=1`，日常 `NATIVE_CAPTURE_BOOTSTRAP=0`。本包复用之前测试的Compose项目、容器名和状态卷，保留Client-ID/MAC。

在新目录中先安装，准备好后再短暂停止Kotlin测试：

```sh
docker stop ESurfingDialerNative-v3.0.0-modulefix-test
sh install.sh
docker stop ESurfingDialer-v2.4
sh start.sh
docker compose -f compose.native.yml logs --tail 80
```

新镜像应为 `esurfing-dialer-native:v3.0.0-sm4-response-fix-amd64`。如需确认，可执行：

```sh
docker inspect -f '{{.Config.Image}}' ESurfingDialerNative-v3.0.0-modulefix-test
```

首次心跳确认后应见 `LOGIN_CONFIRMED`，然后观察10–15分钟。浏览器能上网本身不能证明C++认证成功。2分钟内未确认、再次backoff/paused或退出，立即回退：

```sh
docker compose -f compose.native.yml stop
docker start ESurfingDialer-v2.4
```

反馈新镜像名和日志末80行即可。若仍报 `CIPHERTEXT_PADDING_INVALID`，此版已将该码收窄为实际PKCS7校验失败，需要另取实际响应验证，不能继续假定是末尾NUL；重复收集相同bootstrap模块不能回答这一问题。不需要重跑之前的Java模块采集。保留旧目录/卷，不执行 `down -v`，不同时运行两个认证容器；`.env`、原始模块和私有协议资料不公开。
