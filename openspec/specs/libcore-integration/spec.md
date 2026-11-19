# Libcore Integration Specification

## Purpose

定义 ThroneForAndroid 与 NetProxy 所用 reF1nd sing-box 内核之间的 Go/JNI 接驳、eBPF/TUN 网络生命周期、日志、规则集和协议扩展约束。

> **验证状态标记**：条目末尾的 `> 验证：` 行说明该条目的验证状态。「CI 通过」指该条目所属批次的分支验证（port #190 / feat #194）与 main `Verify Android Build` #197 第 2 次尝试全绿，run 号见标记正文；「待真机验证」指 Magisk / KernelSU / APatch 真机矩阵未执行。未标注的条目为历史基线或待拍板项，不代表已验证。

## Requirements

### Requirement: libcore 使用固定且可验证的 NetProxy sing-box 基线

libcore MUST 使用 NetProxy 所用的 `reF1nd/sing-box` tag `v1.15.0-alpha.13-reF1nd`，并验证其 commit 为 `17397d022647b73378705e1f91ff031dddaaec21`。`nb4a.properties` 的 `SINGBOX_VERSION` MUST 是唯一版本来源；源码获取脚本 MUST 固定到该仓库并校验 remote 与 tag commit。依赖 MUST NOT 混用官方 alpha 标签和 fork API。

#### Scenario: 获取内核源码

- **GIVEN** CI 开始构建 libcore
- **WHEN** 源码获取脚本处理 `SINGBOX_VERSION`
- **THEN** remote 为 `https://github.com/reF1nd/sing-box.git`
- **AND** HEAD 与指定 tag 的 commit 完全一致
- **AND** 缓存键及版本注入使用同一个 `SINGBOX_VERSION`

### Requirement: eBPF 接管与 TUN 回退保持明确边界

Root 模块 MAY 启用 fork 提供的 Android eBPF inbound；启用前 MUST 完成权限、内核能力、挂载点和路由冲突预检。预检或启动失败 MUST 回滚本次启动新建的挂载与路由，并保留可诊断原因；只有显式配置的安全 TUN 路径可作为回退，不能把未挂载的 eBPF 状态报告为接管成功。管理应用 MUST 只通过受限 Root IPC 请求状态或操作，不得直接管理底层代理生命周期。

#### Scenario: eBPF 启动失败

- **GIVEN** 目标设备缺少所需 eBPF/cgroup 能力或 attach 失败
- **WHEN** Root 模块启动代理
- **THEN** 清理由本次启动创建的部分资源
- **AND** 使用明确启用的 TUN 回退或保持停止
- **AND** 状态报告区分预检、启动和实际接管


### Requirement: Root eBPF shared path intercepts tethered traffic only when explicitly enabled

Root eBPF 入站 MUST 支持与本机路径独立启用的共享路径：`shared.enabled` 默认 `false`；启用时 `shared.interface` MUST 含至少一个真实下游接口（不能为 loopback），`data_plane` MUST 为 `socket_assign` 或 `packet_rewrite`，`dns_mode` 默认 `hijack`，`default_interface` 默认 `false`。共享路径启用前 MUST 通过 eBPF 能力预检；预检失败 MUST 明确报错，MUST NOT 自动切换数据平面，supervise 前预检失败 MUST Fail-Closed（状态置 failed 并拒绝启动）。`default_interface=false` 时，已配置的共享接口成为默认上游后 MUST 暂时停止共享挂载，恢复为下游后重新挂载；attachment 的实际建立/拆除由 sing-box 管理，Go 侧只做可测试裁决与诊断。模块 MUST NOT 创建热点、DHCP、NAT、IPv6 RA 或 IP 转发。共享路径 MUST 支持 `include_source_cidr` / `exclude_source_cidr` / `include_mac_address` / `exclude_mac_address` 筛选，排除优先。本机分应用名单 MUST NOT 用来识别热点客户端。TUN 回退 MUST NOT 为共享建立映射；eBPF 共享失败时 MUST 按现有回退表处理，MUST NOT 静默回退到未配置共享的 TUN。

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

> 验证：CI 通过（feat 分支 Verify Android Build #194 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: Root resolves per-app lists per Android user

Root 模块 MUST 接受 `<userId>:<package>` 形式的黑/白名单；解析时 MUST 按用户查询 Android package service 得到 UID，再合并为受管入站的 `include_uid`/`exclude_uid`。MUST NOT 把包名直接交给 sing-box，MUST NOT 跨用户合并 UID。白名单解析后与原生 include UID/range 均为空时，运行时 MUST 设置 `exclude_uid_range=["0:4294967294"]`，MUST NOT 生成空 include 后静默变为全量接管。应用安装、重装、UID 变化或用户范围变化后，MUST 通过重启或配置 reload 重新解析，MUST NOT 维护模块侧 UID 缓存。仅 eBPF 共享路径时 MUST NOT 查询应用 UID。UID 0 MUST NOT 被自动加入名单；Root UID 策略与应用名单正交。

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

