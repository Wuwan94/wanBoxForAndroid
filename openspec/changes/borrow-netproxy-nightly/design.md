# 设计：借鉴 NetProxy nightly（N1–N8）

本文档只做设计，不写实现代码。所有 MUST/SHALL 以 `specs/` 增量为准；本文件解释取舍与落法。

## 0. 总原则

- **只借设计与行为，不复制代码**（登记见 proposal.md）。NetProxy GPL-3.0；wanBox 落到既有结构里重写。
- **wanBox 自家约束优先**。与上游冲突时以自家为准，取舍见 §7。
- **管理面与核心进程解耦**：节点/订阅/配置是持久数据，管理 API 与诊断导出不得依赖核心进程存活。
- **Fail-Closed**：诊断、网络策略、共享接管、热更新任一环节无法确认安全时，停止并明确报错，
  不得谎报成功、静默直连或静默回退。
- **default 保真**：任何新开关默认关闭或默认保持现有行为；已有配置不迁移语义，只迁移表示形式，
  且迁移失败时保留原值并报错。

## 1. N1 停核可编辑（B5）

### 落法

- 管理面（节点浏览/编辑、订阅更新、路由/策略组编辑、URL 测速、速度测速）MUST 不以
  `serviceState.started` 为前置条件。凡 UI 或 Root 调用链以“核心运行中”为隐式前提的，
  B5 须逐面核查并消除。
- 测速复用 wanBox 已有独立测试 box 实例：URL 测速与速度测速各起独立 box，不注册正式核心的
  PlatformLogWriter、不共享 `cache.db`、不经过受管 eBPF/TUN 入站；借鉴上游“短生命周期会话”
  的约束——测速会话不得修改正式服务状态、当前选择或 Worker，结束/取消时 MUST 清理进程与临时文件。
- 策略组编辑提交复用现有 Root 编辑器链（CandidateSnapshot + validate → apply + expectedRevision
  + 绕缓存确认）：停止态只更新配置、不启动核心；运行态重载并核验 revision/state。
- 订阅更新失败 MUST 保留上一版有效 Provider 与当前选择（与上游订阅事务一致，wanBox 已有同类语义）。
- 测速入口在瘦管理器与停止态下同样可用（B5 修复了两处按 `CoreRuntime.available` 移除入口的做法：
  配置页工具栏菜单与单节点弹出菜单均不再移除 URL 测试/速度测试入口）；服务在本页由运行转为
  停止时，节点列表提示“服务已停止。节点、订阅与规则仍可浏览、编辑与测速。”
- 瘦管理器 / 停止态的测速不走运行态 Clash Controller，改走 Root 侧短生命周期测试会话：
  App 用最小测试配置（`buildConfig(profile, forTest=true)`，不含 Clash API、缓存与受管入站）
  调 `wanboxctl node urltest <id>` / `node speedtest <id>`，Root 侧由 `rootbox --url-test` /
  `--speed-test` 起独立测试 box（`NewTestSingBoxInstance`，与正式连接同一隔离语义）；
  完整 APK 仍走进程内 `TestInstance`。测试配置临时文件 0600、执行后删除；Root 会话
  超时为探测超时 + 60 秒（URL）/120 秒（速度）收尾。
- 插件出站（external chains）的节点本批不支持 Root 测试会话：App 侧在调用前拒绝，
  URL 测试报 `url_test_plugin_unsupported`、速度测试报 `speed_test_plugin_unsupported`。

### 不做的事

- 不为停核编辑另造一套“离线模式”开关；停止态可编辑是默认行为，不是可选项。
- 不把正式核心的启动/停止状态机改成“测速专用核心”。

## 2. N2 脱敏诊断包（B5）

### 包结构（借鉴上游，字段按 wanBox 模型重写）

- 格式：gzip 压缩 tar，扩展名 `.tar.gz`；先写同目录临时文件，`fsync` 后原子重命名到目标；
  目标 MUST 是普通文件（已存在目录/symlink 时拒绝），不得覆盖用户指定目录外的路径。
