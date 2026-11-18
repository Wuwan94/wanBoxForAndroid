# Android Application Specification — Delta: borrow-netproxy-nightly

本增量仅描述本次 change 新增或改变的要求；未提及的主规范要求保持不变。
N8（eBPF 入站）本轮只做可行性评估，不在本增量中立 MUST 要求。

## Requirements

### Requirement: Stopped service still allows browsing, editing and speed tests

服务停止时，用户 MUST 仍可浏览与编辑节点、订阅、路由规则与策略组，并可执行 URL 测速与速度测速。
任何管理入口 MUST NOT 以核心运行中为前置条件；停止态编辑 MUST 只更新持久配置与 Root 快照，
MUST NOT 启动核心。测速 MUST 使用独立测试 box 实例（不注册正式核心的 PlatformLogWriter，
不共享 `cache.db`，不经过受管 eBPF/TUN 入站），MUST NOT 修改正式服务状态、当前选择或 Worker，
结束或取消时 MUST 清理测试进程与临时文件。停止态的订阅更新失败 MUST 保留上一版有效 Provider 与当前选择。
停止态的策略组编辑提交 MUST 复用现有 Root 编辑器链（CandidateSnapshot + validate → apply +
expectedRevision + 绕缓存确认）。
瘦管理器与停止态的 URL/速度测速 MUST NOT 依赖运行态 Clash Controller：入口 MUST NOT 因
`CoreRuntime.available` 为假而被移除（配置页工具栏菜单与单节点弹出菜单均保留入口），
瘦管理器/停止态下改走 Root 侧短生命周期测试会话（`wanboxctl node urltest/speedtest` →
`rootbox --url-test/--speed-test`，独立测试 box 实例）；完整 APK 仍走进程内 TestInstance。
插件出站（external chains）的节点本批 MUST NOT 走 Root 测试会话，App 侧 MUST 在调用前拒绝。
服务在本页由运行转为停止时，节点列表 MUST 提示停核后仍可浏览、编辑与测速。

#### Scenario: Stopped service still edits nodes and subscriptions

- **GIVEN** 核心处于停止态
- **WHEN** 用户打开节点列表并编辑一个节点，随后更新一个订阅
- **THEN** 编辑与订阅更新均可完成且不启动核心
- **AND** 订阅更新失败时上一版 Provider 与当前选择保持不变

#### Scenario: Stopped service still runs speed tests

- **GIVEN** 核心处于停止态
- **WHEN** 用户对当前组执行 URL 测试与速度测试
- **THEN** 两项测试均可完成并显示结果
- **AND** 正式服务状态、当前选择与 Worker 不被修改
- **AND** 取消测试后测试进程与临时文件被清理

#### Scenario: Stopped thin-manager speed tests use the Root test session

- **GIVEN** 瘦管理器且核心处于停止态
- **WHEN** 用户对一个节点执行 URL 测试与速度测试
- **THEN** 两项测试经 Root 侧短生命周期测试会话完成并显示结果
- **AND** 工具栏与单节点菜单中的测试入口均可见
- **AND** 正式服务状态、当前选择与 Worker 不被修改

#### Scenario: Plugin-backed nodes refuse the Root test session

- **GIVEN** 节点配置含插件出站（external chains）
- **WHEN** 用户在停止态对其执行 URL 测试或速度测试
- **THEN** 测试被拒绝并显示可读原因，不启动 Root 测试会话

#### Scenario: Stopped service still edits policy groups

- **GIVEN** 核心处于停止态且存在一个可编辑的策略组
- **WHEN** 用户修改策略组成员并提交
- **THEN** 配置被更新且核心仍处于停止态
- **AND** 提交复用 validate → apply 与 expectedRevision 校验链

### Requirement: One-click export of a redacted diagnostics package

