# 常见问题

> 外部参考说明（本仓库整理于 2026-09-30）：本文属于用户提供的 C 项目附件，平台、版本、操作与资源数据均描述原项目；本项目的 Windows/Docker 安装和待办请使用仓库中文文档索引。本次仅补充中文说明/可读标签，命令、许可及实现保持来源内容。

<details>
<summary>
问：除了广东地区的电信校园网可以用吗?
</summary>

**答：目前来看, 应该大部分是不行的, 因为每个省的认证流程都有差别, 认证服务器可能都不同**

**如果抓包对比流程差不多的话, 可以自行 fork 并改改流程来用**

</details>

<details>
<summary>
问：为什么我会出现 "curl 错误码: 3, 错误原因: URL 格式错误" 报错并且认证不了? (参考日志)
</summary>

```text
# 一段日志
[xxxx-xx-xx xx:xx:xx] [TID xxx] [T-0] [ERROR] [NetClient.c:xx] curl 错误码: 3, 错误原因: URL 格式错误
[xxxx-xx-xx xx:xx:xx] [TID xxx] [T-0] [DEBUG] [NetClient.c:xx] 配置 1 获取认证配置 URL: /qs/main.jsp?wlanacip=**.**.**.**&wlanuserip=**.**.**.**
```
**答：这是 UA2F/UA3F 开启之后的典型特征, 关闭即可, 或根据以下原理修改 UA2F/UA3F 配置**

- UA2F/UA3F 会将经过路由器的 HTTP 包的 UA 修改为指定 UA (默认为 F)
- 电信校园网获取认证配置靠指定 UA 获取 (例如 CCTP/android11_64/2104)
- [参考 Issue](https://github.com/BadGhost520/ESurfingClient-CVersion/issues/30)

</details>

<details>
<summary>
问：为什么在使用 opkg 管理器的版本里推荐 19.07.0 这个版本?
</summary>

**答：因为它是开始使用 LuCI2 的第一个版本, 更现代化**

</details>

<details>
<summary>
问：为什么会出现 "curl 错误码: 6, 错误信息: DNS 解析错误" 的问题?
</summary>

```text
# 一段日志
[xxxx-xx-xx xx:xx:xx] [auth#x pid=xxx] [main] [ERROR] [NetClient.c:357] curl 错误码: 6, 错误信息: DNS 解析错误
[xxxx-xx-xx xx:xx:xx] [auth#x pid=xxx] [main] [WARN] [NetClient.c:725] 主检测 URL 无法连通, 切换到备用 IP 地址 URL
```

**答：如果连续出现这两条日志但是不断网的话, 则是正常的网络波动, 会切换至备用检测地址检测, 若是断网, 则需要检查校园网的物理连接情况**

</details>