- 内容清单（缺失文件跳过，不得因单个缺失导致整包失败；但须在 README 内列出缺失项）：
  - `logs/service.log` 与轮转备份（每文件取尾部，上限 4 MiB/文件，与上游 `MaxFileBytes` 同值）、
    `logs/sing-box.log` 与轮转备份（同上限）；轮转备份（`.1`/`.2`）为可选：存在则收录，
    缺失不记入缺失清单、不失败；
  - `config/root-snapshot.json`（当前 Root 快照的脱敏版）、`config/singbox-config.json`
    （生成配置的脱敏版）、`config/module-status.json`（版本/状态/诊断字段）；
  - `state/service.json`（服务状态快照，脱敏后）；
  - `rules/local/`（本地规则，脱敏后；只含规则原文的匹配条件，不含凭据）；
  - `README.txt`：wanBox 版本（正式版 + 预览版 + 版本码）、模块版本码、内核版本、
    生成时间（RFC 3339 本地时间）、脱敏声明、缺失文件清单。
- wanBox 此前没有持久化的 service/core 日志文件（supervisor 曾丢弃核心 stderr，只读
  WANBOX_STATS 行）：B5 起 supervisor 把自身日志写 `logs/service.log`、把核心 stderr
  写 `logs/sing-box.log`（各 capped 8 MiB、保留尾部 4 MiB 的原地截断，打开的追加 fd
  保持有效），诊断包的日志条目才有真实来源。
- 导出目的地默认系统 Download 目录（与上游默认一致），文件名固定为
  `wanbox-diagnostics-<时间>.tar.gz`（时间戳为 RFC 3339 本地时间转文件名安全形式，
  冒号改连字符，不得含设备标识与用户标识）；wanboxctl 再校验目标父目录解析后位于
  `/storage/emulated/<user>/Download` 或 `/sdcard/Download`，否则拒绝。
- 诊断包不含用户备份：本批不做“可选含一份脱敏用户备份”的选项，保持排除。
- 远程规则集 URL 在 README 元数据里只脱敏 userinfo 与 secret query 参数（host/path
  保留为“来源”）；规则内容永不导出；哈希为 `assets/srs/<safeTag>.srs` 的 SHA-256，
  更新时间为文件 mtime、缺失为 `unknown`。
- 导出前确认文案 MUST 用 design.md §10 的原句，明示残留风险（路由域名/规则集名会原样保留）。
- **不包含**：远程规则集原文（只在 README 列出名称/来源/哈希/更新时间）、用户备份文件、
  `cache.db`、任何私钥/证书、Catalog staging 临时文件。
- 导出入口：设置页“导出诊断包”一键操作；导出前 MUST 明确提示包内不含凭据、但含路由规则域名
  与脱敏后的地址信息（见下表残留风险），由用户确认后执行；导出完成 MUST 显示目标路径与大小，
  失败 MUST 显示可读原因。

### 脱敏规则（定死，B5 实现 MUST 逐条有测试）

