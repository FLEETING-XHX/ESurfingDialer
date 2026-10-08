# Docker beta3 门户修复本地交付记录

日期：2026-10-08。用户授权将最新门户兼容修复重新打包为beta3并立即测试。打包当时未提交或推送源码；测试包仅本地交付，未上传或公开发布。后续源码同步不改变本文记录的ZIP。

同日现场进展：用户更新后已完成一次真实认证并确认可上网，日志包含登录响应校验与首次keep心跳确认；长时间运行和恢复仍待验收，详见文末。下文构建/交付检查保留打包当时边界。

## 交付产物

- ZIP：`ESurfingDialer-Docker/00_最终部署包/Docker_v1.4.0-beta.3_20261008/ESurfingDialer-Docker-v1.4.0-beta.3.zip`。
- 大小：238862243字节。
- SHA256：`928e64f223445460158f3d7bf7600043d1febecdf4fca7738526e92850ac54e8`。
- 版本仍为`v1.4.0-beta.3`，构建标识`beta3-portal-fix-20261008`；仅支持linux/amd64（x86_64）。
- 镜像`esurfing-dialer:v1.4.0-beta.3`，容器`ESurfingDialer-Beta3`，Compose项目`esurfing-dialer-beta3`。
- JAR SHA256：`a05de2562492fcf6eda26279630b2f2a89a52734a92c1dfc903b6ef9f6e993f4`。
- 镜像配置/ID：`sha256:cfd8bc13562c2de53216b774859bad4d3bbb8b3a85d5d4e875b5fc1671c98524`；OCI清单：`sha256:3f8cb779af28f250eac25df91638445845c8edb490f4d6db16a9e6ca878c3d83`。
- `BUILDINFO.json`记录源码HEAD `8f4533e5d0b0d97091dbfc6b7195a332eb53d279`、`sourceDirty=true`与完整Docker输入文件摘要，包括尚未跟踪的新诊断/回归源码。HEAD本身不代表本次修复已提交。

同目录提供解压包、`ZIP-SHA256SUMS.txt`、`VERIFICATION.json`和中文交付说明。包不含用户`.env`、`data`或本机协作文件。旧10-07包的摘要与验证仍见[原交付记录](DOCKER-LOCAL-BETA3-2026-10-07.md)；本轮开始时原交付目录为空，没有恢复或覆盖其中历史文件。修正文档前的候选包另存于忽略的构建目录，最终交付以本页摘要为准。

## 本次进入镜像的修复

[现场分析](DOCKER-BETA3-PORTAL-FAILURE-2026-10-07.md)确认属性查询串裸`&`与等值重复auth构成连续阻断点。本包包含属性查询分隔符兼容、全量验证后合并规范化相同auth、处理指令边界修正及固定类别XML诊断。不同地址、坏字段、重复ticket和其他非法XML继续拒绝；安全XML限制保持。

没有更换原生库、UA、算法或设备身份，没有修改Windows或停止注销流程。退出后偶发仍联网继续由DOPT-08独立跟踪。

## 实际验证

Docker源码目录使用JDK17、缓存Gradle8.4运行`check shadowJar --offline --console=plain`，成功用时2分11秒；276项认证、113项运行策略、41项监督和18项日志检查通过。日志位于本机`build/beta3-portal-package-20261008/`。

打包器验证基础镜像SHA256、全部blob/rootfs、OCI/Docker清单及镜像内JAR/脚本。交付验证脚本9组检查全部通过：

- ZIP CRC、全部文件对应与校验和，完整源码输入摘要集合。
- 包内JAR的门户类与编译输出逐字节一致、原生资源不变、无回归测试类；直接运行该JAR，以合成属性裸`&`加嵌套等值auth输入得到`PORTAL_CONFIG_OK`，未发网络或认证请求。
- 镜像版本/构建/JAR标签、运行脚本及shell语法一致。
- 真实Docker Compose配置解析、合成特殊字符凭据原值往返；不访问daemon。
- 安装替身只创建不启动，保留既有凭据/身份，拒绝重名与arm64。
- 默认启动与`--force-recreate`均执行其他版本互斥和daemon检查；非法参数在调用Docker前退出。

首次审查发现手动首次安装段紧接更新章节，可能误导用户覆盖`.env`；已独立标注“首次安装”并将复制模板改为仅在文件不存在时执行，重新打包后全部交付检查通过。

## 现场更新与验收

在原beta3目录保留`.env`和`data`，覆盖解压新交付文件并校验，停止旧beta3、导入新镜像，再执行`sh start-beta3.sh --force-recreate`。详细命令与镜像ID核对见包内`README_BETA.md`。镜像标签相同，仅重启旧容器不会替换程序；正式版/beta1/beta2保持停止后测试。

没有实际执行docker build/load、容器启动或校园网登录。门户兼容本地通过不能证明后续原生会话、ticket、账号登录、心跳或长期稳定已恢复；这些仍需用户本次现场反馈。

## 用户本次现场反馈

上述未执行实际容器/认证指维护者打包阶段。同日用户部署新包后提供107行日志并确认可上网：旧失败会话与新成功会话分开记录，11:08:13启动，11:08:19完成`LOGIN_CONFIRMED attempt=1`和`LOGIN_SUCCESS`；中间原生初始化、ticket及登录响应校验均成功。按当前代码，登录确认来自通过业务校验的首次keep心跳。

该现场首轮真实认证已通过，原门户XML阻断已越过；新会话样本仅约6秒，未覆盖后续定时心跳/自然掉线/计划重认证/长期资源。计划中的172800秒是估计，不是已运行时长。镜像构建身份依据本会话的更新步骤关联，日志没有记录镜像ID；不改变包摘要或扩大为跨校园通用兼容结论。详情见[现场记录](DOCKER-BETA3-PORTAL-FAILURE-2026-10-07.md)。
