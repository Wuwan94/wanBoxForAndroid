# Android Application — port-ownbox-26-11-8-20 Delta

## Purpose

移植 OwnBox v26.11.8.5–v26.11.8.20-preview 的 Android 侧改动（O2 部分、O5、O6 应用层、O7 UI、O9 核查项）。
Go 侧改动见 `libcore-integration` delta。O8 只做评估，不在本 delta 落实现要求。

## Requirements

### Requirement: 测速刷新间隔未设置时默认 1000ms

`speedInterval` 未读取到用户设置时 MUST 默认 1000ms，与设置页一致，MUST NOT 回退为 0ms
导致高频无效测速开销。

- **GIVEN** 用户从未设置过测速刷新间隔
- **WHEN** 管理器读取 `speedInterval`
- **THEN** 读到 1000，且测速刷新按 1000ms 节奏执行

### Requirement: 纯节点切换走热切换通道，配置类变更仍走配置重建

纯节点切换 MUST 独立走热切换通道（`switchProfileService` 等价物），不得触发完整配置重建；
路由规则、代理模式及分组设置等配置类变更 MUST 严格触发配置重建生效。
热切换 MUST NOT 中断现有连接。

- **GIVEN** 服务已连接，当前节点为 A
- **WHEN** 用户在 SwitchActivity 或快捷切换里把节点切到 B（未改任何配置项）
- **THEN** 只做节点热切换，现有连接不断，B 在秒级接管新连接
- **GIVEN** 用户改了路由规则或代理模式
- **WHEN** 保存并应用
- **THEN** 触发配置重建，而不是热切换

### Requirement: 订阅自动更新间隔预设、自定义弹窗与人性化摘要

订阅设置 MUST 提供常用间隔单选预设（15 分钟、30 分钟、1 小时、3 小时、6 小时、12 小时、24 小时/1 天），
并支持自定义弹窗：纯数字软键盘、「分钟 / 小时 / 天」单位切换、明确的有效性校验与最小 15 分钟限制提示。
设置项摘要 MUST 人性化展示（例如“每 24 小时 (1天)”、“每 12 小时”），关闭自动更新时 MUST 明确显示“已关闭自动更新”。
`autoUpdateDelay` 的分钟值语义 MUST 不变（默认 360）。

- **GIVEN** 用户打开订阅自动更新设置
- **WHEN** 选择“24 小时”预设
- **THEN** 摘要显示“每 24 小时 (1天)”，且 `autoUpdateDelay` 存为 1440
- **GIVEN** 用户在自定义弹窗输入 10 分钟
- **WHEN** 确认
- **THEN** 提示最小为 15 分钟并拒绝保存
- **GIVEN** 用户关闭自动更新
- **WHEN** 返回订阅设置页
- **THEN** 摘要显示“已关闭自动更新”

### Requirement: 订阅到期补更触发

进入订阅分组页及代理连接成功时，管理器 MUST 检查到期订阅并触发补更。
补更 MUST 尊重既有 per-group 失败退避与 `updateWhenConnectedOnly`；
未到期的订阅 MUST NOT 被提前更新。

- **GIVEN** 某订阅已到期且未处于失败退避期
- **WHEN** 用户进入订阅分组页或代理连接成功
- **THEN** 触发该订阅的更新
- **GIVEN** 某订阅处于失败退避期
- **WHEN** 触发补更检查
- **THEN** 跳过该订阅，不打断退避

### Requirement: 订阅后台调度收归主进程（待用户拍板后才实施）

若用户拍板迁移，订阅后台调度 MUST 收归应用主进程原生 `WorkManager` 管理，
不得继续用 `:bg` 跨进程 `RemoteWorkManager`；
迁移 MUST 先解决 `UpdateTask` 对 `:bg` 维护的 `DataStore.serviceState` 的依赖，
不得在主进程读不到连接状态时误判 `updateWhenConnectedOnly`。
初始延迟 MUST 按各订阅到期时间精确计算，不得截断为 60 秒；
冷启动时 MUST 在后台协程中校验并恢复定时调度任务。

- **GIVEN** 用户拍板迁移且手机重启后首次打开应用
- **WHEN** 应用冷启动完成
- **THEN** 定时调度任务被校验并恢复，到期订阅仍能按时更新
- **GIVEN** 某订阅 3 小时后到期
- **WHEN** 调度任务入队
- **THEN** 初始延迟约为 3 小时，不得被截断为 60 秒

### Requirement: 应用路由生成层集中式系统组件排除名单