| 类别 | 规则 | 说明 |
|---|---|---|
| 订阅地址 | 任何 `http(s)://…` URL 全文替换为 `[订阅链接已隐藏]` | 借鉴上游；不保留 host/path/query 的任何部分 |
| 节点链接 | 任何非 http(s) 的 `scheme://…`（如 `vmess://`、`vless://`、`trojan://`、`ss://`、`hysteria2://`、`tuic://`、`juicity://`）全文替换为 `[节点链接已隐藏]` | 借鉴上游 |
| URL 内凭据 | `scheme://<userinfo>@` 的 userinfo 部分替换为 `***`；query 中 `token/key/secret/password/auth/uuid/hwid` 的值替换为 `***` | 借鉴上游 |
| 认证头/键值 | `Authorization` / `Proxy-Authorization` 的凭证值替换为 `***`；JSON 键（不区分大小写，`-`/`_` 等价）属于 `access_token/api_key/auth/auth_key/auth_str/authorization/client_secret/custom_headers/headers/hwid/password/pre_shared_key/private_key/private_key_passphrase/private_key_path/proxy_authorization/psk/public_key/secret/short_id/token/url/user_agent/username/uuid` 的，值替换为 `***` | 键表借鉴上游，按 wanBox 字段复核；`url` 键整体隐藏（订阅地址规则优先） |
| 行内密钥 | 日志行内 `hwid/uuid/token/password/secret/auth/psk/private_key/public_key/short_id` 等 `key=value` 或 `key: value` 形式的值替换为 `***` | 借鉴上游 |
| Wi-Fi SSID | 网络策略中的 SSID 名单：每个 SSID 字符串替换为 `***`，条数保留 | 借鉴上游（上游把整名单替换为 `"***"`；wanBox 改为逐条替换以保留条数结构，名字仍不可见） |
| 分应用名单 | `proxy_apps` / `bypass_apps` / `include_package` / `exclude_package` 的值整体替换为 `***` | 借鉴上游；包名本身可识别用户画像，不得外泄 |
| 服务器地址 | 节点/出站的 `server` / `address` / `server_name`（SNI）字段值替换为 `***` | **严于上游**：上游只对 URL 与密钥字段脱敏；wanBox 诊断不需要真实服务器地址即可定位配置问题 |
| IP 地址打码 | 公网 IPv4：保留前两个 octet，其余打码，如 `203.0.x.x`；公网 IPv6：保留前 32 位（前两个 hextet），其余打码，如 `2001:db8:xxxx:xxxx::`；私网/回环/链路本地/CGNAT（`10/8`、`172.16/12`、`192.168/16`、`127/8`、`169.254/16`、`::1`、`fe80::/10`、`fc00::/7`、`100.64/10`）：不保留地址，只保留类别 token `<private>` / `<loopback>` / `<link-local>` / `<cgnat>`；网关 IP 与 DNS 服务器 IP 同规则；端口号保留 | **上游无 IP 打码规则**（`redact.go` 无 IP 模式）；本表为 wanBox 自定，B5 定死。注意：早期草稿曾写“保留首 octet”，与示例 `203.0.x.x` 矛盾，已按示例纠正为保留前两个 octet |
| MAC 地址 | 全文替换为 `**:**:**:**:**:**` | 上游共享路径支持 MAC 筛选；wanBox 诊断包内 MAC 一律不留 |
| 损坏 JSON | 无法按字段脱敏的损坏 JSON 文件：省略原文，替换为 `{"diagnostic_error":"JSON 配置损坏，已省略原文以保护敏感信息"}` | 借鉴上游 Fail-Closed 做法 |
| 日志行长度 | 单行脱敏消息截断上限 1024 rune，超长加 `…` | 借鉴上游 `maxMessageRunes` |

残留风险（须在导出确认文案中明示）：路由规则的域名/规则集名、地名、包名以外的匹配条件会原样保留，
可能反映用户分流意图；用户须自行检查后再对外分享。诊断包默认写到系统 Download 目录时，
文件名 MUST 不含设备标识与用户标识。

### 与 wanBox 现有诊断的关系

- 设置页模块诊断区（呈现模块已上报的退出/事件）保持不变；诊断包是“导出”能力，不是替代。
- 日志级别门控（主规范 libcore-integration）保持不变：脱敏在落盘/导出两层都做，导出层不得依赖
  日志层已脱敏。

## 3. N3 与 F1 合并：统一「按网络」规则（B6）

### 问题

F1（智能启停）与 N3（按网络选策略）触发源相同（当前物理网络），动作不同（停核 vs 切 Direct 模式）。
若各立开关，用户在“公司 Wi-Fi 停核”和“公司 Wi-Fi 直连”同时开启时，两个执行器会互相覆盖，
且通知/状态互相矛盾。

### 统一模型

- **单规则表**：取代 F1 的独立规则编辑入口与 N3 的 Wi-Fi 名单入口。规则上限仍为 5 条（F1 既有上限，
  不放宽）；每条规则 = 匹配器 + 动作。
- **匹配器**（一条规则内可组合多项，同一条内为 AND；规则之间为 OR，按用户顺序优先匹配）：
  - `ssid`：Wi-Fi 名称，精确保留空格/大小写/逗号（借鉴上游）；空 SSID 不得匹配任何 SSID 规则；
  - `gateway:`：默认网关 IP（借鉴 F1 已实现的 `gateway:` 匹配）；
  - `cidr` / 精确 IP：本地地址匹配（F1 已实现）；
  - `egress`：实际默认出口接口类型（`wifi` / `not_wifi`，由 Root 按 policy routing 读取，借鉴上游
    `network_route_unix.go`，不回退到 dumpsys 文本猜测）。
- **动作**（三选一，互斥）：
  - `suspend`：命中→优雅停核挂起（F1 语义，保留全部状态机、通知、手动恢复抑制）；
  - `direct`：命中→运行时模式切 `Direct`（N3 语义，只改运行时，不覆盖默认模式；离开→恢复默认模式）；
  - `keep`：命中→保持代理（显式白名单动作，用于“在该网络下不要触发其他规则”的兜底表达）。