> 验证：CI 通过（feat 分支 Verify Android Build #194 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: Root 模块导出脱敏诊断包

Root 模块 MUST 提供脱敏诊断包导出：调用方指定目标路径后，模块 MUST 生成 gzip 压缩 tar，
先写同目录临时文件并 `fsync` 后原子重命名；目标 MUST 是普通文件，否则拒绝。
包内文件 MUST 按 change 的 design.md §2 的清单与脱敏规则表脱敏后写入；日志每文件取尾部，
上限 4 MiB/文件。损坏的 JSON 文件 MUST 省略原文并替换为固定 diagnostic_error 说明，
MUST NOT 把残留名单或凭据作为诊断原文导出。导出 MUST NOT 依赖核心进程运行；
导出失败 MUST 返回可读错误码，不得谎报成功。远程规则集 MUST 只导出元数据
（名称/来源/哈希/更新时间），MUST NOT 导出规则原文。wanBox 此前没有持久化的
service/core 日志文件：supervisor MUST 把自身日志写 `logs/service.log`、把核心 stderr
写 `logs/sing-box.log`（各 capped 8 MiB、保留尾部 4 MiB 的原地截断），诊断包的日志条目
才有真实来源。`wanboxctl diagnostics export` MUST 只接受解析后位于系统 Download 目录的目标，
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

> 验证：CI 通过（feat 分支 Verify Android Build #194 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

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

> 验证：CI 通过（feat 分支 Verify Android Build #194 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 平台接口按 box 实例隔离

每个 sing-box 实例 MUST 创建独立的 `boxPlatformInterfaceWrapper`，其 NetworkManager、TUN 名称和接口状态 MUST NOT 在并发实例之间共享。平台实现 MUST 提供 TUN 打开、fd protect、连接属主查询、网络接口枚举和默认接口监视器，以满足官方拨号路径。

#### Scenario: 正式连接与测试实例并存

- **GIVEN** 主服务运行且多个测试 box 并发启动
- **WHEN** 各 box 初始化或收到默认接口更新
- **THEN** 更新作用于该 box 自己的 NetworkManager
- **AND** 各 box 都能选择可用物理接口

### Requirement: box 启动失败后的关闭必须安全且幂等

libcore box 生命周期 MUST 区分未启动、启动中或部分失败、已启动和已关闭状态。启动失败后调用关闭 MUST 能安全释放尚存资源；同一实例的后续关闭 MUST 保持幂等。若底层启动回滚已关闭资源，libcore MUST NOT 以等价于“已关闭”的返回覆盖原始启动错误或使 JNI 调用方发生未处理异常；其他非预期关闭错误 MUST 保留诊断并返回给受控错误边界。

#### Scenario: 部分启动失败后首次关闭

- **GIVEN** 底层 box 启动时因 outbound 依赖缺失失败，并在内部回滚中关闭资源
- **WHEN** Android 通过 JNI 请求关闭该 box
- **THEN** libcore 将实例标记为已关闭并完成剩余清理
- **AND** 等价于资源已关闭的结果不覆盖原始启动错误
- **AND** 调用方不会因该关闭结果产生未处理异常

#### Scenario: 已关闭实例再次关闭

- **GIVEN** box 已完成正常关闭或失败回滚清理
- **WHEN** 调用方再次请求关闭同一实例
- **THEN** 关闭操作成功返回且不重复释放底层资源
- **AND** 生命周期日志能够区分首次关闭和幂等跳过

### Requirement: 默认网络变化遵循官方重置语义

默认接口监视器 MUST 在接口 name/index 变化时通知官方 NetworkManager，并在解析失败时保留旧接口。Android OEM 接口查找失败时 MAY 依次回退到 NetworkManager 缓存、系统接口和 Kotlin 上报的信息。`networkChangeResetConnections=false` 时普通变化 MUST 只更新默认接口而不触发 ResetNetwork；从无网络恢复时仍 MUST 通知 NetworkWake。正常切网 MUST NOT 由 Kotlin 层额外重复重置。

#### Scenario: Wi-Fi 与移动网络切换

- **GIVEN** 默认物理接口发生变化
- **WHEN** 平台监听器报告新的 name/index
- **THEN** 接口缓存先刷新
- **AND** 开启网络变化重置时仅触发一次官方 ResetNetwork 路径
- **AND** 禁用该设置时保留连接且更新默认接口