设置页 MUST 提供一键“导出诊断包”入口，默认导出到系统 Download 目录，文件名固定为
`wanbox-diagnostics-<时间>.tar.gz`（时间戳为 RFC 3339 本地时间转文件名安全形式，
不含设备标识与用户标识）。导出前 MUST 显示确认说明，明确包内不含凭据、
但含路由规则域名与脱敏后的地址信息。诊断包 MUST 为 gzip 压缩 tar（`.tar.gz`），
先写同目录临时文件并 `fsync` 后原子重命名到目标；目标 MUST 是普通文件，
已存在目录或 symlink 时 MUST 拒绝。包内 MUST 包含脱敏后的 service/core 日志（轮转备份
存在则收录、缺失不失败，每文件取尾部，上限 4 MiB/文件）、Root 快照、生成配置、模块状态与服务状态、本地规则，
以及版本/时间/脱敏声明/缺失文件清单的 README。包内 MUST NOT 包含远程规则集原文
（只列名称/来源/哈希/更新时间）、用户备份文件、`cache.db`、私钥/证书或 Catalog staging 临时文件。
导出完成 MUST 显示目标路径与大小；失败 MUST 显示可读原因。缺失的单个文件 MUST 只记入 README
缺失清单，MUST NOT 导致整包失败。诊断包 MUST NOT 含“可选脱敏用户备份”选项。

脱敏规则 MUST 按 design.md §2 的表执行：订阅地址全文隐藏；节点链接全文隐藏；URL 内凭据、
认证头与敏感 JSON 键值替换为 `***`；SSID 逐条替换为 `***`（条数保留）；分应用名单整体替换为
`***`；服务器地址与 SNI 替换为 `***`；公网 IPv4 保留前两个 octet、其余打码，公网 IPv6 保留前 32 位、
其余打码，私网/回环/链路本地/CGNAT 只保留类别 token；MAC 地址全文打码；无法按字段脱敏的
损坏 JSON MUST 省略原文并替换为固定 diagnostic_error 说明；单行脱敏消息截断上限 1024 rune。

#### Scenario: Export succeeds with redaction applied

- **GIVEN** 用户在设置页打开导出诊断包并确认说明
- **WHEN** 导出完成
- **THEN** 目标路径出现一个 `.tar.gz` 文件且界面显示路径与大小
- **AND** 包内日志与配置不含订阅地址、节点凭据、服务器地址、SSID 明文、完整公网 IP 或 MAC 地址
- **AND** README 列出版本、生成时间与缺失文件清单

#### Scenario: Export refuses a non-file target

- **GIVEN** 用户指定的导出目标是一个已存在目录
- **WHEN** 执行导出
- **THEN** 导出被拒绝并显示可读原因
- **AND** 不创建或覆盖任何文件

#### Scenario: Corrupted JSON is omitted instead of leaked

- **GIVEN** 某份待导出 JSON 配置已损坏、无法按字段脱敏
- **WHEN** 执行导出
- **THEN** 包内该文件被替换为固定 diagnostic_error 说明，不含原文
- **AND** 其余文件仍正常导出

### Requirement: Unified per-network rules merge Smart Auto-stop and Wi-Fi policy

高级设置 MUST 提供单一「按网络」规则表，取代 F1 智能启停的独立规则编辑入口与新增的 Wi-Fi 名单入口；
MUST NOT 同时存在两个可互相覆盖的网络开关。规则上限 MUST 为 5 条。每条规则 MUST 由匹配器与动作组成：
匹配器可组合 SSID（精确保留空格/大小写/逗号）、`gateway:` 默认网关、CIDR/精确本地地址、
实际默认出口类型（`wifi` / `not_wifi`，由 Root 按 policy routing 读取）；同一条规则内多项匹配器为 AND，
规则之间为 OR，按用户顺序优先匹配，仅首条匹配规则生效。动作 MUST 三选一且互斥：
`suspend`（命中→优雅停核挂起）、`direct`（命中→运行时模式切 `Direct`，只改运行时，
不覆盖默认模式）、`keep`（命中→保持代理）。无规则匹配时 MUST 保持当前默认行为。
功能 MUST NOT 请求定位权限；SSID MUST 由 Root 侧按实际出口 station 读取。
未知网络 MUST NOT 被当作非 Wi-Fi；指纹读取失败或 SSID 不可读时 MUST 保持当前状态并记录，
MUST NOT 猜测切换。`suspend` 动作 MUST 完整保留 F1 状态机：命中后只在核心确认退出并释放本实例
TUN/auto-route 后报告挂起；离开匹配网络自动恢复；同 fingerprint 手动恢复抑制重复挂起；
手动停止不被自动恢复覆盖；挂起通知保留“恢复”“停止”按钮。`direct` 动作 MUST 经 Clash API
切换并回读确认，参数不变时 MUST NOT 重载；API 失败 MUST NOT 重载兜底。
F1 已保存规则 MUST 迁移为 `suspend` 动作规则并保留原顺序；功能关闭时 MUST 保留规则，
MUST NOT 清空；迁移失败 MUST 保留原 F1 数据并报错。