- **指纹源**：单指纹 =（SSID + 实际出口接口 + 默认网关 + 本地地址集合）；评估由 Root supervisor
  在网络事件（route/link/address/nl80211）到达时触发，不做定时轮询（与上游一致）；未知网络
  不得当作非 Wi-Fi；开机未知时校准默认模式（与上游一致）。
- **裁决**：首条匹配规则胜出；无规则匹配→保持当前默认行为；`suspend` 与 `direct` 同时只可能由
  首条匹配规则产生，不存在两动作并存。手动恢复/手动停止的语义只适用于 `suspend` 动作
  （同 fingerprint 抑制重复挂起、手动停止不被自动恢复覆盖，F1 既有语义全部保留）。
- **新规则默认动作**：新建规则默认 `keep`（最保守，B6 批次决定）；用户须显式改动作才生效。
- **SSID 读取**：SSID 由 Root 按实际出口 station 经 `iw dev <iface> link` 读取；Wi-Fi 出口上
  `iw` 缺失或读取失败时，评估整体 Fail-Closed——同一评估内 `gateway:`/CIDR 规则也不触发，
  保持当前状态并记录（`EvaluateNetworkPolicy` 在 `ReadErr != nil` 时直接返回未知，不进入匹配）。
- **评估触发（实现说明）**：评估观察沿用 F1 既有架构——5 秒 health tick 上读指纹并重新裁决，
  但动作只在指纹或动作变化时执行一次（`suspend` 返回即停核；`direct` 只在动作变为 direct 时切换，
  离开时恢复默认模式；未知指纹只在转为未知时记一次并开机校准一次默认模式）。这不是“定时轮询动作”，
  但与 delta spec 字面的“网络事件（route/link/address/nl80211）到达时触发评估”仍有差距：
  本轮未实现 netlink 事件 watcher，事件驱动评估留待后续批次，delta spec 措辞保持目标形态不变。
- **权限**：不请求定位权限（F1 既有红线）；SSID 由 Root 侧按实际出口 station 读取，不经过
  Android 定位 API。通知门禁（应用/渠道可用性、API 33+ 运行时通知权限）复用 F1 既有实现。
- **迁移**：F1 已保存的 5 条以内规则迁移为 `suspend` 动作规则，原顺序保留；F1 开关关闭时整表停用，
  不得清空规则；迁移失败 MUST 保留原 F1 数据并报错，不得静默丢弃。

### 与自家约束的取舍

- “切换不中断连接”：`direct` 动作是运行时模式切换，经 Clash API 切换并回读确认，参数不变时
  不重载（借鉴上游“API 失败不得重载兜底”）；`suspend` 动作本来就是停核，与“切换不中断”不冲突，
  但须在文案中区分“挂起会断开当前连接”。
- “Fail-Closed”：指纹读取失败、SSID 不可读、网关解析失败时，动作 MUST 为“保持当前状态并记录”，
  不得猜测为“非 Wi-Fi”而切 Direct 或挂起（与上游“SSID 尚不可读时 Target 为空”一致）。

## 4. N4 共享流量接管（B6）

### 落法

- 只接 eBPF 共享路径，与本机路径独立开关：`shared.enabled` 默认 `false`；
  启用时 `shared.interface` MUST 含至少一个真实下游接口（不能填 loopback），
  `data_plane` 为 `socket_assign`（默认，支持以太网/raw-IP/PPP/隧道）或 `packet_rewrite`
  （兼容，需以太网帧，适用普通热点/LAN 下游），`dns_mode` 默认 `hijack`，
  `default_interface` 默认 `false`（仅当客户端与上游路由器共用一个接口、且本机作为网关时按需开启）。
- 模块不创建热点/DHCP/NAT/IPv6 RA/IP 转发（与上游一致）；绕过的 IPv4 流量仍需系统 SNAT/masquerade，
  须在 UI 文案中明示“开启接管≠开启热点/共享本身”。
- 接口出现/消失/成为默认上游时由 sing-box 管理 attachment；`default_interface=false` 时，
  已配置的共享接口成为默认上游后暂时停止共享挂载，恢复为下游后重新挂载（与上游一致）。
  Go 侧只做可测试裁决（`SharedAttachmentDecision`：变默认上游→`paused`，恢复为下游→`attached`）
  与诊断，不直接建立/拆除 attachment；attachment 生命周期由 sing-box 管理。