### Requirement: fd protect 失败不得静默回环

主进程 fd protect MUST 经 Unix socket 转发到 VPN 后台进程。仅 socket 不存在或拒绝连接（表示 VPN 未运行）时 MAY 放行直连；超时、协议错误或 protect 返回失败 MUST 作为拨号错误返回。服务端 MUST 并发处理 fd 请求并隔离 panic。

#### Scenario: protect 确认超时

- **GIVEN** VPN 正在运行但 protect socket 未及时确认
- **WHEN** 测速或正式连接拨号
- **THEN** 拨号失败并返回可诊断错误
- **AND** 未 protect 的 fd 不会进入 TUN 形成代理回环

### Requirement: 日志按来源执行一致的级别门控

sing-box PlatformWriter 消息 MUST 在 Go 平台 writer 中按配置级别过滤；Kotlin `Logs` MUST 在 JNI 调用前过滤；插件进程 MUST 通过启动参数接收等价级别。日志 MAY 包含脱敏的 outbound 链拓扑，但 MUST NOT 包含 UUID、密码、Reality 公钥或其他认证材料。

#### Scenario: info 级日志运行

- **GIVEN** 用户设置日志级别为 info
- **WHEN** 内核、Kotlin 和插件产生 debug 消息
- **THEN** debug 消息不会写入日志文件
- **AND** error/warning/info 仍按各通道规则保留

### Requirement: geo 规则兼容新旧命名

规则生成 MUST 同时接受 `geoip-cn`/`geosite-cn` 与旧 `geoip:cn`/`geosite:cn`。官方命名 SHOULD 优先使用已有 `.srs`；旧命名或缺失的官方 `.srs` MUST 可从本地 geo 数据库转换并缓存。`geoip-private` 与 `geoip:private` MUST 映射为 `ip_is_private`。

#### Scenario: 加载旧版 geo 规则

- **GIVEN** 用户规则包含 `geosite:cn`
- **WHEN** libcore 在 `box.New` 前预处理配置
- **THEN** 规则被转换为可用的本地 rule-set
- **AND** 生成缓存可在源数据库未变化时复用

### Requirement: 自定义协议扩展使用固定内核 registry/API

Juicity outbound 与覆盖版 HTTP outbound MAY 由 libcore 自定义注册，但 MUST 基于固定 reF1nd fork registry/API。HTTP TLS 在用户未显式配置 ALPN 时 MUST 提供 `h2` 与 `http/1.1`，并按协商结果使用 HTTP/2 CONNECT 或 HTTP/1.1 CONNECT。

#### Scenario: 连接 h2-only HTTPS 代理

- **GIVEN** HTTPS 代理仅接受 HTTP/2 且用户未显式指定 ALPN
- **WHEN** HTTP outbound 完成 TLS 握手
- **THEN** ALPN 可协商为 `h2`
- **AND** outbound 使用 HTTP/2 CONNECT 建立代理流

### Requirement: 已知官方内核降级必须显式保留

SSR/Snell、WireGuard outbound 以及 Clash API selector 回调等尚未完成的兼容项 MUST 在主规范中保持可见，且 MUST NOT 被描述为已支持。WireGuard 在完成 outbound 到 endpoint 的配置迁移前 MUST 被视为已知不兼容项。

#### Scenario: 评估 WireGuard 节点

- **GIVEN** 当前官方内核只支持 WireGuard endpoint
- **WHEN** Android 仍生成 WireGuard outbound
- **THEN** 项目文档将其标记为已知降级
- **AND** 不以静默兼容或完整支持对外承诺

### Requirement: libcore 提供节点隔离的上下行速度测试

libcore MUST 提供可由 Android 调用的速度测试接口，经指定节点的 outbound 执行下载和上传测试，并返回可区分节点的下载速度、上传速度、传输字节、延迟、服务端信息、错误与取消状态。测速 MUST 遵守调用方提供的模式、超时和 URL 设置，MUST 支持取消，且 MUST NOT 绕过指定 outbound 或与其他测试实例共享可变网络状态。

#### Scenario: 下载上传完整测速

- **GIVEN** Android 为一个或多个节点请求下载+上传测速并提供有效配置
- **WHEN** libcore 启动速度测试
- **THEN** 每个节点的下载与上传流量均经该节点 outbound
- **AND** 返回结果包含下载和上传吞吐量、字节数、延迟及服务端信息

#### Scenario: 测速被取消

- **GIVEN** 一批速度测试正在执行
- **WHEN** Android 发出取消请求或测试上下文结束
- **THEN** libcore 停止尚未完成的网络操作
- **AND** 返回或发布可识别的取消状态，而不是把取消报告为成功

#### Scenario: 自定义速度测试 URL