#### Scenario: First matching rule wins and actions do not fight

- **GIVEN** 用户保存两条规则：首条匹配当前 Wi-Fi 且动作为 `direct`，次条同样匹配且动作为 `suspend`
- **WHEN** 设备接入该 Wi-Fi
- **THEN** 运行时模式切换为 `Direct`，核心不被挂起
- **AND** 默认模式保持不变

#### Scenario: Suspend rule keeps F1 semantics

- **GIVEN** 首条匹配规则动作为 `suspend` 且当前网络命中
- **WHEN** 核心优雅退出并释放本实例 TUN/auto-route
- **THEN** 显示已挂起通知及“恢复”“停止”按钮
- **AND** 用户同网手动恢复后，同一 fingerprint 不再重复挂起
- **AND** 离开该网络后自动恢复此前运行意图

#### Scenario: Unknown network never guesses

- **GIVEN** 当前网络指纹不可读（SSID 尚不可读）
- **WHEN** 网络评估触发
- **THEN** 不切换模式、不挂起核心
- **AND** 评估失败被记录用于诊断

#### Scenario: F1 rules migrate without loss

- **GIVEN** 用户此前保存了 3 条 F1 规则且功能开启
- **WHEN** 升级后打开按网络规则表
- **THEN** 3 条规则原顺序保留且动作均为 `suspend`
- **AND** 关闭功能后规则仍在表中

### Requirement: Shared network interception is an explicit opt-in eBPF shared path

设置页 MUST 提供默认关闭的“接管共享网络流量（热点 / USB 共享）”开关，说明 MUST 明确
本开关不创建热点、DHCP、NAT 或 IPv6 RA。开启时 MUST 使用 eBPF 共享路径：`shared.interface`
MUST 含至少一个真实下游接口（不能填 loopback），`data_plane` MUST 为 `socket_assign`
或 `packet_rewrite`，`dns_mode` 默认 `hijack`，`default_interface` 默认 `false`。
开启前 MUST 通过 eBPF 能力预检；预检失败 MUST 明确报错，MUST NOT 自动切换数据平面。
`default_interface=false` 时，已配置的共享接口成为默认上游后 MUST 暂时停止共享挂载，
恢复为下游后重新挂载。TUN 模式下共享接管 MUST 明确不支持；TUN 用户的 LAN 网关能力保持不变，
MUST NOT 在文案中把 LAN 网关描述为流量接管。分应用名单 MUST 只筛选本机应用，
MUST NOT 用来识别热点客户端。

#### Scenario: Shared interception requires a downstream interface

- **GIVEN** 用户开启共享接管但未配置下游接口
- **WHEN** 保存并尝试应用
- **THEN** 保存被拒绝并提示未配置下游接口
- **AND** 核心不启动共享挂载

#### Scenario: eBPF preflight failure blocks shared interception

- **GIVEN** 用户开启共享接管且所选数据平面能力预检未通过
- **WHEN** 应用共享配置
- **THEN** 应用被拒绝并显示预检原因
- **AND** 数据平面不被静默切换

#### Scenario: TUN mode keeps LAN gateway without interception

- **GIVEN** 当前为 TUN 回退模式且用户打开 LAN 共享页
- **WHEN** 查看共享相关文案
- **THEN** LAN 网关能力可用
- **AND** 文案不把 LAN 网关描述为共享流量接管

### Requirement: Per-app lists are keyed by Android user and support clones