应用路由生成层 MUST 维护集中式系统组件排除名单（`SystemExcludedPackages`，唯一来源），至少包含系统下载器
（`com.android.providers.downloads`、`com.android.providers.downloads.ui`）、媒体存储、
网络栈及 Captive Portal 门户组件；名单 MUST NOT 用于改写用户显式规则——用户规则的包名 MUST 原样生成
（修红 #182：生成层过滤会静默丢弃显式规则，使其流量改走默认路由，反而可能被分流进代理，与排除本意相反）；
名单的适用层是智能分流分类（上游 `SmartRouteRules` 的分类派生绑定；wanBox 暂无该层，名单先行作为唯一来源），
与 `ProfileManager` 既有下载器排除（仅剔两个下载器包名）合并去重，不得重复造两份名单。

- **GIVEN** 系统下载器发起下载
- **WHEN** 应用层规则生成并匹配
- **THEN** 生成结果 MUST NOT 包含把下载器强绑进代理的分类派生绑定；用户显式规则原样保留

### Requirement: 规则集缓存 MUST 按实际规则条数校验

`.srs` 规则集缓存 MUST 前后端同步校验实际规则条数；0 规则占位文件 MUST NOT 被当作有效缓存，
MUST 重新拉取或明确报错。远程规则集定时刷新时 MUST 跳过 `local:` 条目。
`local:` 本地规则集支持以 B3 核查结论为准：若规则集模型支持，MUST 支持连字符与旧下划线命名缓存、
`local:` 规则校验与多目录解析；若不支持，MUST 在评估里写明理由并跳过，不得硬做。

- **GIVEN** 本地存在 0 规则的 `.srs` 占位缓存
- **WHEN** 规则集被加载或刷新
- **THEN** 该缓存被判定无效并重新拉取，不得静默当作有效规则集使用

### Requirement: 策略组混搭来源与组隔离

策略组 MUST 支持“分组（含名称正则过滤）+ 散装节点”混搭来源，自动去重，并在编辑页实时展示
实际匹配节点数量与正则语法错误提示。每个策略组 MUST 独立隔离出站与前置/落地代理链，
多策略组共享成员时 MUST NOT 串线。

- **GIVEN** 策略组选了一个分组（正则 `^HK`）并叠加 2 个散装节点，其中 1 个已在分组内
- **WHEN** 保存并生成配置
- **THEN** 成员去重后无重复出站，匹配数量显示正确，且该组的出站与代理链与其他组隔离
- **GIVEN** 正则写错
- **WHEN** 编辑页实时校验
- **THEN** 显示正则语法错误提示并阻止保存

（B7a 已落地：`BalancerMemberResolver`（`fmt/BalancerMemberResolver.kt`）统一解析，
分组在前、散装在后、按 id 去重，正则只过滤分组来源；设置页实时计数 + 保存拦截；
组隔离分两层——策略组级 `(balancerId, memberId)` 隔离缓存与链级 `isolationKey`
（出口跳点不进 `globalOutbounds` 共享、中间跳点与出口 tag 加组后缀）；
落地链同样隔离构建。副作用：隔离链不再进入 `tagMap.values`，顶层 selector/urltest
成员列表不再含策略组内部成员链。）

### Requirement: 策略组严格手动锁定

策略组成员被手动锁定后，断连或超时 MUST NOT 自动切换，直到用户点击「恢复自动」解锁。
锁定期间的失败 MUST 计入统计，MUST NOT 触发自动切换。锁定成员不可用时，主页横幅 MUST 红字高亮提示。

- **GIVEN** 用户手动锁定节点 A 且 A 已断连
- **WHEN** 经过一次检查间隔
- **THEN** 仍锁定在 A，不自动切到 B，横幅红字提示“锁定节点不可用”
- **GIVEN** 用户点击「恢复自动」
- **WHEN** 下一次检查完成
- **THEN** 恢复自动选择，可切到健康节点

（B7b 已落地：`fmt/BalancerManualLock.kt`（状态机 + 编解码 +
`lockedMemberTag` + 快照 tag↔id 双向解析）；锁存 `DataStore.balancerManualLock`
（configurationStore），不进 `BalancerBean` 记录、不占 v7；
`ConfigBuilder.buildChain` 锁定时生成 selector（`default` = 锁定成员），
锁定成员不在列表里时回落自动生成、锁保留；selector 默认
`interrupt_exist_connections = false`，不断现有连接，本批不提供断开开关；
锁定期间的失败由统计页测速与 `BalancerMemberStats` 计入，配置层不自动切换；
统计页是锁定的唯一入口，锁定/解锁后 `SagerNet.reloadService()` 重建配置生效。）

### Requirement: 策略统计页与主页横幅

开启策略组连接时，分组栏下方 MUST 自动显示策略组状态横幅（当前策略模式、正在使用/锁定节点及实时延迟）。
点击横幅 MUST 进入策略统计页，展示各成员状态/报错信息，并提供一键“立即全部检查”。
统计页数据 MUST 来自 Root 快照/Clash API 等既有通道，不得为统计另开核心直连。

