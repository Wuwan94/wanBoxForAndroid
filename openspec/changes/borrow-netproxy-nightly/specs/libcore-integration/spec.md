# Libcore Integration Specification — Delta: borrow-netproxy-nightly

本增量仅描述本次 change 新增或改变的要求；未提及的主规范要求保持不变。
N8（eBPF 入站）本轮只做可行性评估，不在本增量中立 MUST 要求。

## Requirements

### Requirement: Root module exports a redacted diagnostics package

Root 模块 MUST 提供脱敏诊断包导出：调用方指定目标路径后，模块 MUST 生成 gzip 压缩 tar，
先写同目录临时文件并 `fsync` 后原子重命名；目标 MUST 是普通文件，否则拒绝。
包内文件 MUST 按 design.md §2 的清单与脱敏规则表脱敏后写入；日志每文件取尾部，
上限 4 MiB/文件。损坏的 JSON 文件 MUST 省略原文并替换为固定 diagnostic_error 说明，
MUST NOT 把残留名单或凭据作为诊断原文导出。导出 MUST NOT 依赖核心进程运行；
导出失败 MUST 返回可读错误码，不得谎报成功。远程规则集 MUST 只导出元数据
（名称/来源/哈希/更新时间），MUST NOT 导出规则原文。
wanBox 此前没有持久化的 service/core 日志文件：supervisor MUST 把自身日志写
`logs/service.log`、把核心 stderr 写 `logs/sing-box.log`（各 capped 8 MiB、
保留尾部 4 MiB 的原地截断），诊断包的日志条目才有真实来源。
`wanboxctl diagnostics export` MUST 只接受解析后位于系统 Download 目录的目标，
stdin 传 DiagnosticsRequest JSON，成功时 stdout 为 `{"missing":[...]}`，
失败时 stdout 为 `{"error":"<stable_token>"}` 且 exit 1。

#### Scenario: Export while the core is stopped

- **GIVEN** 核心处于停止态且存在 service 日志与 Root 快照
- **WHEN** 管理端请求导出诊断包
- **THEN** 导出成功并返回目标路径
- **AND** 包内日志与配置已按脱敏规则表脱敏

#### Scenario: Export omits corrupted JSON safely

- **GIVEN** 待导出的一份 JSON 配置已损坏
- **WHEN** 执行导出
- **THEN** 包内该文件为固定 diagnostic_error 说明
- **AND** 导出仍返回成功并在 README 列出该文件已省略原文

### Requirement: Root test sessions run stopped-state node tests in an isolated box

Root 模块 MUST 为停止态节点测试提供短生命周期测试会话：`wanboxctl node urltest <id>` /
`node speedtest <id>` 经 stdin 接收测试配置与参数，调用 `rootbox --url-test` /
`--speed-test` 起独立测试 box（`NewTestSingBoxInstance`，不注册正式核心的
PlatformLogWriter，不建 `cache.db`，不经过受管入站），成功时 stdout 为结果 JSON
（URL 探测为 `{"delayMs": N}`，速度测试为 libcore `SpeedTestResult` 字段），
失败时 stdout 为 `{"error":"<stable_token>"}` 且 exit 1。测试配置 MUST 由调用方
写入 0600 临时文件并在执行后删除；URL 探测超时 MUST 在 1000–60000 毫秒之间，
速度测试超时 MUST 在 0–60000 毫秒之间（0 表示 libcore 默认 5000 毫秒）；
Root 侧执行时限 MUST 为探测超时 + 60 秒（URL）/120 秒（速度）。测试会话
MUST NOT 修改正式服务状态、当前选择或 Worker，且 MUST 只在 euid 0 下执行。

#### Scenario: Stopped URL test returns a delay

- **GIVEN** 核心处于停止态，调用方提供最小测试配置与探测 URL
- **WHEN** 执行 `node urltest <id>`
- **THEN** stdout 为 `{"delayMs": N}` 且 N 在 1–60000 之间
- **AND** 正式服务状态与当前选择不变，临时测试配置已被删除

#### Scenario: Stopped speed test returns a result