- 接口选择交互（B6 批次决定，最保守）：用 Root 枚举出的真实下游接口候选列表供用户勾选
  （`wanboxctl __internal shared-interfaces`，Root 侧校验、loopback 不得入选；Root 枚举失败时
  回退本地 `NetworkInterface` 枚举），不采用手输接口名（手输易错且绕不过校验）。
- TUN 模式下共享接管明确不支持：TUN 不建 local/shared 映射（与上游一致）；TUN 用户仍可用
  `LanSharingActivity` 的 LAN 网关能力，但那不是流量接管，不得在文案中混淆两者。
- 开启前 MUST 过 eBPF 能力预检（见 §8 预检复用 N2 诊断能力）；预检失败 MUST 明确报错，
  不得自动切换数据平面（与上游一致）；`udp_timeout` 下限 5s（上游值，wanBox 沿用）。
- 分应用名单只筛选本机应用，不得用来识别热点客户端（与上游一致）；共享路径支持
  `include_source_cidr` / `exclude_source_cidr` / `include_mac_address` / `exclude_mac_address`，
  排除优先（字段名属 sing-box 原生 schema）。

## 5. N5 分应用按用户细化（B6）

### 落法

- 持久化键改为严格的 `<userId>:<package>` 字符串（与上游一致）；Android 每个用户独立展示，
  含工作资料与应用分身；同一包名在不同用户下是不同条目。
- UID 解析由 Root/Go 侧按用户查询 Android package service 后合并：黑名单→`exclude_uid`，
  白名单→`include_uid`；不得把包名直接交给 sing-box，不得跨用户合并 UID。
- 空白名单护栏（借鉴上游）：白名单解析后与原生 include UID/range 均为空时，仅在运行时设置
  `exclude_uid_range=["0:4294967294"]` 排除全部有效 UID；不得生成空 include 后静默变全量接管，
  不得注入占位 UID。护栏生效时管理器自身 UID 不再被当作唯一 include（与旧行为的差异：
  旧实现白名单为空时仍把管理器 UID 塞进 include，本轮改为不代理任何应用）。
- 仅 eBPF 共享路径时不查询应用 UID，不把本机应用名单当成热点客户端过滤器（与上游一致）。
- 应用安装/重装/UID 变化/用户范围变化后，通过重启或配置 reload 重新解析，不维护模块侧 UID 缓存；
  不自动加入 UID 0；Root UID 策略（`rootUidPolicy`）与应用名单正交，互不覆盖。
- 迁移：旧单 UID 名单（`DataStore.individual`）按当前用户（user 0）+ 包名反查迁移为
  `<userId>:<package>`；无法反查包名的 UID 条目 MUST 保留在旧字段并提示用户手动确认，
  不得静默丢弃。**旧字段清理版本（B6 批次决定，最保守）：本轮不设清理版本**，旧字段只读保留、
  清理留待以后版本另行决定，不得在本轮写死一个删除版本（覆盖评估阶段“保留一个版本周期”的表述）。

## 6. N6 路由规则指定策略组（B7）

### 落法

- 数据模型：`RuleEntity` 新增可空的策略组目标（稳定实体 ID 引用，与节点 `outbound: Long`
  正交；两者同时存在时 MUST 拒绝保存并定位到目标字段）。内置目标（`0`=proxy、`-1`=bypass、
  `-2`=block）保持不变。
- 生成：规则写原生 `route.rules` 的 `outbound=<策略组运行时 tag>`（主组固定 `proxy`，
  非主组为 trim 后组名，与现有组 tag 契约一致）；插入位置在默认全局模式、DNS 接管及本地快捷规则
  之后、内置站点分流之前（借鉴上游）；无法确认插入位置时交给 JSON 编辑器（与上游一致）。
- 保护：复用现有缺失引用预检——规则引用的策略组不存在时列表标红、启动前 modal 汇总；
  出站分区事务内保护被路由引用的策略组：编辑/删除/改名导致失效时 MUST 阻断并定位到策略组
  （借鉴上游 `validateRemovedRouteGroups`）。
- 多条件、logical、invert 与其他原生字段保留原样，走 JSON 编辑器（与上游一致）；单条件表单
  只支持“指定策略组”一种新增目标类型。