- **GIVEN** 策略组已连接
- **WHEN** 用户查看主页
- **THEN** 横幅显示当前模式、承载节点与实时延迟
- **GIVEN** 用户在统计页点击“立即全部检查”
- **WHEN** 检查完成
- **THEN** 各成员状态与报错信息刷新展示

（B7b 已落地：统计页即既有 `PolicyGroupsActivity`（Root 快照 +
Clash API + `node tags`，未另开核心直连）——成员行展示失败数/最近报错，
“整组测速”按钮改为一键“立即全部检查”，每次结果记入进程内
`BalancerMemberStats`；主页横幅在 `DashboardFragment` 当前节点卡片下方，
`ui/BalancerBanner.kt` 纯模型 + 30 秒一刷（回首页即刷），点横幅进统计页；
锁定成员不可用（不在快照成员列表里或有连续失败）时横幅红字提示。
锁只对 `BalancerBean` 策略组实体生效，顶层 proxy selector 与编辑器组无锁定入口。）

### Requirement: 「按优先级顺序」多层级调度

「按优先级顺序」模式 MUST 支持按服务器分组与节点名称正则定义层级（层级 1、层级 2…），
未匹配节点自动归入保底层级；层级支持上移/下移与删除。层内 MUST 按实测延迟与切换容差择优；
高顺位层全数不可用时 MUST 立即回退下一层；恢复后 MUST 按双重门槛（检查次数 + 无失败持续时间）
受控回切。启用流量分配时 MUST 只在当前最低健康层级内分散流量。
「最快最稳」模式 MUST 隐藏「添加层级」、层级行与节点优先级列表。

- **GIVEN** 层级 1 全部节点不可用，层级 2 有健康节点
- **WHEN** 下一次检查完成
- **THEN** 流量回退到层级 2 的最优节点
- **GIVEN** 层级 1 恢复可用但未达到双重回切门槛
- **WHEN** 检查完成
- **THEN** 不回切，仍留在层级 2
- **GIVEN** 用户切到「最快最稳」模式
- **WHEN** 打开策略组设置页
- **THEN** 不显示添加层级、层级行与节点优先级列表

（B7c 已落地：「按优先级顺序」为新增策略值 `STRATEGY_PRIORITY_ORDER = "priorityOrder"`，
与既有模式并存；「最快最稳」=`STRATEGY_LEAST_PING`（本批定义的映射）。
层级规则存 `BalancerBean.tierRules`（单条正则 URL 安全 Base64 无填充编码，
`include:exclude`、`;` 分隔，上限 10 条，脏条目解码跳过）；Kotlin 侧
`BalancerTierScheduler` 负责定层级/层内择优（延迟 + 容差，复用 Go `selectLocked`
语义）/回切双重门（连续健康检查 ≥3 次 + 无失败 ≥60 秒）/流量只在当前最低
健康层内分散；Go 侧 `libcore/protocol/urltest/outbound.go` 强制执行——
Kotlin 生成嵌套 urltest：每层一个内层 urltest（同间隔/容差/空闲），外层 urltest
对层级 tag（`"<balancerTag> [T<n>]"` / `"<balancerTag> [FB]"`）择优；单层不嵌套。
层级解析仅当全部成员 tag 都带层级后缀且 ≥2 个不同层级时激活，避免普通节点名
误触发。回切不断现有连接（`interrupt_exist_connections = false`），探测间隔
仍钳制 ≥10 秒，不为层级检查新增更密的探测。）

### Requirement: 节点优先级二级页与内嵌节点顺序列表

策略组设置页 MUST 提供内嵌式节点顺序列表（位于「指定节点」下方、「高级」上方），支持紧凑编号
（`1, 2, 3...`）、拖拽调序、快速移除及点击替换节点；不得在页面底部另挂外挂列表。
「节点优先级」MUST 收纳为独立二级页面，摘要实时显示节点数量与前 3 个节点预览；
二级页 MUST 支持顺位标号、拖动调序、删除、点击替换、添加/多选节点与「按当前延迟排序」
（配置有层级时按层级分组内延迟排优）；返回 MUST 自动生效。

- **GIVEN** 策略组有 5 个指定节点
- **WHEN** 用户在内嵌列表拖动第 5 个节点到第 1 位
- **THEN** 顺序即时生效，编号变为 `1..5` 且与实际选路顺序一致
- **GIVEN** 用户打开「节点优先级」二级页
- **WHEN** 点击「按当前延迟排序」
- **THEN** 节点按实测延迟升序排列（有层级时层内排序），返回后生效