- **GIVEN** Android 提供了用户自定义的服务发现、下载、上传或简单下载 URL
- **WHEN** libcore 执行对应速度测试阶段
- **THEN** 网络请求使用已提供的 URL
- **AND** 未提供自定义值时使用与选定 Throne 参考版本一致的默认值

### Requirement: Root 测试会话为停止态测速提供独立 box

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

> 验证：CI 通过（feat 分支 Verify Android Build #194 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: Root 网络自动暂停与恢复不得泄漏内核自有路由

Root 模块 MUST 接收统一按网络规则表（最多五条规则，每条 = 匹配器 + 动作），并在不使用定位数据的情况下匹配当前物理网络。匹配器可组合 SSID（精确保留空格/大小写/逗号）、`gateway:` 默认网关、CIDR/精确本地地址与实际默认出口类型（`wifi` / `not_wifi`）；规则间按用户顺序首条胜出，无匹配时保持默认行为。`suspend` 动作命中时，supervisor MUST 保留用户运行意图，仅请求当前内核进程优雅退出；内核 MUST 关闭自身 sing-box 实例、TUN 和 `auto_route` 资源，确认进程退出后模块才可报告 `suspended`。`direct` 动作命中时 MUST 只改运行时模式并经 Clash API 回读确认，MUST NOT 覆盖默认模式，参数不变时 MUST NOT 重载；API 失败 MUST NOT 重载兜底。`keep` 动作命中时 MUST 不改变运行状态。模块 MUST NOT 全局清空 iptables/nftables 或修改非本内核实例拥有的路由。离开匹配网络后，模块 MUST 恢复原运行意图，最多启动一个替代内核。同一网络指纹上的手动恢复 MUST 抑制再次自动暂停，直到物理网络指纹变化；显式手动停止 MUST NOT 被自动恢复撤销。清理超时 MUST 报告失败，不得声称暂停成功或启动重复内核，并 MUST 允许重试显式停止而不影响其他进程或路由。指纹 MUST 由实际默认出口（按 Android policy routing 读取）、该出口的 station SSID（`iw` 读取）、默认网关与本地地址集合组成；未知网络 MUST NOT 被当作非 Wi-Fi；开机未知时 MUST 校准默认模式；指纹读取失败或 SSID 不可读时 MUST 保持当前状态并记录，MUST NOT 猜测切换；Wi-Fi 出口上 SSID 读取失败时，同一评估内 `gateway:`/CIDR 规则也不触发。评估观察沿用既有 5 秒 health tick，动作只在指纹或动作变化时执行，不得做定时轮询式的重复动作。

快照文件 `files/network_rules.json` MUST 包含开关与规则表；启用时 MUST 包含经过验证的应用包名，模块 MUST 在应用快照前拒绝无效包名。旧快照的 `files/smart_auto_stop.json` 在新表缺失时仍可回退读取并迁移（F1 规则转为 `suspend` 动作、原顺序保留）；新表损坏时 MUST 报错，MUST NOT 静默回退旧表；迁移失败 MUST 保留原数据。固定生命周期转换 MUST 仅通过显式 Android 广播通知该包名，action MUST 为 `<appPackage>.AUTO_STOP_STATE`，唯一 phase extra MUST 为 `suspended`、`connected`、`stopped`、`failed` 或 `disabled`。广播和日志 MUST NOT 包含接口名、地址、网关、SSID 或规则文本；模块 MUST NOT 将快照文本传入 shell。

IPv4 与 IPv6 默认网关发现 MUST 通过固定参数、短超时的系统 `ip route show table all` 接口读取策略路由表，并保留 `/proc` 回退；`ip` 不可用时 MUST 安全继续。仅带有有效 `via` IP 和 `dev` 接口的默认路由可被解析，网关 MUST 按接口关联以保留忽略接口过滤。启动请求 MUST 将同一 RunningRevision 的 `suspended` 视为保留运行意图的成功确认，但 MUST NOT 将其记录为 `core_started`；不同修订或其他未成功状态 MUST NOT 被误认为启动成功。

位于 active、non-ignored physical interface 的 IPv4/IPv6 link-local unicast 地址 MUST NOT 仅因 link-local scope 被过滤；这些地址 MUST 可由精确 IP 和 CIDR 规则匹配，该接口关联的 link-local 默认网关 MUST 可由 `gateway:` 规则匹配。

#### Scenario: Link-local unicast participates in physical network matching

- **GIVEN** active、non-ignored physical interface 上存在 IPv4/IPv6 link-local unicast 地址，且默认网关通过该接口关联
- **WHEN** Root supervisor 分别评估匹配该地址的精确 IP、CIDR 规则以及匹配该接口 link-local 默认网关的 `gateway:` 规则
- **THEN** 每种规则均可命中，不会仅因 link-local scope 过滤地址或网关
- **AND** ignored-interface 过滤仍然生效