- `default` 保真：默认规则与默认 Play/广告规则不受影响；新目标类型默认不启用，
  用户显式选择后才生效。

### 实现落点（B7 批次决定）

- 引用键：`RuleEntity.policyGroupId: Long?`（Room 11，`rules.policyGroupId` 可空列，
  手工迁移 `MIGRATION_10_11`；旧行缺失即 NULL，默认规则不受影响）。`11.json`
  schema 由 CI 构建时生成，本地无 Java/Gradle 环境无法预生成，随 CI 产物补提交。
- 生成：被引用的非主组在 `buildConfig` 的用户规则循环之前获得一个 selector 出站
  （tag 为 trim 后组名，成员取该组节点并尊重编辑器草稿与禁用组）；主组沿用已存在的
  `proxy`。建不出的组（不存在、组名空白、成员为空、tag 已被他组占用）不记入映射，
  引用它的规则走既有缺失出站分支（提示后丢弃，不静默回退主代理）。无 selector 的
  主组（如单节点组）仍写 `proxy`，末尾既有 tag 同步会把它改写为主代理 tag。
- 插入位置：组目标规则不另起生成通道，仍在同一用户规则循环内生成；显式规则经
  `RouteRulePriority.order` 天然排在内置站点分流（fallback 层）之前，clash_mode
  全局/直连规则与顶部 DNS 接管规则（`topRouteRules`）在其之前。JSON 编辑器门：
  配置含 `rules` 数组/`type=logical`/`invert`，或全局/当前节点自定义配置非空
  （可能改写 `route.rules`）时，表单拒绝设置组目标。
- 互斥：组目标存在时节点出站必须为 0 且动作为路由动作；直连/拦截/非路由动作与
  组目标同存同样拒绝保存并定位到策略组字段。
- 删除保护：上游的“出站分区事务”在 wanBox 落为 `GroupManager.deleteGroup` 的
  数据库事务——同一事务内先查引用再删行，检查失败则一行不动；两处删除入口
  （单删、多删）与组设置页删除确认共用该保护，阻断对话框标题为组名、可跳转到
  首条引用规则。单删路径先快照选中状态再删，阻断时不迁移选中节点/分组。
- 改名：不需要单独阻断。引用按稳定 ID 解析，tag 在生成时由当前组名重算，已提交
  的改名本身不使引用失效；组名改空白在组设置保存时已有非空回退（`My group` /
  `Subscription #…`），落不到生成层。编辑器草稿改名只改 outbounds 分区，不改规则引用。
- 组清空（`clearGroup` 只删节点、留组）不在本批保护内：组变空后 selector 建不出，
  引用规则按缺失出站处理（启动前 modal 先告警，生成时提示后丢弃），Fail-Closed。
- 组名 trim 后重名是既有缺口（组设置保存无全局重名校验）：重名组的 selector 建不出，
  引用规则同样按缺失出站处理；本批不另立重名校验。
- 备份：JSON 备份自动携带新字段，旧备份缺字段读为 NULL；恢复时校验组目标在备份
  分组（或仅恢复规则时在目标设备分组）内，否则拒绝写入。旧版 Parcel 规则条目
  （`decodeLegacyParcel`）的跨版本兼容须由 CI 的 instrumented 备份测试确认，
  本地无 Android 运行时无法验证。

## 7. N7 热更新契约测试（B8，只借测试方法）

### 落法

- 借鉴上游 `tests/customize_hot_update_test.sh` 的测法，不复制脚本原文：
  1. **fixture 双目录**：在临时目录搭 live（当前模块）与 staging（待安装包）两套完整 fixture
     （module.json、inbound.json、singbox config、本地/远程规则、cache.db、自定义状态、Catalog、
     日志、telemetry、锁文件），内容全部虚构；
  2. **桩外部命令**：桩掉安装器外部依赖（`ui_print`/`su`/`pidof` 等 shell 侧；Go 侧用既有接缝
     `installRunning`/`installRouteGuard`/`installStart`/`installCanaryCheck`），被测函数直接取自生产代码；
  3. **逐模式断言快照内容**：三种安装模式（保留全数据 / 仅节点与订阅 / 全新）分别断言 staging 与
     live 在提交前后的精确内容（哪些文件来自当前、哪些来自安装包、哪些被清理）；
  4. **失败恢复**：快照失败、重命名失败、服务停止失败、配置校验失败、canary 失败时，断言旧模块
     与用户数据原样保留、失败事件已记录、可由下次开机流程继续处理。