（B7c 已落地：设置页内嵌节点顺序列表位于「指定节点」下方、「高级」上方（`updatePrioritySummary`，
摘要“数量 + 前 3 名”）；二级页为新 `BalancerPriorityActivity`（注册于 AndroidManifest，
`configChanges="uiMode"`），草稿态编辑、返回时经结果 intent 回写 DataStore 草稿并自动生效；
支持紧凑编号 `1, 2, 3...`（`formatPriorityNumber`）、拖拽（ItemTouchHelper）、
快速移除、点击替换（经 `ProfileSelectActivity`）、多选添加与按延迟排序（有层级时层内排序，
无延迟排最后、稳定排序）。移除语义：散装成员同时从 `proxies` 移除（真正离组），
分组来源成员记入 `BalancerBean.priorityExcluded`（仅优先级模式过滤成员，切回其他
策略即恢复）；新成员的层级按名称正则重算、不继承被替换成员的层级。）

### Requirement: BalancerBean 记录升级与迁移

`BalancerBean` 引入混搭来源或层级字段时，记录版本 MUST 递增（v5→v6 起），
并 MUST 保留 `v1–v5` 历史数据的读取与迁移；新字段默认值 MUST NOT 覆盖用户已保存的 `0`（不限）等显式值；
导出/备份 MUST 可降级，未知字段不得静默丢弃。

- **GIVEN** 旧版 v5 策略组数据（interval 300、tolerance 300）
- **WHEN** 升级到新版并打开策略组设置
- **THEN** 旧字段原值保留，新字段取安全默认值，可正常保存与建连

（B7a 已落地：v6 字段形状由本批定——`TYPE_MIXED = 2` + `maxMembers`（0 = 不限），
不照抄上游 autoselect 形状的 v6；`serialize` 写 `writeInt(6)`，`deserialize`
保留 v1–v5 分支、`maxMembers` 缺失时取 0；`initializeDefaultValues` 只归一化
负值、不覆盖显式 0；`serializeV5` / `downgradeToV5Bytes` 提供 v5 降级导出。
8.15 同类问题核查：既有字段无同类覆盖缺陷，见主规范对应 requirement。）

（B7c 已落地：记录升至 v7（`serialize` 写 `writeInt(7)`），新增 `tierRules`
（String，编码后规则表）、`priorityOrder`（`List<Long>`，节点优先级顺序）、
`priorityExcluded`（`List<Long>`，仅优先级模式排除的分组来源成员 id）。
`v1–v6` 历史数据 MUST 能读、能升（v7 字段缺失时取 `""` / 空列表）；
`initializeDefaultValues` 只做 v7 字段空值守卫、不覆盖已有值；
`serializeV6` / `downgradeToV6Bytes` 提供 v6 降级导出（v7 字段丢弃，
`serializeV5` / `downgradeToV5Bytes` 链式复用、仍可用）。
层级规则与优先级顺序的草稿态另存 DataStore（`balancerTierRules` /
`balancerPriorityOrder` / `balancerPriorityExcluded`，profileCacheStore），
保存时才写入 `BalancerBean`。）

### Requirement: leastPing 组冷启动按缓存延迟预排序（B1 已落地）

（补记：本条在 B1 实施期间已写入主规范 `openspec/specs/android-application/spec.md`，delta 只记了 Go 侧一半，Kotlin 侧漏记，2026-11-18 发布后回填；正文与主规范一致。）

配置生成时，leastPing 策略组的成员顺序 MUST 按缓存延迟预排序：健康有延迟
（status 1 且 ping > 0）在前并按延迟升序、有延迟但未确认健康其次、未测再次、
已知失败（status 2/3）在后；使内核首包不盲连列表首位。排序只改变生成配置里的
成员顺序，不改变任何持久化数据；failover 等其他策略 MUST 保持原有成员顺序。

- **GIVEN** leastPing 组内节点 A 健康延迟 80ms、节点 B 健康延迟 900ms、节点 C 未测、节点 D 已知失败
- **WHEN** 生成配置
- **THEN** 成员顺序为 A、B、C、D（失败在后）
- **GIVEN** failover 策略组
- **WHEN** 生成配置
- **THEN** 成员顺序保持原有顺序，不做冷启动预排序

### Requirement: leastPing 冷启动预排序是纯顺序，过期判定在 Go 侧（B4 已落地）

（补记：本条在 B4 实施期间已写入主规范 `openspec/specs/android-application/spec.md`，delta 漏记，2026-11-18 发布后回填；正文与主规范一致。）

`sortBalancerMembersForColdStart` 只做成员顺序（健康有延迟在前、失败在后），
MUST NOT 判断种子新鲜度——`ProxyEntity` 无探针时间戳字段，Kotlin 侧无法判定
缓存延迟是否过期；种子过期判定（超过 `3*interval`）与 idle 后重选在 Go 侧
`URLTestGroup` 强制执行。

- **GIVEN** 健康成员缓存延迟极大（可能是过期缓存）
- **WHEN** 生成 leastPing 组配置
- **THEN** 仍按延迟排在未测成员之前，过期判定不在此处做