#### Scenario: 当前网关命中网络规则

- **GIVEN** 有效持久化 `gateway:` 规则命中活动物理网络
- **WHEN** Root supervisor 观察当前网络指纹
- **THEN** 仅请求当前内核优雅退出，并在进程退出后转换为 `suspended`
- **AND** 状态仅暴露固定自动暂停原因，不泄漏本地 IP、网关或命中规则文本

#### Scenario: 离开匹配网络

- **GIVEN** 内核自动暂停且其 TUN/`auto_route` 资源已释放
- **WHEN** 新物理网络不再匹配任何规则
- **THEN** supervisor 恢复原运行意图并为当前修订启动一个内核
- **AND** 无关系统路由与 iptables/nftables 规则保持不变

#### Scenario: 手动恢复抑制重复暂停

- **GIVEN** 用户在网络指纹仍命中规则时手动恢复内核
- **WHEN** 同一指纹再次进行周期网络评估
- **THEN** 内核继续运行且不再次自动暂停
- **AND** 物理网络指纹变化后清除抑制

#### Scenario: Manual suppression survives configuration reload

- **GIVEN** manual resume suppressed automatic suspension on the current physical fingerprint
- **WHEN** config Apply replaces the supervisor
- **THEN** the replacement restores the suppression from a bounded private runtime marker and remains running on that fingerprint
- **AND** observing a different physical fingerprint clears the marker through the same decision used by active and suspended monitoring

#### Scenario: Abnormal auto-stop exit fails closed

- **GIVEN** automatic suspension requested graceful core cleanup
- **WHEN** the core exits abnormally or its sing-box instance Close fails
- **THEN** the supervisor reports failed with cleanup_required, never suspended, and does not automatically start another core
- **AND** the standalone core returns a nonzero result with fixed safe output, retaining close diagnostics only at the controlled error boundary
- **AND** explicit stop remains safe to retry

#### Scenario: 内核清理超时可见且可重试

- **GIVEN** 内核未在优雅退出期限内退出
- **WHEN** Root supervisor 处理清理超时
- **THEN** 状态包含失败且不声称暂停成功或启动重复内核
- **AND** 调用方可重试显式停止而不影响其他进程或路由

#### Scenario: Root 仅向配置应用发布固定生命周期阶段

- **GIVEN** 快照包含有效应用包名且自动暂停生命周期阶段变化
- **WHEN** 模块报告 `suspended`、`connected`、`stopped`、`failed` 或 `disabled`
- **THEN** 仅向配置应用发送 action 为 `<appPackage>.AUTO_STOP_STATE` 的显式广播及对应固定 phase
- **AND** 广播不包含物理网络标识、命中规则文本或内核错误输出

#### Scenario: 启动后立即命中自动暂停规则

- **GIVEN** 启动请求对应的修订在完成内核清理后报告 `suspended`
- **WHEN** 启动调用方轮询到相同 RunningRevision 的确认状态
- **THEN** 启动请求成功并保留用户运行意图
- **AND** 不记录 `core_started`，也不接受不同修订的暂停状态

Broadcast delivery MUST be independent of the supervisor lifecycle: enqueueing MUST NOT wait for Android `am broadcast`. A detached dispatcher MUST serialize/coalesce pending phases across supervisor replacements, bound every command with a short timeout, and prevent a stale `suspended` notification from being delivered after a newer `connected`, `stopped`, `failed`, or `disabled` phase. Arguments MUST be passed as argv, never shell-interpolated.

Enabled `files/network_rules.json` MUST include `userId`, the current Android user/profile identifier, as an integer in the supported Android UID range (0–21474), as well as `appPackage`. Missing, null, fractional, negative, or out-of-range identities MUST be rejected before Apply; no enabled snapshot may silently default to user 0. Disabled/legacy snapshots MAY omit user identity; without one the module MUST NOT dispatch notifications. `am broadcast --user` MUST use the validated identifier as a separate argv value. Legacy `files/smart_auto_stop.json` snapshots follow the same identity rule.

#### Scenario: Apply preserves the cleanup failure barrier

- **GIVEN** a newly applied generation fails startup with `cleanup_required`
- **WHEN** Apply considers automatic rollback
- **THEN** it preserves and returns `cleanup_required` without automatically stopping away the failure or restarting the old generation
- **AND** subsequent Start remains blocked until explicit Stop recovery

#### Scenario: Slow lifecycle delivery does not block networking