分应用黑/白名单 MUST 以严格的 `<userId>:<package>` 字符串为持久化键；同一包名在不同用户
（含工作资料与应用分身）下 MUST 为不同条目，UI MUST 按用户分组展示。名单解析 MUST 由 Root 侧
按用户查询 Android package service 得到 UID 后合并为 `include_uid`/`exclude_uid`，
MUST NOT 把包名直接交给 sing-box，MUST NOT 跨用户合并 UID。白名单解析后与原生 include
UID/range 均为空时，运行时 MUST 设置 `exclude_uid_range=["0:4294967294"]` 排除全部有效 UID，
MUST NOT 生成空 include 后静默变为全量接管，MUST NOT 注入占位 UID。仅 eBPF 共享路径时
MUST NOT 查询应用 UID。旧单 UID 名单独 MUST 按当前用户反查包名迁移为 `<userId>:<package>`；
无法反查的条目 MUST 保留在旧字段并提示用户手动确认，MUST NOT 静默丢弃。

#### Scenario: Clone of the same app is listed separately

- **GIVEN** 同一包名在用户 0 与用户 10（应用分身）均已安装
- **WHEN** 用户打开分应用列表
- **THEN** 该包名出现两次，分属不同用户分组
- **AND** 只勾选其中一个用户时，另一个用户的同包名应用不受影响

#### Scenario: Empty whitelist does not become full interception

- **GIVEN** 用户开启白名单模式但名单为空
- **WHEN** 生成 Root 入站配置
- **THEN** 运行时包含 `exclude_uid_range=["0:4294967294"]`
- **AND** 不生成空 `include_uid`

#### Scenario: Unresolvable legacy UID entries are kept for review

- **GIVEN** 旧名单中有一条无法反查包名的 UID
- **WHEN** 执行迁移
- **THEN** 该条目保留在旧字段并提示用户手动确认
- **AND** 其余可反查条目正常迁移

### Requirement: Route rules may target a policy group

路由规则 MUST 支持以策略组为目标：`RuleEntity` 新增可空的策略组目标（稳定实体 ID 引用），
与节点 `outbound: Long` 正交；两者同时存在时 MUST 拒绝保存并定位到目标字段。
内置目标（`0`=proxy、`-1`=bypass、`-2`=block）保持不变。生成配置时，规则 MUST 写原生
`route.rules` 的 `outbound=<策略组运行时 tag>`（主组固定 `proxy`，非主组为 trim 后组名），
插入位置 MUST 在默认全局模式、DNS 接管及本地快捷规则之后、内置站点分流之前；
无法确认插入位置时 MUST 交给 JSON 编辑器。规则引用的策略组不存在时 MUST 复用现有缺失引用预检：
列表标红、启动前 modal 汇总。编辑/删除/改名策略组导致被路由引用的组失效时，
出站分区事务 MUST 阻断并定位到该策略组。多条件、logical、invert 与其他原生字段 MUST 保留原样，
走 JSON 编辑器。

#### Scenario: Rule targets a policy group

- **GIVEN** 存在一个名为 `streaming` 的策略组与一条单条件域名规则
- **WHEN** 用户将该规则目标设为 `streaming` 并启动核心
- **THEN** 生成配置的对应 `route.rules` 条目 `outbound` 为 `streaming`
- **AND** 该规则位于本地快捷规则之后、内置站点分流之前

#### Scenario: Rule targets a missing group

- **GIVEN** 一条规则的目标策略组已被删除
- **WHEN** 用户打开路由规则列表并尝试启动核心
- **THEN** 该规则显示红色错误状态
- **AND** 启动前出现列出规则与缺失策略组的提醒

#### Scenario: Deleting a referenced group is blocked

- **GIVEN** 一条启用规则引用策略组 `streaming`
- **WHEN** 用户尝试删除 `streaming`
- **THEN** 删除被阻断并定位到 `streaming`
- **AND** 规则与配置保持不变

#### Scenario: Rule cannot target both a node and a group

- **GIVEN** 一条规则已选节点目标
- **WHEN** 用户同时为其选择策略组目标并提交
- **THEN** 提交被拒绝并定位到目标字段
- **AND** 原规则保持不变