- **GIVEN** 核心处于停止态，调用方提供最小测试配置与 simple_download 参数
- **WHEN** 执行 `node speedtest <id>`
- **THEN** stdout 为 Done 的 `SpeedTestResult` JSON，含下载字节与速率
- **AND** 正式服务状态与当前选择不变

#### Scenario: Invalid test input fails closed

- **GIVEN** 调用方提供非法 profile id、非法 URL、越界超时或损坏配置
- **WHEN** 执行 `node urltest` 或 `node speedtest`
- **THEN** 命令以非零退出码失败并返回稳定错误 token，不启动测试 box

### Requirement: Root supervisor evaluates unified per-network rules from the real egress

Root supervisor MUST 在网络事件（route/link/address/nl80211）到达时评估统一按网络规则，
MUST NOT 做定时轮询。指纹 MUST 由实际默认出口（按 Android policy routing 读取）、
该出口的 station SSID、默认网关与本地地址集合组成；SSID MUST 精确保留空格/大小写/逗号；
未知网络 MUST NOT 被当作非 Wi-Fi；开机未知时 MUST 校准默认模式。评估 MUST 只产生首条匹配规则的
动作：`suspend` 时 MUST 复用 F1 状态机（优雅停核、确认核心退出并释放本实例 TUN/auto-route 后
才可报告挂起；MUST NOT 全局 flush iptables/nftables 或操作其他实例路由）；
`direct` 时 MUST 只改运行时模式并回读确认，MUST NOT 覆盖默认模式；`keep` 时 MUST 不改变运行状态。
指纹读取失败或 SSID 不可读时 MUST 保持当前状态并记录，MUST NOT 猜测切换。
广播与日志 MUST NOT 包含 SSID、接口名、地址、网关或规则文本；phase event 仍只用固定值
`suspended`、`connected`、`stopped`、`failed`、`disabled`。

#### Scenario: Real egress decides the SSID, not a guessed one

- **GIVEN** 当前默认出口为 Wi-Fi 接口且其 station SSID 为 `Office`
- **WHEN** Root supervisor 评估按网络规则
- **THEN** 以 `Office` 参与匹配，不以其他接口或缓存 SSID 参与匹配
- **AND** 评估结果只产生首条匹配规则的动作

#### Scenario: Evaluation failure keeps the current state

- **GIVEN** 当前默认出口不可读
- **WHEN** 网络评估触发
- **THEN** 不切换模式、不挂起核心
- **AND** 失败被记录用于诊断

### Requirement: Root eBPF shared path intercepts tethered traffic only when explicitly enabled

Root eBPF 入站 MUST 支持与本机路径独立启用的共享路径：`shared.enabled` 默认 `false`；
启用时 `shared.interface` MUST 含至少一个真实下游接口（不能为 loopback），
`data_plane` MUST 为 `socket_assign` 或 `packet_rewrite`，`dns_mode` 默认 `hijack`，
`default_interface` 默认 `false`。共享路径启用前 MUST 通过 eBPF 能力预检；
预检失败 MUST 明确报错，MUST NOT 自动切换数据平面。`default_interface=false` 时，
已配置的共享接口成为默认上游后 MUST 暂时停止共享挂载，恢复为下游后重新挂载。
模块 MUST NOT 创建热点、DHCP、NAT、IPv6 RA 或 IP 转发。共享路径 MUST 支持
`include_source_cidr` / `exclude_source_cidr` / `include_mac_address` / `exclude_mac_address`
筛选，排除优先。本机分应用名单 MUST NOT 用来识别热点客户端。
TUN 回退 MUST NOT 为共享建立映射；eBPF 共享失败时 MUST 按现有回退表处理，
MUST NOT 静默回退到未配置共享的 TUN。

#### Scenario: Shared path starts only with a real downstream interface

- **GIVEN** 共享路径已启用且 `shared.interface` 含一个真实热点接口
- **WHEN** 核心启动
- **THEN** 共享挂载建立，下游客户端流量进入 sing-box
- **AND** 本机流量仍由本机路径控制

#### Scenario: Shared interface becomes the default upstream