- **GIVEN** Android broadcast delivery stalls during suspension
- **WHEN** the supervisor transitions to connected or stopped
- **THEN** the transition proceeds without waiting for delivery and the stalled command times out
- **AND** serialized/coalesced delivery leaves the latest phase, never a stale suspended notification

#### Scenario: Notifications target a work profile

- **GIVEN** an enabled snapshot contains a validated nonzero Android `userId`
- **WHEN** a lifecycle notification is dispatched
- **THEN** the explicit broadcast targets that user/profile and application package only
- **AND** an enabled snapshot without user identity is rejected while disabled legacy snapshots remain compatible

> 验证：CI 通过（feat 分支 Verify Android Build #194 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: DNS ExchangeContext.Success 必须发送完成信号（B1 已落地）

`ExchangeContext.Success()` MUST 在写入解析地址后调用 `c.done()`，与 `RawSuccess`、
`ErrorCode`、`ErrnoCode` 的行为一致；DNS 解析流程 MUST NOT 因缺少完成信号而挂起直至超时。

- **GIVEN** 平台 DNS 回调走 `Success(result)` 返回地址列表
- **WHEN** `Success` 返回
- **THEN** 等待该次解析的调用方收到完成信号并继续，不再挂起至超时

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 探测失败惩罚写入续期时间戳，惩罚必须饱和封顶（B1 已落地）

`URLTest` 批量探测失败时，对已有历史的惩罚写入 MUST 续期 `Time` 为当前时间
（wanBox 既有测试 `TestMemberTimeoutStillPenalizesRecentHistory` 明确要求
`got.Time.After(before.Time)`；与上游 8.13“保持旧 Time”不同，按“自家约束优先”
保留续期——B1 实施时发现此冲突并按既有测试修正，见 design.md B1 实施记录），
惩罚延迟 MUST 用 uint32 求和后饱和封顶 65000（`penalizeURLTestDelay` 等价语义），
MUST NOT 发生 uint16 回绕。单次超时或网络瞬态抖动 MUST NOT 直接抹除历史；
仅当历史已超过 `3 * interval` 时才可删除。

- **GIVEN** 节点历史延迟为 65000 且探测失败
- **WHEN** 写入惩罚历史
- **THEN** 延迟仍为 65000（不回绕为小值），`Time` 续期为当前时间
- **GIVEN** 节点历史延迟为 100 且探测失败
- **WHEN** 写入惩罚历史
- **THEN** 延迟为 1100 以内（单次惩罚增量），`Time` 续期为当前时间

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 连续两次探测失败清理故障史（B1 已落地）

同一成员连续两次探测失败时，`URLTest` MUST 清理其故障历史，防止假死节点被重新选回。
单次失败 MUST 只记惩罚，不得清史；失败计数 MUST 在探测成功后复位。

- **GIVEN** 节点连续两次探测失败
- **WHEN** 第二次失败处理完成
- **THEN** 该节点故障史被清理，下一次选择不再把它当作“近期可用”候选
- **GIVEN** 节点失败一次后探测成功
- **WHEN** 成功结果写入
- **THEN** 连续失败计数复位为 0

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 建连单步超时与故障转移（B1 已落地）

`URLTest.DialContext` 与 `ListenPacket` 对单个候选的尝试 MUST 带 3 秒单步超时
（用户 2026-11-17 已拍板，与 wanBox 现有探针超时对齐；仅用于“还有后备候选可试”
的尝试，最后一个候选不限时）。超时 MUST 只掐断当次尝试，MUST 保留
`interrupt_exist_connections = false`，现有连接 MUST NOT 被打断；
超时失败 MUST 计入该候选的连续失败并触发备用候选转移。

- **GIVEN** 主选节点建连黑洞（不响应）
- **WHEN** 单步超时到达
- **THEN** 当次尝试被掐断并转移到备用候选，前台流量不在该节点上无限等待
- **GIVEN** 建连正在转移
- **WHEN** 检查既有连接
- **THEN** 既有连接不受影响

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: URLTest 必须继续实现 OutboundGroup（B1 已落地，O4 防护）

wanBox 的 `URLTest` MUST 继续实现 `adapter.OutboundGroup`，不得照抄上游 8.15
对 `AutoSelect` 的移除。wanBox 无 autoselect 出站，不存在“解包绕过学习/记忆/故障转移”的
同类缺陷；移除将破坏 sing-box 路由对 URLTest 组的既有选路契约。

- **GIVEN** 任意 `URLTest` 出站实例
- **WHEN** 做 `adapter.OutboundGroup` 类型断言
- **THEN** 断言成立，且路由仍经由组接口选路

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: GeoSite/GeoIP 读取器关闭与流式过滤（B1 已落地）

