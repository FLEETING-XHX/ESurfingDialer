# Docker 五项 P2 审查修复 · 2026-10-10

本轮只修改源码并执行隔离回归；现有 v1.4.0-beta.3 部署包没有更新。安装器末行换行修复属于此前已完成的 Docker-CR-07，Windows 改动保留原样。

## 修复依据与行为

| 审查项 | 原触发条件 | 源码修复与回归 |
| --- | --- | --- |
| 源码构建上下文含凭据 | 本地存在`.env`，Dockerfile的`COPY . .`会把它发送到构建端并复制进builder | `.dockerignore`排除各层`.env*`、`*.env`及常见环境配置备份，只放行根目录`.env.example`。按[Docker官方匹配规则](https://docs.docker.com/build/concepts/context/#dockerignore-files)核对`**`与末条例外的行为；未运行实际镜像构建 |
| 门户正文变化重置退避 | 同一门户仅变动nonce/时间戳，完整`PortalCandidate`比较却认定为新门户 | 退避单独比较门户URL、探测IP及解析后的有效认证端点；有界缓存解析结果按候选对象缓存，不为比较发起网络请求。无效配置正文变化保留退避，无效变有效、端点/参数/IP变化允许重试。回归覆盖指数计数及真实30秒等待：正文更新后仍等待，端点变化在下一5秒分段内结束等待；原请求合并/版本完成语义保留 |
| CLI特殊密码错误解析 | `-d`或`--dynarmic`被当作选项，实际首尾双引号被解析器剥去 | 普通及su-exec启动均用`--user=值`、`--password=值`单参数形式；只在显式后端设置时加入`-d`。捕获两分支、两后端及四种合成密码的实际argv，再用生产选项定义及Commons CLI 1.5.0解析，核对账号、密码和后端 |
| 系统时间回拨后心跳停发 | 旧心跳tick来自墙上时钟，回拨后间隔差值为负 | 周期心跳间隔及登录/尝试后的计时点均用`System.nanoTime()`，健康文件仍记录实际时间。替身时钟验证不足间隔不发送、恰到间隔发送、负值及Long回绕后的差值，并保留失败/恢复日志与健康更新回归；没有修改宿主机时间 |
| 日志进程退出后Java堵塞 | 日志维护或轮转失败，父进程仍持FIFO读端且仅等Java，输出最终填满管道 | 日志包装进程等待awk退出并通过USR1通知父进程，中断Java等待；关闭启动FIFO句柄、终止Java，五秒后仍存活则KILL，非零退出。两个Java启动分支均exec，监督PID对应实际程序。平时无需轮询；正常Java退出后先读到EOF并收尾，日志收尾五秒超时后也强制结束日志进程。覆盖维护/轮转失败、日志提前正常退出、忽略TERM的Java及入口TERM |

退出清理只在故障/停止时进行有界等待。Java宽限五秒、周期日志清理宽限两秒、日志收尾五秒；若收尾仍挂起，日志包装进程再获五秒结束宽限，其内部awk有两秒结束宽限。上述入口保护不等于已修复Java内部审查中的P1锁等待问题，后者仍需单独处理。

## 验证记录

- 工作目录：`ESurfingDialer-Docker/01_Docker源码/ESurfingDialer-Docker-Fixed`；JDK17，现有Gradle8.4，离线依赖。
- 定向`authenticationRegression --offline --console=plain`已通过324项；原`python scripts/check-logs.py`已通过18项。
- `python scripts/check-entrypoint.py`首轮与完整check均通过132项/26组隔离用例；后续另执行`--only logger-hang --report build/p2-fixes-20261010/logger-hang.json`通过8项/2组日志进程忽略TERM用例，共覆盖28组/140项入口检查。故障退出约7秒内结束，挂起的日志进程约10.4秒结束，并核对替身PID已消失。测试只启动自己的替身进程，保留私有build夹具，超时只清理自己启动的进程树；新增挂起用例也已接入默认入口回归。
- 使用HEAD旧入口执行同一日志维护失败夹具，18秒内仍未结束，触发预期失败并清理该用例进程树；修复脚本的相同用例正常非零退出。
- 完整`check --offline --console=plain`成功，耗时4分21秒：324项认证、113项运行策略、41项监督、18项日志、620项安装器、132项入口、49项真实CLI解析，合计1297项；加上随后8项日志挂起检查，共1305项不同检查。日志保存于`build/p2-fixes-20261010/check.log`，入口结构化结果保存于`build/entrypoint-regression.json`及上述挂起报告。
- `sh -n docker/entrypoint.sh`、Python语法和`git diff --check`已通过。`.dockerignore`是静态规则核对，不能将其描述为实际Docker build验证。

未启动Docker daemon、镜像或真实客户端，未使用真实凭据，未验证Alpine容器现场、arm64、真实校园网认证和长期运行。没有提交、推送、重包或发布；源码测试结果不能替代现场长期验收。

## v2.4 后续：P1 退出锁等待修复

恢复达到上限时，原代码在 `resetSessionState` 的同步锁内调用 `exitProcess`，JVM 等待同样需要该锁的 shutdown hook；如果调用来自同步的 `authorization`，还有一层锁。v2.4 抛出 `RecoveryLimitReached`，运行循环明确向上传递；线程完成 finally 状态记录后由已有 uncaughtExceptionHandler 退出，所有锁已释放。真实子 JVM 使用生产 `Client.run` / 同步认证入口，禁止网络及原生加载，验证退出码 1、hook 能完成、会话只释放一次。该项是单独修复，未以日志入口强杀替代。最终结果见[v2.4发布记录](V2.4-RELEASE.md)。此前“未重包/未提交”等描述是本报告初版快照。