- **GIVEN** 共享已启用且 `default_interface` 为 `false`
- **WHEN** 已配置的共享接口成为默认上游
- **THEN** 共享挂载暂时停止
- **AND** 该接口恢复为下游后共享挂载重新建立

### Requirement: Root resolves per-app lists per Android user

Root 模块 MUST 接受 `<userId>:<package>` 形式的黑/白名单；解析时 MUST 按用户查询
Android package service 得到 UID，再合并为受管入站的 `include_uid`/`exclude_uid`。
MUST NOT 把包名直接交给 sing-box，MUST NOT 跨用户合并 UID。白名单解析后与原生 include
UID/range 均为空时，运行时 MUST 设置 `exclude_uid_range=["0:4294967294"]`，
MUST NOT 生成空 include 后静默变为全量接管。应用安装、重装、UID 变化或用户范围变化后，
MUST 通过重启或配置 reload 重新解析，MUST NOT 维护模块侧 UID 缓存。仅 eBPF 共享路径时
MUST NOT 查询应用 UID。UID 0 MUST NOT 被自动加入名单；Root UID 策略与应用名单正交。

#### Scenario: Same package under two users resolves to two UIDs

- **GIVEN** 名单含 `0:com.example.app` 与 `10:com.example.app`
- **WHEN** Root 生成受管入站配置
- **THEN** `include_uid`/`exclude_uid` 包含两个用户各自查询到的 UID
- **AND** 两者不被合并为一个 UID

#### Scenario: Empty whitelist resolves to exclude-all range

- **GIVEN** 白名单模式开启但名单为空，且原生 include UID/range 均为空
- **WHEN** Root 生成运行时入站配置
- **THEN** 配置含 `exclude_uid_range=["0:4294967294"]`
- **AND** 不含空 `include_uid`

### Requirement: Hot-update contract tests cover installer behaviour without changing the implementation

Root 热更新 MUST 有契约测试覆盖安装器行为，且 B8 只补测试、MUST NOT 改热更新实现。
测试 MUST 在临时目录搭建 live（当前模块）与 staging（待安装包）两套完整 fixture，
内容全部虚构；被测函数 MUST 直接取自生产代码，外部依赖 MUST 用桩替换。
测试 MUST 覆盖三种安装模式（保留全数据 / 仅节点与订阅 / 全新）提交前后的精确快照内容、
热切换互斥（锁顺序与锁文件 inode 保留）、以及快照失败、重命名失败、服务停止失败、
配置校验失败、canary 失败时的失败恢复（旧模块与用户数据原样保留、失败事件已记录、
可由下次开机流程继续处理）。另 MUST 有一层薄 shell 测试覆盖
`rootmodule/package/customize.sh` 与 `installer-options.sh` 的委托语义
（校验失败→abort、不替换当前模块；`schedule-install` 失败→abort）。
测试暴露实现缺陷时 MUST 单独上报，MUST NOT 在同批次内擅自改实现。

#### Scenario: Snapshot failure keeps the old module

- **GIVEN** staging 包已准备且 live 模块正在运行
- **WHEN** 快照步骤失败
- **THEN** live 模块与用户数据原样保留
- **AND** 失败事件已记录，安装包保留待下次开机处理

#### Scenario: Canary failure rolls back to the old core

- **GIVEN** 新核心在 5 秒健康观察期内失败
- **WHEN** 热更新提交
- **THEN** 旧核心被恢复并重启
- **AND** 事件记录回滚原因

#### Scenario: Non-preserve modes journal intent without touching the snapshot

- **GIVEN** live 模块正在运行且用户选择仅保留节点与订阅或全新安装
- **WHEN** 热更新提交
- **THEN** 用户快照原样保留，核心不重启
- **AND** `install-data.json` 已记入该模式与合法选择 ID
- **AND** 再次提交前应用侧写入被挂起，直到该选择被确认

#### Scenario: Incomplete installer handoff defers to the next boot

- **GIVEN** 安装器尚未退出或未写最终标记
- **WHEN** 后台完成任务尝试接管
- **THEN** 接管拒绝，旧模块与 staging 包原样保留
- **AND** 事件记录 `module_hot_update_deferred`