GeoSite 底层读取器 MUST 在使用后关闭释放；GeoIP 提取 MUST 直接按目标国家码流式过滤，
MUST NOT 全量构建全球国家映射导致额外内存峰值。

- **GIVEN** 加载 GeoSite/GeoIP 规则集
- **WHEN** 提取完成
- **THEN** 底层读取器已关闭，且未出现全量国家映射构建的内存峰值

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 内嵌资源解压失败不得静默吞掉（B1 已落地）

内嵌资源解压（`Unxz` / assets）MUST 正确关闭文件句柄、正确拼接版本文件清理路径，
解压失败 MUST 返回错误，不得静默吞掉。

- **GIVEN** 内嵌资源解压失败
- **WHEN** 解压流程返回
- **THEN** 调用方收到明确错误，而不是静默成功

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 切网时重置路由器 DNS/HTTP 连接池（B1 已落地）

`networkChangeResetConnections=false` 时，切网 MUST 保留现有代理长连接
（不通知 NetworkManager 拆出站），但 MUST 仍重置路由器的 DNS/HTTP 连接池，
避免 DoH 等持久化 DNS 连接继续挂在旧物理网卡上卡死；首次出现接口
（曾 Lost/pause 后恢复）仍 MUST 通知回调以 NetworkWake。

- **GIVEN** 设置关闭了“切网重置连接”，当前接口为 wlan0
- **WHEN** 默认接口切到 rmnet0
- **THEN** 不通知出站回调拆连接，但路由器的 DNS/HTTP 连接池被重置一次
- **GIVEN** 同一接口重复上报
- **WHEN** 再次收到相同接口事件
- **THEN** 不重复重置、不重复通知

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 规则集缓存按实际条数校验，0 规则占位无效（B3 已落地）

`.srs` 规则集缓存 MUST 在加载与刷新时校验实际规则条数；0 规则占位文件 MUST NOT 被当作有效缓存。
`local:` 本地规则集支持以 B3 核查结论为准（wanBox 规则模型不支持，已跳过）。

- **GIVEN** 磁盘上存在 0 规则的 `.srs` 缓存文件
- **WHEN** 核心加载该规则集
- **THEN** 该缓存被判定无效并触发重新拉取或明确报错，不得静默当作有效规则集使用

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 种子过期判定与 idle 后重选（B4 已落地）

URLTestGroup 选择层 MUST 只认新鲜缓存：延迟超过 `3*interval` 的历史 MUST 视为无种子
（与 O3 惩罚窗口同一口径）；已存选择的历史过期后，`DialContext`/`ListenPacket`/
`Selected` MUST NOT 沿用，须重新按新鲜缓存预选或回落列表首位（B1 预排序顺序）。
选择过程 MUST NOT 改写 `history.Time`。Go 侧冷启动时 HistoryStorage 必为空
（每核新建内存态），故不做启动时播种；跨重启的缓存是应用层 DB，0 毫秒预选由 B1 的
ConfigBuilder 成员预排序承载。

- **GIVEN** 成员 A 缓存延迟 10ms 但已过期（超过 `3*interval`），成员 B 新鲜 500ms
- **WHEN** 选择当前节点
- **THEN** 选中 B，不得用过期 10ms 压过新鲜 500ms
- **GIVEN** 全部缓存均已过期
- **WHEN** 选择当前节点
- **THEN** 回落列表首位，healthy=false
- **GIVEN** 组 idle 后缓存全部过期
- **WHEN** idle 后首次连接
- **THEN** 重新按缓存预选，不得沿用过期选择

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 批量探针流式就绪与首轮等待跳过（B4 已落地）

`URLTestOutbounds` 批量探针中单个成员成功 MUST 立即写回 history 并重算选择
（仅选择变化时 `NotifyUpdated`），不必等整批跑完；同轮更优节点 MUST 按容差平滑切换。
流式择优只在组活跃（未 idle）时触发；流式就绪 MUST NOT 碰 ticker 与 lastActive，
检查间隔倒计时 MUST 按整轮结束重置。首轮探针进行中已有成员流式就绪时，首连 MUST 不再
等整轮；仍无任何新鲜选择时首连 MUST 等首轮探针（不得盲连列表首位超时节点）。

- **GIVEN** 批量探针中快节点 200ms 返回成功，慢节点仍在跑
- **WHEN** 快节点成功返回
- **THEN** 立即就绪并可被择优选中，不必等慢节点跑完
- **GIVEN** 流式就绪触发了一次切换
- **WHEN** 查看下次检查倒计时
- **THEN** 倒计时仍按整轮结束时刻计算，不被提前重置
- **GIVEN** 首轮探针进行中，快节点已成功、慢节点仍在跑
- **WHEN** 发起首次连接
- **THEN** 直接用快节点建连，不等整轮跑完

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 分层 URLTest 的向下容灾与向上回切不变量（preview.4 复审修复）

