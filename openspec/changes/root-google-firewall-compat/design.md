# 设计：Root 模块 OEM Google 联网兼容

## 生命周期

`service.sh` 运行的 `wanboxctl __internal boot` 是短命 CLI，不能依靠其内存中的 goroutine 延迟一分钟。`Runtime.Boot` 在 `sys.boot_completed=1` 后通过固定参数启动脱离调用者的 `wanboxctl __internal google-firewall --delay-seconds 60` 子进程；该调度独立于核心自启开关。热激活仅在 `withInstallLock` 返回成功后从新 `target` 活动目录调度立即执行。回滚/失败不调度。调度失败只记限频事件，不阻断启动或更新。

内部命令仅由既有 Root-only 授权路径执行，参数是固定命令和规范化的 0–300 秒整数，不接受路径、shell 或 App 输入。修复在等待后复核 `module.prop`、可执行 `bin/wanboxctl` 以及不存在 `disable`/`remove` 标记；每次实际规则或 map 条目删除前再复核活动状态。整体操作可取消；属性探测、包列表、策略读取和规则读取均有超时。

## 支持门禁与执行

- RedMagic、努比亚、中兴：仅识别 `ro.product.brand` 白名单。先读取所有存在的 `iptables`/`ip6tables` `filter -S` 输出；任一读取错误时两族均不删除。读取成功后只匹配 `zte_fw_gms` 专用链、`--uid-owner <单个 UID>` 且末端为 DROP 或 REJECT（可有单个 `--reject-with`），每条删除前再检查模块标记；任一删除命令失败即停止后续规则删除。按原规则参数精确 `-D`；不触碰 `fw_OUTPUT`、`fw_OUTPUT_oplus_dns`、DNS、接口绑定、共享或未知规则。
- OnePlus/OPPO：仅 ROM 属性精确为 ColorOS `V16` 或 `V16.*` 时进入专用路径。目标包为 `com.google.android.gms`、`com.android.vending`，ColorOS 另加 `com.google.android.configupdater`。UID 来自 `cmd package list packages --user 0 -U`；若相同 UID 还属于任何非白名单包，则不操作该 UID。
- ColorOS 16 还必须满足当前用户为 0、user 0 secure `google_restric_info=1`、global `oplus_user_change_gms_network_control` 与 `oplus_comm_trafficmonitor_gms_network_control` 均为 `null` 或 `0`。仅查看三个已命名 wlan/qcom/mtk pin；所有存在的 pin/program/map 都先核对 socket-filter 类型、固定 program tag、唯一关联 map ID、hash map 类型及 4 字节 key/value，任一未知结构会在全局不修改的情况下退出，明确缺失 pin 可跳过。全部预检通过后才遍历目标 UID；仅删除 value 恰为 `1` 的条目，且每项删除前复核模块标记；任一 map 修复失败立即停止后续 map，保留程序和 map。
- 任一设备标识/ROM/策略值/命令结果不明、共享 UID、未知规则/BPF 布局、权限或超时异常均跳过或返回固定错误，不猜测、不清空。非 Android arm64 构建采用空实现。

## 安全与兼容

这是一次性 best-effort 修复，不收集、存储或发送设备标识；不修改共享防火墙链、DNS、通用输出规则或 BPF 程序，不做 flush。热更新调度发生在安装锁释放后，避免子进程在临时 staging 目录上运行。修复进程不额外持有共享 `control.lock`：该锁的最长等待可达 65 秒且覆盖其他数据事务；采用每条规则/map 项删除前重读模块标记的方式限制并发风险。任何失败都不能改变已确认的启动/更新事务结果。PR-C 不增添 App UI；开关/状态/诊断入口由独立 PR-D 决定。

## 测试策略

TDD 覆盖专用链规则的正反例、IPv4/IPv6、双栈任一读取失败时零删除、DNS/共享/通用规则保留、包 UID 格式与共享 UID、固件/ColorOS 设置门禁、全部 pin 预检后再改 map、未知后续 pin 时零修改、BPF 程序和 map 逐字段不匹配、仅 value=1 可删、每项删除前模块标记复核、冷启动延迟与核心自启解耦、成功热激活锁外调度、回滚不调度、子进程启动失败不阻断，以及 CLI 延迟参数/root 授权。Go `-race` 本地门禁由用户本任务明确要求；它不替代 CI、Android 编译或 Root 真机验收。

## 回退

Revert 本 PR 并安装上一版模块可移除后续调度/修复代码。已被定点删除的厂商规则/map 条目不会由版本回退自动恢复；用户如需恢复 OEM 限制，应通过厂商策略重新应用或重启并确认其规则已重建。禁止清空共享链或 BPF map。

## 外部来源

实现对照固定到上游提交 [`0c29f0c56bdbf76f40ec3c5541840e2f3bb5507e`](https://github.com/Fanju6/NetProxy-Magisk/commit/0c29f0c56bdbf76f40ec3c5541840e2f3bb5507e)：[通用兼容入口与 iptables 规则](https://raw.githubusercontent.com/Fanju6/NetProxy-Magisk/0c29f0c56bdbf76f40ec3c5541840e2f3bb5507e/src/native/netproxy/internal/module/google_firewall.go)、[ColorOS Android BPF 路径](https://raw.githubusercontent.com/Fanju6/NetProxy-Magisk/0c29f0c56bdbf76f40ec3c5541840e2f3bb5507e/src/native/netproxy/internal/module/google_firewall_coloros_android.go)、[非目标平台空实现](https://raw.githubusercontent.com/Fanju6/NetProxy-Magisk/0c29f0c56bdbf76f40ec3c5541840e2f3bb5507e/src/native/netproxy/internal/module/google_firewall_coloros_other.go)、[包 UID 与共享 UID 处理](https://raw.githubusercontent.com/Fanju6/NetProxy-Magisk/0c29f0c56bdbf76f40ec3c5541840e2f3bb5507e/src/native/netproxy/internal/inbound/applications.go)、[规则安全测试](https://raw.githubusercontent.com/Fanju6/NetProxy-Magisk/0c29f0c56bdbf76f40ec3c5541840e2f3bb5507e/src/native/netproxy/internal/module/google_firewall_test.go)。