- wanBox 映射：热更新真正逻辑在 Go（`rootmodule/hot_update.go`、`install.go`、`install_handoff.go`），
  故主战场是 Go 测试（在 `hot_update_test.go` / `install_test.go` 既有 fixture 风格上补缺口）；
  另加一层薄 shell 测试，只覆盖 `rootmodule/package/customize.sh` 与 `installer-options.sh` 的
  委托语义（校验失败→abort、不替换当前模块；`schedule-install` 失败→abort；成功→提示后台更新），
  不把 Go 逻辑重测一遍。
- **红线**：B8 只补测试；若测试暴露实现缺陷，实施者 MUST 停下、单独上报（缺陷描述、复现测试、
  影响范围），不得在 B8 内擅自改实现。缺陷修复另立 change。

### B8 落点（2026-11-18，只补测试）

- 缺口盘点（详见 B8 提交说明）：上游 20 项中，安装选择/音量键/输出描述符由既有
  `test_installer_options.py` 与 `volume_key_test.go` 覆盖；标题间距、chcon 权限、
  原生 Catalog 锁在 wanBox 无对应物，不适用。新增 `hot_update_contract_test.go`
  （13 项 Go 测试）与 `test_customize_delegate.py`（10 项 shell 委托测试），补齐：
  三种模式逐模式快照精确内容与 `install-data.json` 记账、选择生命周期（挂起 Apply、
  伪造确认拒绝、确认后放行）、data-reset 挂起、损坏快照先败、缺失快照下 preserve 仍
  装机（与上游有意的映射差异）、安装后文件权限、守卫释放失败仍提交、回滚重启失败
  保留守卫、接管等待失败记 `module_hot_update_deferred`、校验失败无暂存残留、
  数据锁 inode 跨切换保留、管理器 APK 缺失/损坏/平台缺失。
- 未以测试覆盖（无故障注入手段或设备绑定，见提交说明）：目录重命名失败分支、
  `syncDir`/`retireStaging` 失败分支、备份删除失败分支、锁争用 65 秒重试、
  `FinishInstallMode` 的安装器身份核对（需 `/data/adb` 布局与真实安装器进程）。
- 红测暴露缺陷 1 项（见 B8 提交说明）：备份创建失败时守卫泄漏，
  `TestHotInstallBackupCreationFailureReleasesGuard` 保持红测，修复另立 change。

## 8. N8 可行性评估（只评估，不实现）

结论见 proposal.md。补充设计要点，供下一轮立项时直接取用：

- `socket_assign` 相对 `cgroup` 的收益是跟随默认出口、不依赖 cgroup v2；代价是需要更多内核能力
  （网络命名空间、veth、透明 socket、策略路由），且为实验性机制。
- 若立项，须先有 `ebpf status` 式预检（`configured`/`all` 两种范围；普通输出为可读结论，
  原始 JSON 仅在显式 `--raw` 时返回；预检通过不得表述为实际接管成功），并复用 N2 的脱敏规则
  输出诊断；预检诊断与 N4 共享路径的开启门禁共用同一实现。
- 体积：`socket_assign` 是同一入站的选项值，预计不新增体积；立项时 MUST 由
  `buildScript/size-verify.sh` 实测 APK+模块 zip 合计 26 MiB 预算，不得以推断代替。
- 内核版本：不得写死最低内核版本号；能力以实际预检为准。

## 9. 自家约束与上游冲突时的取舍