`libcore/protocol/urltest/outbound.go` MUST 把「向下容灾」与「向上回切」作为两条
互不通用的裁决路径，且恢复门只认本轮新观测。

**向下容灾（数据面，连接级）**：当前生效层的选中成员实际 `DialContext` /
`ListenPacket` 失败或触发 3 秒单步超时后，`failoverCandidates` MUST 先尝试
当前层的其余候选，再按层级序号升序尝试可用的更低层；每层内部仍是新鲜历史
优先、延迟升序、过期候选末尾兜底。回切门未打开的高层 MUST NOT 出现在候选里。
候选成功 MUST 立即把该成员提升为当前选择并把 `currentTier` 下移到该层，
否则高层新鲜历史会让下一次择优立刻把选择拉回坏主选。TCP 与 UDP 语义一致；
无备用候选时保持末候选不限时、其余候选各一次单步超时的既有规则；
父上下文取消或组关闭 MUST 立即停止后续尝试并返回上下文错误，MUST NOT 无限重试、
MUST NOT 泄漏已建连句柄；全部候选失败 MUST 返回主选错误且不改写选择；
未分层组与全组无新鲜历史的排序 MUST 保持既有行为。

**向上回切（轮次门，观测级）**：切回更高层 MUST 且 MUST ONLY 由
`performRoundEndCheck` 的双重门槛（连续健康轮次 ≥3 + 距上次失败 ≥60 秒）裁决。
数据面故障转移成功、高层延迟更低、或高层历史仍新鲜，都 MUST NOT 绕过该门；
回切 MUST NOT 默认断开现有连接（`interrupt_exist_connections = false`）。

**轮次观测不变量**：一轮探测 MUST 按成员记录本轮结局——新成功、新失败、
缓存跳过、轮次预算/调用方取消；旧历史只用于选路，MUST NOT 冒充本轮成功。
层内出现任一新成功才把该层健康计数 +1；无新成功但有新失败 MUST 清零并
记录失败时间；全部跳过、取消或未观测的层 MUST 保持原状（既不累计也不清零）。
低层结果 MUST NOT 替高层累计健康轮次。轮次观测集合 MUST 每轮独立且线程安全：
`batch.Wait()` 完成后才做轮次收尾，迟到结果 MUST NOT 跨轮写入；并发检查合并
到同一次 in-flight 轮次时 MUST 只收尾一次。整轮 5 秒预算到期与正常结束
MUST 使用同一套计数口径，取消/超时/缓存命中都不得算作新成功；探测间隔
仍 MUST 钳制 ≥10 秒，不因本修复新增更密的探测。

- **GIVEN** 当前选中 T1 且 T1 探测历史仍新鲜，但 T1 实际拨号失败，T2 有健康成员
- **WHEN** 一次 TCP 连接发起
- **THEN** 同层候选优先、随后尝试 T2 并成功，`currentTier` 下移到 T2
- **AND** 下一轮只有 T2 的新观测时选择仍留在 T2，不因 T1 历史新鲜而回切
- **GIVEN** T1 黑洞、T2 健康，且开启了单步超时
- **WHEN** 3 秒单步超时到达或父上下文被取消
- **THEN** 超时后及时降级到 T2；取消后立即停止，不再启动低层尝试并返回取消错误
- **GIVEN** 连续三轮均因缓存新鲜跳过全部探测，或三轮只有低层完成探测、高层被预算取消
- **WHEN** 每轮收尾
- **THEN** 高层健康计数不增加、不提前回切；高层连续三轮真实新成功且满足 60 秒门槛后才回切
- **GIVEN** 同层多候选、跨多个低层、全部候选失败或未分层组
- **WHEN** 走故障转移路径
- **THEN** 同层优先、低层按序、全失败返回主选错误、未分层组行为不变

> 验证：`libcore/protocol/urltest` 全套 Go 测试通过（含新增 RED→GREEN 复现）；本地 Windows 无 cgo/gcc，`-race` 未执行，由 Linux CI 覆盖；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 单步超时 3 秒与 UDP 故障转移（B4 已落地，B1 落地实现）

`URLTest.DialContext`/`ListenPacket` 单步超时取 3 秒（用户 2026-11-17 拍板，
与 wanBox 现有探针超时对齐），仅有后备候选时约束，末候选不限；超时后 MUST 故障转移
并计入 O3 连续失败，现有连接 MUST NOT 被打断。

- **GIVEN** 主选节点建连黑洞，存在备用候选
- **WHEN** 单步 3 秒超时到达
- **THEN** 当次尝试被掐断并转移到备用候选，现有连接不受影响

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。
