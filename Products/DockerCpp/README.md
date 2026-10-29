# Docker原生C++核心 v3.0.0（实验版）

本地离线包输出到`artifacts/packages/DockerCpp/ESurfingDialer-DockerCpp-v3.0.0-experimental.zip`。包含amd64、arm64和ARMv7镜像，按架构选择并本地导入；软路由需预装Docker及Compose。安装只创建容器，默认`NATIVE_EXPERIMENTAL=0`，测试时手动配置和启用。不需要Java；认证仍需访问校园网服务。

本次版本使用CMake的PROJECT_VERSION生成程序`--version`，镜像标签及离线包均为v3.0.0。打包入口为`scripts/package-experimental.py`，要求先完成三架构Dockerfile内回归和`scripts/Test-NativeImage.ps1`隔离生命周期检查，不以旧镜像简单改名。`scripts/check-experimental-package.py`检查离线脚本、架构选择、旧容器互斥及配置保留；实际Docker导入和镜像层/配置核对由打包入口执行。

新核心与现有Kotlin工程分别保留；生产Compose未切换。本目录不是已经通过校园网验收的发行版。阶段与下一步见Docs/DockerCpp/DOCKER-CPP-MIGRATION.md。

已实现：C++20状态机、有限恢复/单调计时、外网与本会话分离、目录独占、schema3原子健康上报；95分支的独立参考C算法适配及严格输入边界；libcurl有界传输/取消与手动重定向，libxml2严格协议/门户解析，动态端点，ticket/login/首次keep/后续keep/term请求；旧设备身份迁移、UTC+8周期学习计划；独立网络探测线程，避免慢探测阻塞心跳；登录后门户宽限、同候选累计、心跳佐证与请求冷却；独立实验容器配置。

未知/待办：实际Docker Android32/2082通道模块的aid/算法等价与真实认证；换网、路由器监控现场兼容、资源峰值与长期运行。现已实现旧LOG_*文件归档/保留和独立schema3监控脚本；验证范围见COMPATIBILITY.md。新状态目录不可直接覆盖旧data；schema3不能套用旧schema2脚本。生命周期、健康与计划的离线回归通过不等于完成这些现场验收。兼容细节见[COMPATIBILITY.md](COMPATIBILITY.md)。

## 离线验证

Linux依赖CMake、C++20编译器、libcurl>=7.85、libxml2，时间ABI必须至少64位；32位glibc构建显式启用_FILE_OFFSET_BITS=64和_TIME_BITS=64。算法自身无需JVM/Unidbg/OpenSSL。构建测试：

```sh
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build build
ctest --test-dir build --output-on-failure
```

Windows可用MSVC跑状态/身份/计划/算法测试（默认NATIVE_NETWORK=OFF），不等于Linux容器验证。测试镜像只用于编译与合成数据回归：

```sh
docker build -f Dockerfile.test -t esurfing-native-test:local .
docker run --rm --network none esurfing-native-test:local
docker run --rm --network none esurfing-native-test:local sh tests/runtime-smoke.sh
```

算法用例是参考库自身往返、修改前参考输出及边界，不是与现有ARM32原生库的逐字节等价。runtime-integration会启动独立测试可执行文件，连接本机合成服务器，实际检查完整请求链、探测阻塞期间心跳、计划写入、身份、日志隐私和SIGTERM。该测试可执行文件允许回环地址，生产可执行文件仍拒绝回环，Docker运行镜像只复制生产可执行文件。模拟认证和loopback HTTP不证明真实校园网支持。

## 实验容器

Dockerfile是真正原生多阶段构建，运行入口为C++进程，没有Java。compose.native.yml采用独立服务名和卷，默认NATIVE_EXPERIMENTAL=0会以78退出，避免把未验证核心当生产替换。只有按后续现场计划显式设为1后才会读取账户并探测网络；本轮没有执行此真实认证路径。不要复用正在运行的旧data目录。

运行时支持DIALER_USER、DIALER_PASSWORD或DIALER_PASSWORD_FILE、STATE_DIR、NETWORK_CHECK_URLS、TZ、NATIVE_EXPERIMENTAL、DIALER_MAC_ADDRESS、DIALER_CLIENT_ID、AUTO_REAUTH_ENABLED/SAFE_WINDOW_START_HOUR/SAFE_WINDOW_END_HOUR，及config.cpp列出的探测/心跳/重试/确认/健康间隔配置。周期学习默认开启，窗口固定UTC+8，和日志TZ分开；没有新增每天重启。密码不放命令行，不打印HTTP正文/明密文/密钥/完整URL。退出时取消旧工作，并用独立3秒传输尝试term；TERM_REQUEST_SENT_UNCONFIRMED不表示服务端注销已确认。

第三方算法来源及原始许可证见third_party/NOTICE.md、ciphers/LICENSE、provenance.json。没有引入参考项目Web/监管逻辑，不能把参考项目的平台列表当本核心已验证平台。

## 日志与可选监控

LOG_DIR默认STATE_DIR/logs，按日志TZ生成dialer-YYYY-MM-DD.log，支持LOG_FILE_MAX_BYTES（默认10MiB，1KiB–100MiB）、LOG_TOTAL_MAX_BYTES（默认50MiB，至少单文件上限、最多1GiB）、LOG_ROTATED_FILES（默认2，1–5）和LOG_RETENTION_DAYS（默认3，0–36500）。沿用旧find -mtime +N的整天语义：3在经过四个24小时后到期，并非严格72小时。启动、换日、轮转及每小时维护；总量先给当前文件预留一个完整上限。只管理本目录精确日期日志/轮转名，保留其他文件；目录必须专用、可信且当前UID可写，不与Kotlin日志共享。

C++进程保持PID1。日志线程使用最多128条记录的队列，认证/心跳不执行文件或stdout I/O。队列溢出、写入失败或stdout管道堵塞超过2秒触发失败退出；已接收记录在正常停止时排空。磁盘内核I/O若不可中断，C++线程无法保证自行打断，容器仍依赖stop_grace_period最终终止；没有后台子进程遗留。文件日志和stdout仅记录固定事件/错误码及数字，首次keep有摘要，稳定成功每30分钟一次、失败后恢复一次，不记录敏感协议内容。旧Java DEBUG逐请求输出与凭据CLI不迁移。

scripts/openwrt-watchdog.sh是宿主机可选脚本，默认EXPECTED_RUNNING=0，不安装服务或定时任务。只有操作者明确开启、容器运行且restart策略为unless-stopped、镜像标签为org.esurfing.implementation=cpp时才监督；本目录实验Compose仍为restart:no，所以不会被它自动回收。健康检查在容器内运行jq，宿主需Docker CLI、timeout及常用POSIX工具，无需宿主jq。监控连续失败默认三次、冷却300秒，通过同UID发送TERM给PID1，交给Docker已有策略处理；不调用docker start/restart/stop。不因外网已通但没有本会话、认证paused、断网但报告新鲜而回收，尊重手动停容器、Docker暂停及PAUSE_FILE。容器ID/启动时间/PID启动标识/实例标识防止对替换实例误操作。此脚本尚未在实体OpenWrt上安装或验证。