| 自家约束 | 上游做法 | 取舍 |
|---|---|---|
| URLTest 间隔 ≥10 秒 | 上游 URLTest interval 由原生配置决定，无 10 秒下限 | 以自家为准：组级 URLTest interval 仍 MUST ≥10 秒；N6/N3 不得引入更小间隔 |
| 空闲超时 ≥30 分钟 | 上游无对应下限 | 以自家为准：URLTest idle timeout 仍 MUST ≥30 分钟 |
| 切换不中断连接 | 上游模式切换经 API 切换并回读，参数不变不重载；节点切换经 Clash API | 一致，无冲突；N3 的 `direct` 动作 MUST 沿用此语义，参数不变时不得重载 |
| Fail-Closed 红线 | 上游：预检失败明确报错不自动切换；快照失败保留 `modules_update` 交回下次开机；损坏 JSON 省略原文 | 一致，无冲突；wanBox 另有重载失败恢复上一 generation、清理不确定时 Fail-Closed，本轮全部保留 |
| default 保真 | 上游：新安装默认 `socket_assign`；旧 `tc` 不自动转换；关闭 Wi-Fi 策略不得清空名单或改写模式 | 以自家为准：wanBox eBPF 本机默认仍为 `cgroup`（不跟随上游改 `socket_assign`）；N3 合并时关闭不得清空 F1 规则；N4/N5/N6 全部默认关闭/默认不启用 |
| 版本 CAS 与恢复校验链 | 上游：`config read/apply/validate --revision`，同分区冲突返回 `config.conflict`；Catalog/配置回滚用同目录临时文件+ journal，恢复成功才删备份 | 一致，无冲突；N1/N6 的编辑提交 MUST 复用 wanBox 现有 CandidateSnapshot + expectedRevision + 绕缓存确认链，不得另造 CAS |
| 备份兼容 | 上游：安装不读取/转换旧格式，旧格式用户只能选仅保留节点或全新安装 | 以自家为准：wanBox 用户备份（PortableBackup v2）兼容矩阵保持不变；N5 迁移不得破坏备份往返 |

## 10. 新增 UI 文案（默认语言 + 简体中文）

默认语言为英文；简体中文同步。其他语言回退默认资源。

| 场景 | 默认语言（en） | 简体中文（zh-CN） |
|---|---|---|
| 设置页导出诊断包入口 | Export diagnostics package | 导出诊断包 |
| 导出前确认说明 | The package contains desensitized runtime config, logs and status. Subscription URLs, node credentials, server addresses, SSIDs and MAC addresses are redacted. Routing domains are kept. Review before sharing. | 诊断包包含脱敏后的运行时配置、日志与状态。订阅地址、节点凭据、服务器地址、Wi-Fi 名称与 MAC 地址已脱敏；路由域名会保留。请检查后再对外分享。 |
| 导出成功 | Diagnostics package exported: {path} ({size}) | 诊断包已导出：{path}（{size}） |
| 导出失败 | Failed to export diagnostics package: {reason} | 导出诊断包失败：{reason} |
| 停核后节点列表提示 | Service stopped. Nodes, subscriptions and rules can still be browsed, edited and speed-tested. | 服务已停止。节点、订阅与规则仍可浏览、编辑与测速。 |
| 按网络规则页标题 | Network rules | 按网络规则 |
| 按网络规则说明 | Match the current network by Wi-Fi name, gateway or address, then suspend the core, switch to Direct mode, or keep proxying. Only the first matching rule applies. | 按 Wi-Fi 名称、网关或地址匹配当前网络，然后挂起核心、切换为直连模式或保持代理。仅首条匹配规则生效。 |
| 动作：挂起 | Suspend core on this network | 在此网络挂起核心 |
| 动作：直连模式 | Use Direct mode on this network | 在此网络使用直连模式 |
| 动作：保持代理 | Keep proxying on this network | 在此网络保持代理 |
| 共享网络接管开关 | Intercept tethered network traffic (hotspot / USB sharing) | 接管共享网络流量（热点 / USB 共享） |
| 共享接管说明 | Only the eBPF shared path can intercept tethered traffic. This does not create a hotspot, DHCP, NAT or IPv6 RA. Disabled by default. | 仅 eBPF 共享路径可接管共享流量。本开关不创建热点、DHCP、NAT 或 IPv6 RA。默认关闭。 |
| 共享接口缺失 | Shared interception is on, but no downstream interface is configured. | 已开启共享接管，但未配置下游接口。 |
| 分应用按用户标题 | Per-app proxy by Android user | 按 Android 用户分应用代理 |
| 分应用分身说明 | The same app installed for different users or clones is listed separately. | 同一应用在不同用户或分身下分别列出。 |
| 路由规则目标：策略组 | Policy group | 策略组 |
| 路由规则目标策略组缺失 | This rule targets a policy group that no longer exists. | 该规则指向的策略组已不存在。 |
| 热更新测试说明（开发者可见） | Hot-update contract tests only cover installer behaviour; implementation changes need a separate change. | 热更新契约测试仅覆盖安装器行为；改实现需另立 change。 |
