# 设计：移植 OwnBox v26.11.8.5–v26.11.8.20-preview

## 架构总览

wanBox 与 OwnBox 同源（nb4a / SagerNet），但已分叉为 Root-only 架构：

- 配置生成在管理器 APK（`ConfigBuilder.kt`），执行在 Root 模块核心（`libcore` + `rootmodule`）；
  Android 侧不得另造与核心不同步的出站模型，校验权威在 Root/Core。
- 策略组仍是 sing-box 原生 `urltest` / `loadbalance` 出站，没有 OwnBox 自研的 `autoselect` 出站。
  本 change 不引入 autoselect 出站（O8 结论：不采）；O1/O3/O7 的 Go 侧改动只做在既有
  `libcore/protocol/urltest/`、`libcore/protocol/loadbalance/` 与配置生成层。
- 持久化模型：`BalancerBean` 记录版本 wanBox 为 v5，OwnBox 为 v8；
  任何新字段都必须带显式记录升级与 v5 历史数据迁移设计，不得照抄上游字段形状。
- 构建与验证走 GitHub Actions；本地只做静态检查（无 Go/Android 编译环境）。

## 自家约束 vs 上游冲突：取舍表

wanBox 自家约束与上游冲突时，以自家为准：

| 约束 | wanBox 现状（证据） | 上游做法 | 取舍 |
|---|---|---|---|
| URLTest 检测间隔 ≥10 秒 | `ConfigBuilder.kt:168`：`intervalSec.coerceAtLeast(10L)`；`BalancerBean.interval` 默认 300 | autoselect 的 `checkInterval` 最小钳制 5 秒（`options.go` normalized：`< 5s → 5s`）；8.18 删 15 秒追踪定时器后“严格按用户间隔” | 以自家为准：任何新增探测（O1 流式就绪、O3 重测、O7 层级检查）MUST NOT 把有效间隔压到 10 秒以下；种子预选 MUST NOT 重置探测间隔时钟 |
| 空闲超时 ≥30 分钟 | `DataStore.kt:392,437-438`：组 idle timeout 默认 `"30m"` | autoselect 无等价 idle 约束；8.20 流式就绪会更早切换 | 以自家为准：O1 流式切换 MUST 只在组处于活跃态（未 idle）时触发择优；idle 后的首次连接 MUST 重新按缓存延迟预选，不得沿用过期选择 |
| 切换不中断现有连接 | `ConfigBuilder.kt`：`interruptExist = false`；主规范 Fail-Closed 要求 | autoselect 的 `InterruptExistConnections` 可开；8.18 称「切换时断开连接统一控制普通/故障/回切」 | 以自家为准：默认 MUST 保持 `interrupt_exist_connections = false`；O7 的严格手动锁定与层级回切 MUST NOT 默认断开现有连接；断开只在用户显式开启对应开关时发生 |
| Fail-Closed 红线 | 主规范：核心重载失败 MUST 恢复上一有效 generation 并报告失败；无法确认安全 MUST 停止并 Fail-Closed，不得假报成功 | 上游 8.13 的 Dial 黑洞超时、8.18 的 1 次失败即切换都偏向“快速切走” | 以自家为准：O1/O3 的快速转移 MUST 只影响新连接选路；核心重载、配置生成失败时 MUST 走既有 Fail-Closed 路径，不得为“秒连”跳过校验 |
| `default` 保真 | 主规范：默认配置/默认规则不得被迁移或编辑静默改写 | OwnBox 8.15 修复 `BalancerBean.initializeDefaultValues()` 覆盖 `0`（不限）的问题 | 以自家为准：B7a 须先核查 wanBox 的 `BalancerBean` 默认值回填是否存在同类覆盖（`0` / 空值被默认值吃掉），存在才修 |
| 版本 CAS 与恢复校验链 | 主规范：版本化双向备份兼容、迁移备份优先、可检测可恢复 | OwnBox BalancerBean v6→v7→v8 各带迁移，但字段形状与 autoselect 绑定 | 以自家为准：记录升级 MUST 带版本号 CAS（写入 `writeInt(N)`）与旧版回退路径；v5 数据 MUST 能读、能升、能降级导出，不得静默丢字段 |

## O1 约束对表（B4 前置，逐条）

O1 上游三条（8.20）对表 wanBox 自家约束：

1. **配置生成注入各节点最新缓存延迟、第 0 毫秒预选最佳节点**：
   上游在 `buildAutoSelectOutbound` 里把 `member.ping` 注入 `initial_delays`，
   Go 侧 `NewAutoSelect` 在初始化时 `recordSample` 并 `evaluate`。
   wanBox 适配：`ConfigBuilder` 生成 leastPing 组时按 `HistoryStorage` 缓存延迟对
   `memberTags` 预排序（已知健康低延迟在前、失败节点在后），`URLTestGroup` 启动时以缓存延迟
   确定初始 `selectedOutbound`，不得等待首轮整组探针。
   约束：种子 MUST 只读缓存，不得触发新探测、不得改写 `history.Time`（否则等于续期旧时间戳，
   与 O3 修复直接冲突）；种子延迟过期（超过 `3 * interval`）MUST 视为无种子。
   （B4 落地说明：Go 侧 HistoryStorage 每核新建、冷启动必为空，故不在
   `NewURLTestGroup` 里播种；跨重启存活的缓存是应用层 DB，B1 的
   `ConfigBuilder` 预排序即 0 毫秒预选的载体；Go 侧只做过期判定与 idle 后
   重选，见 B4 实施记录。）
2. **并发测速优质节点流式就绪立即择优**：
   上游在 `runProbeBatch` 里每成功一个节点立即 `recordProbeProgress` + `evaluateWithTransition`。
   wanBox 适配：`URLTestOutbounds` 批量探针里单个成员成功即写回 history 并通知 `NotifyUpdated`，
   不必等整批 6 并发跑完；同轮更优节点按容差（`tolerance`）平滑切换。
   约束：流式就绪 MUST NOT 缩短用户设定的检查间隔；倒计时 MUST 按整轮结束重置，不得按单个节点就绪重置。
3. **多候选单次建连超时 5s→2s**：
   上游把 autoselect `DialContext` 的 `probeTimeout`（5 秒）换成 `defaultFastFailoverTimeout`（2 秒）。
   wanBox 现状：`URLTest.DialContext` 对候选**无超时**逐个尝试；批量探针单节点超时为 3 秒。
   约束：2 秒取值**须用户拍板**（见 proposal 末节）。无论取值如何，单步超时 MUST 只掐断当次尝试，
   MUST 保留 `interrupt_exist_connections = false`，现有连接 MUST NOT 被打断；
   超时后的故障转移 MUST 走 O3 的失败计数（计入连续失败），不得静默丢弃失败证据。
   （B4 落地说明：单步超时取 **3 秒**，用户 2026-11-17 已拍板，与 wanBox 现有
   探针超时对齐；B1 已在 `dialOne`/`listenOne` 落地，B4 只做 UDP 镜像核查。）

## O8 评估结论：不采（本轮），只做评估 + 原型验证

评估对象：OwnBox 8.14 引入、8.15 修 bypass、8.16 加层级、8.17 加优先级页、8.18 收紧并删项的
`libcore/protocol/autoselect/` 引擎（被动健康评分、eTLD+1 站点记忆、指数退避冷却、分级探测），
约 8.14 单版 +6951/−1153 行中的 Go 侧主体。

不采理由（按权重）：

1. **架构不合**：wanBox 策略组是 sing-box 原生 urltest/loadbalance 出站 + Root 核心执行；
   引入自研出站类型意味着配置生成、Root 快照、Clash API、诊断与回滚链路全要新增一个类型，
   与“校验权威在 Root/Core、不得在 APK 另造出站模型”冲突。
2. **上游自身未收敛**：8.18 又删了该引擎的多样本均值/中位数平滑、全局疑似异常冷却、8 节点上限、
   15 秒追踪定时器与 600 秒扫描定时器，并把被动学习收敛为仅站点记忆 + 单站避让；
   8.20 又改了种子与超时。移植一个仍在删改的引擎，等于把上游的试错成本搬进 wanBox。
3. **需求已被更小改动覆盖**：被动故障感知 ≈ O3（连续失败清史 + 单步超时 + 失败计数）；
   站点记忆 ≈ O7 统计页与手动锁定可提供的“上次可用”信息；
   分级探测 ≈ O1 流式就绪 + 既有 300 秒检查间隔。
4. **风险/收益比**：完整移植约等于重做策略组核心，回归面覆盖全部策略组用户；
   而 O3+O1+O7 的组合已能拿到冷启动、死节点、手动锁定这三项用户可感知的收益。

原型验证（本 change 唯一允许的 O8 工作，不进入实现批次）：

- 在隔离 scratch 分支（不合入 `port/ownbox-26.11.8.20`）用 mock `OutboundManager` 跑通
  `scorer`/`probe` 的最小闭环：种子延迟预选、单次失败后备选切换、eTLD+1 站点记忆命中；
  只验证“机制是否如上游所述工作”，不验证“是否值得接入 wanBox”。
- 原型结论（机制成立/不成立、与 wanBox URLTest 的等价点、接入成本量级）写回本 change 的
  `design.md` 附录与 `tasks.md` 第 0 节，不得据此直接开实现批次；是否重估须用户另行拍板。

## O7 拆分设计

- **B7a（混搭来源 + 组隔离）**：`BalancerBean` 新增“分组（含名称正则）+ 散装节点”混合成员模型，
  成员解析（`BalancerMemberResolver` 等价层）去重并实时计数；每组独立隔离出站与前置/落地代理链，
  多组共享成员不得串线。记录升级设计随 B7a 做：新字段、默认值、v5→vN 迁移、`0`（不限）保真核查。
- **B7b（锁定 + 统计 + 横幅）**：严格手动锁定（断连/超时不自动切，须点「恢复自动」解锁）；
  策略统计页（成员状态/报错、一键全检）与主页横幅（模式/承载节点/延迟；锁定成员不可用时红色高亮）。
  统计页数据 MUST 来自 Root 快照/Clash API 等既有通道，不得为统计另开核心直连。
- **B7c（层级调度 + 优先级页）**：「按优先级顺序」模式：分组/正则定层级（层级 1、层级 2…、保底），
  层内按延迟 + 容差择优，高层全灭回退下一层，恢复后按双重门槛（检查次数 + 无失败持续时间）受控回切；
  流量分配只在当前最低健康层级内分散。节点优先级二级页（编号/拖拽/移除/替换/按延迟排序）与设置页内嵌
  节点顺序列表（O9d 并入此处）。层级规则持久化 MUST 随 BalancerBean 记录升级一并迁移。

## O5 设计要点与风险

- 预设（15/30 分钟、1/3/6/12 小时、24 小时/1 天）+ 自定义弹窗（纯数字键盘、分钟/小时/天单位、
  最小 15 分钟校验）+ 人性化摘要（“每 24 小时 (1天)”/“已关闭自动更新”）照上游 8.6 交互移植，
  存量 `autoUpdateDelay`（分钟，默认 360）语义不变。
- 到期补更触发：进订阅分组页、代理连接成功时检查到期并补更；补更 MUST 尊重既有失败退避
  （`SubscriptionUpdater` 的 per-group backoff）与 `updateWhenConnectedOnly`。
- **主进程迁移风险**：上游把 `RemoteWorkManager`（跨进程 `:bg`）收归主进程 `WorkManager`。
  wanBox 的 `UpdateTask` 依赖 `:bg` 维护的 `DataStore.serviceState`（是否已连接决定是否跳过
  `updateWhenConnectedOnly` 的订阅）；迁到主进程须先解决 `serviceState` 来源（主进程可读的状态源
  或改判据），否则宁可保留 `RemoteWorkManager`、只做弹窗与触发器。**此项须用户拍板**，见 proposal 末节。

## O6 设计要点

- wanBox 无 `classifyApps`，不做“默认不再加载预置包名表”的移植；做的是等价目标：
  在应用路由生成层建立集中式系统组件排除名单（系统下载器、媒体存储、网络栈、Captive Portal 等，
  以上游 8.8 的 `SYSTEM_EXCLUDED_PACKAGES` 增量为准，与 wanBox `ProfileManager.kt:302-306`
  的既有下载器排除合并去重），使系统组件不被应用层规则错分流。
- 规则集 0 规则占位：前后端同步校验 `.srs` 实际规则条数（上游 `isPopulatedSRSFile` /
  `isPopulatedSrsCache` 等价物），0 规则文件 MUST NOT 视为有效缓存；
  `local:` 本地规则集按 B3 核查结果定——wanBox 路由层目前无 `local:`，若规则集模型不支持则跳过。
- 分类优先级（仅当 wanBox 引入应用分类时适用，否则只落排除名单）：
  核心国外应用 → 显式国内 → 国外前缀 → 国内前缀 → 预置表（默认空）。

## 数据迁移

- `BalancerBean` 记录版本：wanBox v5 → B7a 引入新字段时升至 v6（字段形状由 B7a 设计定，
  不照抄上游 autoselect 形状的 v6）；B7c 层级持久化已升至 v7（`tierRules` /
  `priorityOrder` / `priorityExcluded`，`serializeV6` / `downgradeToV6Bytes`
  提供 v6 降级导出，`serializeV5` / `downgradeToV5Bytes` 链式复用）。
  每次升级 MUST：`writeInt(N)`、旧版 `readInt` 分支保留、`v1–v5` 历史数据可读、
  导出/备份可降级（未知字段不得静默丢弃，须按既有备份兼容规则处理）。
- 订阅间隔：只改交互与摘要，`autoUpdateDelay` 分钟值语义不变，不做数据迁移。
- 其余批次不做持久化迁移。

## 新增 UI 文案（默认语言 + 简体中文）

| 场景 | 默认语言（en） | 简体中文（zh-CN） |
|---|---|---|
| 订阅自动更新间隔预设标题 | Auto-update interval | 自动更新间隔 |
| 订阅自定义间隔 | Custom interval | 自定义间隔 |
| 订阅摘要：每 24 小时 | Every 24 hours (1 day) | 每 24 小时 (1天) |
| 订阅摘要：已关闭 | Auto-update off | 已关闭自动更新 |
| 订阅自定义最小值提示 | Minimum is 15 minutes | 最小为 15 分钟 |
| 策略组：恢复自动 | Resume auto | 恢复自动 |
| 策略组：严格手动锁定说明 | Locked. Won't switch on disconnect until you resume auto. | 已锁定，断连也不会自动切换，直到你点“恢复自动”。 |
| 策略组：一键全检 | Check all now | 立即全部检查 |
| 策略组：节点优先级 | Node priority | 节点优先级 |
| 策略组：添加层级 | Add tier | 添加层级 |
| 策略组：保底层级 | Fallback tier | 保底层级 |
| 策略组：锁定成员不可用（横幅） | Locked node unavailable | 锁定节点不可用 |
| 策略组：点成员锁定提示 | Tap a member to lock it | 点成员可锁定该节点 |
| 策略组：锁定该节点（标题/按钮） | Lock this node | 锁定该节点 |
| 策略组：锁定确认 | Lock %1$s? It won't auto-switch on disconnect until you resume auto. | 锁定 %1$s？断连也不会自动切换，直到你点“恢复自动”。 |
| 策略组：已锁定摘要 | Locked to %1$s | 已锁定 %1$s |
| 策略组：已锁定（短标记） | Locked | 已锁定 |
| 策略组：横幅自动 | Auto (%1$s) | 自动（%1$s） |
| 策略组：横幅已锁定 | Locked (%1$s) | 已锁定（%1$s） |
| 策略组：一键全检 | Check all now | 立即全部检查 |
| 策略组：失败次数 | Failures: %1$d | 失败 %1$d 次 |
| 策略组：最近报错 | Last error: %1$s | 上次报错：%1$s |
| 策略组：已锁定提示 | Locked. Rebuilding config… | 已锁定，正在重建配置… |
| 策略组：已恢复自动提示 | Auto selection resumed | 已恢复自动选择 |
| 冷启动：正在按缓存延迟预选 | Picking fastest known node… | 正在按缓存延迟预选最快节点… |
| 策略组：按优先级顺序（策略名） | Priority Order (priorityOrder) | 按优先级顺序 (priorityOrder) |
| 策略组：添加层级 | Add tier | 添加层级 |
| 策略组：层级规则（分类标题） | Tiers | 层级规则 |
| 策略组：层级 %1$d | Tier %1$d | 层级 %1$d |
| 策略组：保底层级 | Fallback tier | 保底层级 |
| 策略组：名称包含（正则） | Name inclusion (Regex) | 名称包含（正则） |
| 策略组：名称排除（正则） | Name exclusion (Regex) | 名称排除（正则） |
| 策略组：上移 | Move up | 上移 |
| 策略组：下移 | Move down | 下移 |
| 策略组：删除层级 | Delete tier | 删除层级 |
| 策略组：层级正则错误提示 | Fix the tier regex before saving:\n%1$s | 保存前请先修正层级正则：\n%1$s |
| 策略组：暂无层级 | No tiers yet. Members fall back to the fallback tier. | 暂无层级，节点全部归入保底层级。 |
| 策略组：节点优先级 | Node priority | 节点优先级 |
| 策略组：优先级摘要 | %1$d nodes: %2$s | %1$d 个节点：%2$s |
| 策略组：暂无节点 | No nodes yet | 暂无节点 |
| 策略组：按当前延迟排序 | Sort by latency | 按当前延迟排序 |
| 策略组：添加节点 | Add nodes | 添加节点 |
| 策略组：替换节点 | Replace node | 替换节点 |
| 策略组：从优先级列表移除 | Remove from priority list | 从优先级列表移除 |

以上为规格文案；实现时以 `values/strings.xml` 与 `values-zh-rCN/strings.xml` 落地，
其他已存在语言继续通过默认资源回退。

## 不做的事

- 不引入 `libcore/protocol/autoselect/`（O8）。
- 不改 `SINGBOX_VERSION`、不改版本号、不恢复 8.11/8.19 已回退的改动、不做 Kryo 库升级
  （5.2.1 保持不变）。
- 不在 APK 另造出站模型；不为统计页另开核心直连；不把凭据、地址或原始 stderr 放入 issue/日志。
- 不删除底部 StatsBar 之外的既有 UI（它已不存在）；不改动稳定版 v3.0.14 行为。

## B1 实施记录（2026-11-17，O2+O3+O4）

### 已拍板值

- 单步建连超时取 **3 秒**（用户 2026-11-17 22:16 拍板，与 wanBox 现有探针超时对齐，
  不用上游 8.13 的动态 2–4.5 秒）。落地为固定 3 秒，仅用于“还有后备候选可试”的尝试，
  最后一个候选不限时（照抄上游的 bounded 规则，避免单节点组里一次慢握手被直接掐断）。

### 与上游原文的差异（均有意为之）

- O2d：上游是广播 `SWITCH_PROFILE` → `BaseService.reload(allowSelector = true)`；
  wanBox 是 Root 模块架构，无 `BaseService.reload` 落点，改为 `SagerNet.switchProfileService`：
  运行时走既有 `selectProfile` → `RootModuleClient.selectNode` 热切换通道
  （确认后才持久化，失败保持旧连接与旧选择）；停止时保存选择并连接。
  `QuickToggleShortcut` 的 `toggleService` START 分支在运行中切另一节点时直接
  `call("node select …")` 热切（已持有 `changes` 锁，不得调 `selectNode()`，会死锁）；
  确认失败抛 `node_switch_not_confirmed`，不静默回退到全量重建。
- **O2d 修红（2026-11-18，CI #179/#180）**：O2d 把 `SwitchActivity.returnProfile` 从直调
  `SagerNet.startService()` 改为走 `switchProfileService` 热切换通道后，既有契约测试
  `ShortcutControlContractTest.toggleKeepsNodeValidationAndServiceSemanticsAndSystemAuthorization`
  在 `:41` 失败——它用字面形式断言 SwitchActivity 源码含 `"SagerNet.startService()"`，
  而 B1 自己的 `SwitchProfileHotSwitchTest` 反而断言 SwitchActivity 不含它，两处断言
  直接冲突，不可能同时成立。旧字面断言的意图是“快捷选择保留停止时也能连上”
  （改动前源码注释原话），不是“必须直调 startService() 本身”：改回直调会让运行中
  切换也走全量 `startOrReload`、中断现有连接，违反 O2d 与“切换不中断现有连接”约束；
  在 SwitchActivity 里按缓存 `DataStore.serviceState` 分支也不可取——缓存可能过期，
  判错会把运行中切换打回全量重建、或把停止时切换打进死模块；运行状态的唯一活判断
  在 `switchProfileService` 内（`call("status").connected`），不应再造第二个决策点。
  因此旧字面契约确实被 O2d 适配取代，修法是实现 + 测试各改一半：实现上，
  `switchProfileService` 的停止分支改走规范的 `startService()` 入口（其本体即
  `moduleCommand { RootModuleClient.startOrReload() }`，行为与原来相同，错误仍由
  moduleCommand 的 try/catch 上报），不再绕过应用层入口直调模块；测试上，
  契约测试改断言 SwitchActivity 走 `switchProfileService` 通道、且通道停止分支
  按调用行调用 `startService()`，原意图（停止时选中节点必须连上）继续被守住，
  没有放宽。`SwitchProfileHotSwitchTest` 同步加固为同样的调用行断言。
- O3 惩罚增量：沿用 wanBox 既有 +300，不照抄上游的 5000 下限。
- **O3 Time 续期冲突（B1 实施中发现，2026-11-17）**：第一阶段规格按上游 8.13 要求
  惩罚写入“保留旧 Time、不续期”，但本地构建跑测试时既有测试
  `TestMemberTimeoutStillPenalizesRecentHistory`（`startup_probe_test.go`，启动探针
  路径）失败——它明确断言失败后 `got.Time.After(before.Time)`，即 wanBox 自家
  的失败策略就是“+300 惩罚并续期 Time”。按工作词“与上游冲突时以自家为准”，
  B1 改为：惩罚写入仍续期 `Time` 为当前时间，只修真正缺陷——`Delay+300` 的
  uint16 回绕（饱和封顶 65000）。delta spec 与主 spec 的对应 requirement、
  tasks.md 1.1/1.7、`b1_regression_test.go` 的 Time 断言已同步改回续期。
  若用户希望改为上游的“不续期”，须同步改该既有测试，待用户拍板（B1 不动它）。
- O3 连续失败计数：落在持久的 `URLTestGroup` 上（`failCount`，probe 与 dial 超时共用）；
  `URLTestOutbounds` 新增 `WithGroup` 变体接线；`loadbalance` 调用方无归属组，
  只做饱和惩罚、不做连续失败清史。
- O3 冷启动预排序：上游是 `BalancerMemberResolver.sortForColdStart`；
  wanBox 无该解析层，改为 `ConfigBuilder.kt` 内的本地等价函数
  `sortBalancerMembersForColdStart`，只对 leastPing 策略排序（上游的 blank/leastPing
  守卫照搬；wanBox 的 strategy 空值已在 `BalancerBean` 内归一化为 leastPing）。
  Go 侧毫秒 0 预选（`URLTestGroup` 按 `HistoryStorage` 预选）是 B4 的 4.2/4.3，
  不在本批。
- O3 切网 DNS 重置：按上游 8.13 移植 `routerResetTarget`/`setRouterReset`/
  `resetRouterNetwork`（`Start` 注册、`Close` 清除）；`networkChangeResetConnections=false`
  的分支仍保留长连接，但重置路由器 DNS/HTTP 连接池。
- O9a：组内成员切换的缺口真实存在（模块侧 `node select` 后 state.json 的 ProfileID
  即成员 id，`cbSelectorUpdate(新成员)` 上报时 `id != selectedProxy（组 id）`，
  旧条件跳过 `clearCache()`，而 `getCachedInfo()` 按 selectedProxy 读缓存，
  60 秒内返回旧成员出口 IP）。修两处：`getCachedInfo()` 改按 `currentProfile` 读；
  `cbSelectorUpdate` 改为任何上报都清缓存。

### 核查关闭（不改实现）

- O2b：`protect.go` 的 `defer` 关闭全部 fd 已存在（另有 MSG_CTRUNC 守卫与 panic 恢复，
  比上游多）；回归断言 `TestProtectClosesReceivedCopyAndKeepsSender` 已存在。
- O4：wanBox 无 autoselect，`URLTest` 继续实现 `adapter.OutboundGroup`；
  防护断言 `TestURLTestImplementsOutboundGroup`，不得照抄上游 8.15 对 AutoSelect 的移除。
- RawUpdater：共享 OkHttpClient 的 `close()` 是有文档的 no-op，上游泄漏不适用。
- ServiceDiagnosticsManager：wanBox 不存在，`WanHttp.kt` 的共享 base 设计已满足复用。
- O9b：StatsBar 已删除，无落点。O9c：WebDAV 默认路径 `"OwnBox"` 与回退检索真实存在。
- yacd.zip 分支不移植（wanBox 已移除 yacd 网页面板）。

### 本批未做

- B4 的 Go 侧毫秒 0 预选与批量探针流式就绪、O1/O5/O6/O7 与 N 系均不在本批。
- 1.14 验证阶段未跑：CI（`verify-android.yml`）与真机结果待用户回传，不得勾选。

## B2 实施记录（2026-11-18，O5）

### 已拍板值

- 2.4 主进程 WorkManager 迁移**未拍板，不做**：调度继续走 `:bg` 跨进程
  `RemoteWorkManager`，`UpdateTask` 仍在 `:bg` 读 `DataStore.serviceState`。
  保留理由已写进 `SubscriptionUpdater.reconfigureUpdater` 注释与提交说明。

### 落地内容（2.1–2.3）

- 2.2：新增 `SubscriptionAutoUpdateDialog`（预设 15/30 分钟、1/3/6/12 小时、
  24 小时/1 天 + 自定义单位弹窗 + 人性化摘要），`autoUpdateDelay` 分钟值
  语义不变（默认 360）；`GroupSettingsActivity` 的间隔项由 `EditTextPreference`
  改为点开弹窗的 `Preference`，摘要随开关状态刷新；加载/保存时对历史
  <15 分钟值钳制到 15。文案按本文件末尾双语表落地（en + zh-rCN）。
- 2.3：新增 `SubscriptionUpdater.checkPendingUpdates(onlyIfConnected)`：
  进订阅分组页（`GroupFragment.onResume`，`onlyIfConnected = false`）与
  代理连接成功（`SagerConnection.stateChanged` 到 `Connected`，
  `onlyIfConnected = true`）时检查到期并补更。
- 2.1：新增 `SubscriptionAutoUpdateTest`（预设、自定义换算、最小值校验、
  摘要映射、初始延迟精确值无 60 秒截断回归断言、`isDue` 判定）；
  `SubscriptionSchedule` 新增纯函数 `isDue`（未更新过视为到期，间隔钳制 ≥15 分钟）。

### 与上游原文的差异（均有意为之）

- 补更尊重失败退避：上游 8.6 的 `checkPendingUpdates` 不查退避，直接调更新；
  wanBox 版先查 `shouldBackoff`，退避期内跳过、成功/失败后调
  `recordUpdateResult` 维护退避计数，与既有 `UpdateTask` 行为一致。
- 未做 `SagerNet.onCreate` 的冷启动补更调用：tasks.md 2.3 只定两个触发点
  （进订阅页、连接成功），应用启动触发不在本批范围，不擅自加。
- 摘要文案：上游 en 串照搬（"Every 24 hours (1 day)" 等），zh 按本文件双语表；
  `auto_update_delay` 标题按双语表取 "Auto-update interval" / "自动更新间隔"。
- `toMinutes`/`describeInterval`/`initialValueAndUnit` 抽为纯函数：
  上游的换算与摘要逻辑与 `Context` 耦合，无法进 JVM 单测；wanBox 版把纯逻辑
  抽出来测，`formatSummary`/`formatInterval` 只做字符串装配。

### 本批未做

- 2.4 主进程 WorkManager 迁移（未拍板）。
- 2.7 验证阶段未跑：CI（`verify-android.yml`，workflow_dispatch，推分支不触发，
  该分支上 0 次运行）与真机结果待用户回传，不得勾选；Kotlin 侧未经本地编译
  （沙箱 Gradle 插件解析失败，见提交说明），待 CI 验证。

## B3 实施记录（2026-11-18，O6）

### 核查结论（3.2，写入提交说明）

- Go 侧（`libcore/ruleset.go`）：`prepareRemoteRuleSets` 只凭文件存在/非空就
  信任 `InitialPath` 与缓存（0 规则占位文件本身约 7–13 字节，非空即被当作
  有效缓存）；`convertGeoRuleSetToSRS` 只凭 `.srs` 比 db 新就复用，转换失败
  写出的空回退会被永久复用；`validateRootGeoResources`（`rootbox.go`）只凭
  官方 `.srs` 存在就放行。以上三处均无实际规则条数校验——缺陷真实存在。
- Kotlin 侧（`SingBoxOptionsUtil.kt`）：`processRulesetUrl` 只接受 `rsip:`/
  `rssite:`，其余前缀（含 `local:`）即抛 `ruleset_prefix_error` 明确拒绝；
  `generateRemoteRuleSet` 只发 remote 条目（tag `ruleset-<hash>`），不读缓存；
  wanBox 无 `RuleSetCatalog`/`RuleSets`，Kotlin 侧没有任何 `.srs` 缓存查找
  调用点。
- `local:` 结论：**跳过，不硬做**。wanBox 规则模型没有本地规则集解析层
  （无 `RuleSetCatalog`、无多目录搜索、无 `local:` 标签模型）；用户输入
  `local:` 时配置生成即明确拒绝；Go 侧 `parseGeoRuleSetPath` 对 `local:`
  返回 ok=false，不做任何解析。上游 8.8 的多目录 `local:` 解析
  （`RuleSetCatalog.localRuleSetDirs`/`findLocalRuleSetFile`）不移植。
  远程规则集定时刷新须跳过 `local:` 条目——因 `local:` 条目进不了配置，
  该要求空虚满足。
- 上游 8.8 的 `rs.LocalOptions.Path` 命名不移植：wanBox 钉住的 reF1nd
  sing-box API 仍是 `rs.Path`，改名会编译失败。

### 落地内容（3.1、3.3、3.4）

- 3.3：新增 `route/SystemExcludedPackages.kt`（集中式名单，与上游 8.8 的
  `SYSTEM_EXCLUDED_PACKAGES` 19 项一致，含 `BuildConfig.APPLICATION_ID`）；
  `ConfigBuilder` 在应用路由生成时用 `effectivePackages` 统一过滤
  （UID 解析、`package_name`、DNS 派生规则、`isAppOnlyDns` 判定均走过滤后
  集合；日志只记数量，不记包名）；`ProfileManager` 的 Play 商店规则修复
  改引同一份名单——原先只剔两个下载器包名，现按完整系统名单剔除。
- 3.4：Go 侧新增 `isPopulatedSRSFile`（上游原文直搬，与 Kotlin 侧
  `RuleSetCache.isPopulatedSrsCache` 同一条判定：>128 字节直接放行，否则
  解析首个 varint 规则数），用于 `prepareRemoteRuleSets`（双命名
  altPath/dstPath 优先复用已填充缓存；两者皆无才保留/创建占位；已指定的
  `InitialPath` 只有填充过才信任）与 `convertGeoRuleSetToSRS` 的缓存复用
  分支（0 规则缓存不再复用，改为重新生成）；`validateRootGeoResources`
  的官方 `.srs` 存在检查改为填充检查。
  Kotlin 侧新增 `route/RuleSetCache.kt`（`isPopulatedSrsCache`，与 Go 同判定，
  配单测）：wanBox Kotlin 当前无 `.srs` 缓存查找调用点，本函数是前后端
  共用的校验定义，生效校验在 Go 侧 `box.New` 前。
- 3.1：新增 `libcore/ruleset_test.go`（6 项：占位/填充/缺失/0 字节/极简 JSON/
  大文件判定、优先填充缓存、无缓存保留占位、geo 转换复用拒绝空占位与保留
  填充缓存、`local:` 被忽略）；新增 `SystemExcludedPackagesTest`、
  `RuleSetCacheTest`、`RulesetEntryTest`（`local:` 被明确拒绝——JVM 单测里
  `SagerNet.application` 未初始化，抛出的具体异常类型与真机上的
  `ruleset_prefix_error` 不同，但“拒绝”契约相同）。

### 与上游原文的差异（均有意为之）

- `convertGeoRuleSetToSRS` 的复用分支加填充检查：上游 8.8 未动此处，但
  wanBox 的空回退写盘使该分支可达——转换失败写出的空 SRS 若只凭新旧复用，
  会被永久当作有效缓存。这是同类缺陷的延伸修复，不是照抄。
- `validateRootGeoResources` 的官方 `.srs` 检查改为填充检查：同类缺陷
  的延伸修复（只凭存在放行 → 0 规则占位落到 db 加载校验）。
- `RouteRuleEditor.validationConfig` 的投影规则不套排除名单：它是
  schema 校验的 dry-run，不是生成层；套用后纯系统包规则会投影为空，
  可能新增校验失败。生成层（`ConfigBuilder`）已排除，投影与生成的
  差异已在此记录。
- 不做分类优先级（核心国外应用 → 显式国内 → 前缀 → 预置表）：wanBox
  无 `classifyApps`，design.md 的 O6 要点已写明只落排除名单。

### 本批未做

- 3.7 验证阶段未跑：CI（`verify-android.yml`）与真机结果待用户回传，
  不得勾选；Kotlin 侧未经本地编译（沙箱 Gradle 插件解析失败，见提交说明），
  待 CI 验证；Go 侧本地已验证通过（见提交说明）。

## 修红 CI #182 记录（2026-11-18，B3 生成层过滤回退）

### 根因

- CI #182（头 `04775e6` = B3）唯一失败步骤是步骤 14 “Validate database
  migrations and external control (debug)”（插桩测试，79 项，2 失败 2 跳过）；
  #181（头 `0e1018c`，B1+B2+修红）全绿，#181→#182 之间只差 B3 一个批次。
- 两个失败同属一个根因：B3 在 `ConfigBuilder` 用户规则循环里用
  `SystemExcludedPackages.exclude(rule.packages)` 统一过滤包名，而名单含
  `BuildConfig.APPLICATION_ID`（管理器自身）。两处失败测试都用应用自身包名
  做夹具（`targetContext.packageName` / `context.packageName`），过滤后
  `effectivePackages` 为空，规则失去唯一匹配条件，被 `checkEmpty()` 守卫丢弃：
  - `DomesticRoutingConfigTest.presetIsRepeatableAndAppOverridesDnsAndGeoFallbacks`：
    `selectedApp` 规则（仅包名 = 应用自身，outbound=proxy）生成结果里消失，
    `app = indexOfFirst { 含应用包名 }` 得 -1，`assertTrue(app >= 0 && …)` 裸失败；
    DNS 侧同理（`isAppOnlyDns` 变假，不再派生 DNS 规则）。
  - `RouteRuleCompatibilityTest.realGeneratorKeepsOrderTargetsAndFieldsInVpnAndRootTun`：
    904 号规则（仅包名 = 应用自身，outbound=-1 bypass）生成结果里消失，
    `list.first { type=="logical" && 含应用包名 }` 顺延命中 907 号规则
    （测试包 `io.nekohasekai.sagernet.test` 的包名字符串含应用包名作子串），
    其出站是选中节点 tag `192.0.2.2:1081`，故 `expected:<[bypass]> but
    was:<[192.0.2.2:1081]>`。
- 上游核对：`SYSTEM_EXCLUDED_PACKAGES` 在 OwnBox 里只用在
  `SmartRouteRules.kt` 的分类层（`resolveDomesticAndForeign`：扫描已装应用
  做分类、保底循环、尊重用户分应用手动选择三处），从不用在用户规则生成上。
  wanBox 无 `SmartRouteRules`/`classifyApps`，B3 把名单挂到了错误的层。
- 语义论证：排除的本意是“分类派生绑定不得把系统组件强绑进代理”；对显式
  用户规则做过滤不是“排除”，而是静默删除用户的明确指令——流量改走默认
  路由（规则模式下默认常为代理），与排除本意相反。例如用户给系统下载器
  写的 bypass 规则被删后，下载流量反而跟随默认路由进代理。B3 的 design
  记录自己也写过“投影规则不套排除名单，套用后纯系统包规则会投影为空”，
  却没处理生成层同样会把规则置空的问题。

### 修法（实现侧回退，测试不动）

- `ConfigBuilder.kt`：删除用户规则循环里的 `effectivePackages` 过滤，
  恢复 `rule.packages` 原样生成（UID 解析、`package_name`、DNS 派生规则、
  `isAppOnlyDns` 判定、日志计数六处）；删 `SystemExcludedPackages` 引用。
  恢复后与 B3 之前（#181 全绿）的生成行为完全一致，两处失败测试无需改动。
- `ProfileManager.kt`：Play 商店规则修复收回 B3 的放宽，恢复为只剔两个
  下载器包名（`com.android.providers.downloads` / `.ui`）。理由：该修复会
  改写入库的用户规则（`updateRule` 持久化），放宽到完整 19 项名单会永久
  丢失用户规则里的系统包名；且 `DefaultRouteRulesTest.historicalPlayRulesDropOnlyInjectedDownloadsPackages`
  的契约就是“只剔注入的两个下载器包名”，`unrelated` 规则原样保留。
- 保留：`SystemExcludedPackages.kt` 本体与 `SystemExcludedPackagesTest`
  不动——名单仍是唯一来源，供将来分类层使用；B3 的 Go 侧 3.4（`isPopulatedSRSFile`
  三处缓存校验）不动，与本次失败无关，#182 里其余 77 项测试（含 6 项新
  ruleset 测试的 JVM 部分）均通过。
- 规格同步：主规范与 change delta 的 B3 排除名单需求改写为修正后的分层
  口径（名单不改写用户显式规则；适用层是分类派生绑定与 Play 修复的两个
  下载器包名）。

### 被否决的修法

- 只改测试夹具（把应用自身包名换成 `com.example.app` 等假包名）：能让
  CI 变绿，但把 B3 的静默丢规则行为合法化了——真实用户给系统组件写的
  显式规则仍会被删，且删后流量改走默认路由，与排除本意相反。否决。
- 只豁免 `APPLICATION_ID`、其余 18 项继续过滤：两处失败测试能过，但
  其余系统包（如下载器）的显式规则仍会被静默删除，问题只缩小未根除；
  且上游证据表明排除本就不该作用于用户规则层。否决。
- 在生成层给被清空的规则补一条直连兜底：会凭空新增一条用户没写的规则，
  改变路由语义，且与“用户规则原样生成”契约冲突。否决。

## B4 实施记录（2026-11-18，O1）

### 已拍板值

- 单步超时取 **3 秒**（用户 2026-11-17 拍板，与 wanBox 现有探针超时对齐，
  不是上游的 2 秒）；4.1 的“未拍板不得进入 4.2”这道门已过。

### 4.1 约束对表核查结论（逐条核对，均已验证）

- 间隔 ≥10 秒：`ConfigBuilder` leastPing 分支 `interval coerceAtLeast(10L)`；
  `buildUrlTestOutbound` 经 `clampUrlTestParams`（GroupEditor.kt:159–164）
  `intervalSeconds coerceAtLeast(10)`；DataStore 组间隔默认 600 秒。
- 空闲 ≥30 分钟：`clampUrlTestParams` idle < 1800s → `"30m"`；DataStore
  组 idle 默认 `"30m"`。注意：balancer 子组 `idleTimeoutStr` 取 `"${iv}s"`
  （间隔长度）是既有行为，非本批改动；流式就绪的 `isActive` 门用各组
  自己的 `idleTimeout` 判定，口径一致。
- 切换不中断现有连接：clamp 强制 `interruptExisting = false`；
  `buildLoadBalanceOutbound` 两调用点均传 `interruptExist = false`，
  默认调用亦为 false；Go 侧 `performUpdateCheck` 仅在
  `interruptExternalConnections=true` 时才 Interrupt，B4 流式路径复用
  同一道门；新增测试 `TestDialFailoverKeepsExistingConnections` 钉住
  “故障转移不 Interrupt”。
- 种子不续期时间戳：B4 未写任何种子写入——HistoryStorage 是每核新建的
  内存态（box.go:183），冷启动时必为空，Go 侧不存在可播种的缓存；
  `freshHistory`/`selectLocked`/`selectedFresh` 均为只读；测试断言
  `Select()` 后 Time/Delay 原值不变。O3 的惩罚续期分歧（B1 既有测试断言
  失败后 Time 续期）本批未动，仍待用户拍板。
- 不重置探测间隔时钟：流式路径只调 `performUpdateCheck`，不碰 ticker、
  不碰 `lastActive`；测试断言 `lastActive` 不变；倒计时仍按整轮结束重置。

### 关键核查发现：Go 侧冷启动无可播种的历史（不做启动时播种）

- HistoryStorage 每核新建（sing-box `box.go:183`
  `service.ContextWithPtr(ctx, urltest.NewHistoryStorage())`），内存态、
  从不落盘；libcore 与 app 没有任何代码在启动时向其中恢复缓存（全仓仅
  urltest 包内写 `StoreURLTestHistory`）。因此在 `NewURLTestGroup` 里算
  种子必为空——死代码，本批不做。
- 冷启动真正跨重启存活的缓存是应用层 DB（`ProxyEntity.status`/`ping`），
  B1 的 `ConfigBuilder` leastPing 预排序已把它变成成员顺序；
  `selectLocked` 无新鲜历史时回落列表首位（即 B1 排序后的首位），这就是
  wanBox 的“0 毫秒预选”。Go 侧增量是：过期判定（`freshHistory`）+
  idle 后重选（`selectedFresh`）+ 流式就绪（4.4）。
- 与既有启动探针测试的关系：
  `TestStartupProbeIsAsyncAndFirstConnectionWaits` 要求“无历史时首连必须
  等首轮探针，不得盲连列表首位超时节点”——B4 保留该行为：仅当已有新鲜
  选择（首轮中已有成员成功并流式就绪）时 `DialContext`/`ListenPacket`
  才跳过等待；无新鲜选择时仍等。测试 `TestSeedSkipsStartupProbeWait`
  覆盖前者，既有测试覆盖后者。

### 落地内容（4.2–4.5）

- 4.2：新增 `libcore/protocol/urltest/b4_regression_test.go`（5 项测试 +
  2 个夹具 `delayedDialOutbound`/`closeSpy`）：
  `TestSelectIgnoresExpiredHistory`（过期 10ms 不压新鲜 500ms；全过期
  回落首位 healthy=false；`Select` 不改写 Time）、
  `TestSeedSkipsStartupProbeWait`（首轮进行中快成员就绪后首连 2 秒内
  返回且 `startupDone` 仍开着）、
  `TestStreamingReadinessUpdatesSelectionMidRound`（500ms 时选择已是快
  成员、慢成员历史仍空、`lastActive` 不变；整轮后仍是快成员）、
  `TestListenPacketBlackholeTimeoutFailover`（UDP 黑洞 3 秒超时转移、
  两次清史）、`TestDialFailoverKeepsExistingConnections`
  （`AttachConnection` 的既有连接在转移后仍存活）。
- 4.3：Kotlin 侧无生产逻辑改动——`ProxyEntity` 无探针时间戳字段，排序
  无法判定过期；`ConfigBuilder.kt` 给 `sortBalancerMembersForColdStart`
  补 KDoc 写明“纯顺序、过期判定在 Go 侧”；`ColdStartMemberOrderTest`
  新增 `sortDoesNotJudgeFreshness`（健康大延迟仍排未测前，过期判定不
  在此处）。Kotlin 未本地编译，待 CI 验证。
- 4.4：`urlTestBatch.test` 成功分支在写回 history + 复位失败计数 + 释放
  `b.access` 锁后，若 `b.group != nil` 且 `isActive()`（`lastActive`
  落在 `idleTimeout` 窗口内、ctx 未取消）即调 `performUpdateCheck()`
  （重算选择 + 仅选择变化时 `NotifyUpdated`）。上游 8.20
  `recordProbeProgress` 的适配：上游在 autoselect 内 recordSample +
  evaluateWithTransition + NotifyUpdated；wanBox 用既有
  `performUpdateCheck`（容差择优 + 仅选择变化才 NotifyUpdated）等价
  实现；`StoreURLTestHistory` 本身已通知 history 观察者。倒计时仍按
  整轮结束重置（`urlTest` 的 deferred `performUpdateCheck` 与 ticker 不变）。
- 4.5：**B1 已落地，不重复做**——`dialOne`/`listenOne` 的
  `urlTestDialAttemptTimeout=3s`（仅有后备候选时约束，末候选不限）；
  本批只做增量核查：新增 UDP 镜像测试
  `TestListenPacketBlackholeTimeoutFailover`（3 秒超时、两次清史）与
  既有连接不断测试；TCP 镜像测试为 B1 的
  `TestDialBlackholeTimeoutFailover`。

### 与上游原文的差异（均有意为之）

- 不做 autoselect 式 `InitialDelays` 注入：wanBox 无 autoselect 引擎，
  冷启动缓存不在 Go 层；等价实现是 B1 的 `ConfigBuilder` 成员预排序 +
  `selectLocked` 回退首位。
- 单步超时 3 秒而非上游 2 秒：用户拍板（2026-11-17），与 wanBox 现有
  探针超时（`ProbeOutbound` 3s / `testCtx` 3s）对齐。
- 流式就绪加活跃门（`isActive`）：上游 `recordProbeProgress` 无条件
  触发；wanBox 只在组活跃（未 idle）时流式择优，idle 后的首次连接仍按
  缓存重新预选——与 design.md O1 对表一致。
- `selectLocked` 新鲜窗口 `3*interval`：与 B1/O3 惩罚窗口
  （`penalizeOrDeleteHistory`）同一口径；过期历史在选择层直接忽略，
  不删除（删除仍由失败惩罚路径负责）。
- `Now()` 不动：`Now()` 读 `selectedOutboundTCP` 字段直接返回（仅展示
  用）；过期重选只走 `DialContext`/`ListenPacket`/`Selected` 的
  `selectedFresh` 路径，不改展示行为。

### 相邻缺口（本批未处理，记下来）

- loadbalance 组（`libcore/protocol/loadbalance/outbound.go:555,596,609`）
  在排序/选择时读 `LoadURLTestHistory` 不判新鲜——与 B4 修的
  `URLTestGroup` 同类问题，但属 loadbalance 组，不在 O1（URLTest 适配）
  范围内；后续批次如动 loadbalance 选择逻辑时一并处理。

### 本批未做

- 4.8 验证阶段未跑：CI（`verify-android.yml`）与真机结果待用户回传，
  不得勾选；Kotlin 侧未经本地编译（沙箱 Gradle 插件解析失败，见提交说明），
  待 CI 验证；Go 侧本地已验证通过（见提交说明）。

## B7a 实施记录（2026-11-18，O7 混搭来源 + 组隔离）

### 已拍板值

- v6 字段形状由本批定（任务书结论，2026-11-18 12:07 前已定）：不照抄上游
  autoselect 形状的 v6。wanBox v6 = `TYPE_MIXED = 2` + `maxMembers`（int，
  0 = 不限）。上游 v6/v7/v8 的字段形状与 autoselect 绑定，本轮不采 autoselect，
  故不移植。
- 「按优先级顺序」是新增模式（任务书结论），不在 B7a 做，随 B7c 落地。

### 8.15 同类问题核查结论（5.2 前置，先核查、存在才修）

逐项核查 `BalancerBean.initializeDefaultValues()`：

- `tolerance`：守卫是 `< 0 → 300`，0（严格切换）早已保留——**无缺陷，不改**。
  这正是上游 8.15 修过的那类问题在 wanBox 的已修形态。
- `interval`：`<= 0 → 300`。interval 是探测周期（秒），0 没有“不限”语义；
  UI（`balancerInterval` 摘要 ≤0 回落 "300"）与 ConfigBuilder
  （`coerceAtLeast(10L)`）均不把 0 当有效用户值——**不是 8.15 类覆盖，不改**，
  测试钉住该行为以防后人误改。
- `frontProxy` / `landingProxy`：`0L → -1L`。0 不是合法 profile id
  （Room id 从 1 起），-1 = 无——**是 id 归一化，不是数据丢失，不改**。
- 新字段 `maxMembers` 的 0 是本批唯一的显式“不限”值：
  `initializeDefaultValues` 只做 `if (maxMembers < 0) maxMembers = 0`，
  0 原样保留；解析层对未经归一化的负值同样按不限处理（防御性）。

### 落地内容（5.1–5.3）

- 5.1：新增 `BalancerMixedSourceTest`（15 项）：混搭去重（分组在前、散装在后）、
  正则 include/exclude 只作用于分组来源、非法正则报错且生成侧按未设置处理、
  `maxMembers` 截断（0 不限、负值不限）、LIST/GROUP 互不干扰、空来源；
  v5 手写历史 payload 可读可升（`maxMembers = 0`）、v6 往返保真、
  `serialize` 写版本 6、0（不限）不被默认值覆盖、降级字节读作 v5 且字段齐全、
  混搭 `displayAddress`；ConfigBuilder 与设置页源码级接线断言。
- 5.2 记录升级：`serialize` 写 `writeInt(6)`，`deserialize` 保留 v1–v5 全部分支，
  `version >= 6` 才读 `maxMembers`，旧版读到 0 = 不限；降级导出
  `serializeV5` / `downgradeToV5Bytes` 按 v5 布局写 v5 子集（类型强制降为
  TYPE_LIST，`targetGroupId` 写 0、`targetGroupIds` 写空，proxies 为调用方
  先解析出的唯一成员 id 列表——分组来源与上限已 baked 进列表，不静默丢字段；
  其余 v5 字段与 name 附加段原样保留）。备份/导出路径本身仍写 v6（向前兼容
  读取端）；v5 降级 API 供显式“导出给旧版”流程调用，测试覆盖。
- 5.2 解析层：新增 `fmt/BalancerMemberResolver.kt`（wanBox 原先无该解析层，
  成员展开逻辑散在 `ConfigBuilder.buildChain` 里）。语义：分组在前、散装在后、
  按 id 去重；正则只过滤分组来源（散装是显式点选）；`maxMembers` 去重后截断；
  非法 type 沿用既有行为按散装列表处理，不静默变空组；非法正则记进
  `BalancerMemberResolution` 供 UI 展示，生成侧按未设置处理。
- 5.2 组隔离（两层，都是真实存在的串线缺陷）：
  1. 策略组级：旧代码用 `tagMap` 的 `-member.id` 槽位缓存“带上下文的成员链”，
     两组共享成员时第二组直接复用第一组的链 tag，前置/落地代理链串线。
     改为 `balancerScopedTagMap[(balancerId, memberId)]` 隔离缓存。
  2. 链级：即使缓存隔离，`buildChain` 仍会经 `globalOutbounds` 复用共享出口
     跳点——复用分支直接跳过出站生成，第二组的 detour 无处可挂、被静默丢弃，
     仍串线。新增 `buildChain(..., isolationKey)`：隔离构建时出口跳点不用全局
     `g-<id>` tag（改用 `g-<id>-b<balancerId>`）、不查全局共享表、中间跳点
     tag 加组后缀（否则两组共享同一多跳成员会发出重复 tag，sing-box 拒收）。
     落地链（detour 就是本组 tag）同样隔离构建；前置链本身无组上下文 detour，
     沿用 `tagMap[front.id]` 共享，不动。
  副作用（有意为之，已记录）：隔离链不再进入 `tagMap.values`，顶层
  selector/urltest 的成员列表不再含策略组内部成员链（策略组本体的 tag 仍在
  其中；内部成员链本就不是分组 profile，之前是泄漏，选它会绕过策略组）。
  `useFront` / `useLanding` 开关的适用类型由 `== TYPE_GROUP` 放宽为
  `!= TYPE_LIST`（混搭与分组一致）。
- 5.2 编辑器引用图：`GroupEditorService.validate` 的引用图对混搭同时计入分组边
  与散装节点边（分组 id 只取 >0，避免无分组的混搭凭空多一条 Group(0) 边），
  否则经由散装成员的环引用检查不到；完整 `validate` 需 Room，待 CI/真机验证。
- 5.3 设置页：类型数组新增“混合（分组 + 节点）”（值 2）；混搭同时显示分组选择
  与散装节点列表；新增 `balancerMemberCount` 实时计数项（分组/散装/去重数，
  正则错误时摘要直接显示错误）与 `balancerMaxMembers` 上限项（0 = 不限，
  负值归一化为 0）；分组选择、正则改动、类型切换、散装增删、异步加载完成均
  刷新计数；覆写 `saveAndExit()`，名称正则语法错误时弹框阻止保存。
  文案按本文件末尾双语表落地（en + zh-rCN），新增 9 条。
- 5.4：主规范新增两条 B7a 已落地 requirement（混搭来源与组隔离、记录 v5→v6），
  delta spec 两条对应 requirement 补落地注；`git diff --check` 通过；
  版本文件与 `SINGBOX_VERSION` 未变（未碰）。

### 与上游原文的差异（均有意为之）

- 不照抄上游 8.12 的混搭实现形状：上游的混搭与 autoselect 引擎绑定
  （`BalancerMemberResolver` 只是其一环），wanBox 只取“分组+散装去重”
  的等价语义，落在既有 urltest/loadbalance 出站上。
- 正则失效的处理：上游行为未在任务书中钉住；wanBox 版生成侧按未设置处理
  （与既有 ConfigBuilder 的 `getOrNull()` 行为一致），设置页拦截保存——
  导入/备份里的脏正则不让核心起不来，也不让用户无声保存脏数据。
- 组隔离是 wanBox 自有的两层修复：上游 8.12 无 `tagMap -id` 槽位与
  `globalOutbounds` 共享跳点的等价结构（OwnBox 的链生成路径不同），
  缺陷与修法均为本批核查确认。

### 本批未做

- 5.6 验证阶段未跑：CI（`verify-android.yml`）与真机结果待用户回传，
  不得勾选；Kotlin 侧未经本地编译（沙箱 Gradle 插件解析失败，见提交说明），
  待 CI 验证；Go 侧无改动。
- B7b（严格手动锁定 + 统计页 + 横幅）与 B7c（层级调度 + 优先级页）不在本批。

### 偏离记录：tasks.md 5.6 的“验证未通过前不得开始 B7b”已被覆盖

用户 2026-11-18 12:07 下令“剩下六批全部做完，绿了合并”，覆盖 tasks.md
原先“验证未通过前不得开始下一批”的串行要求（B7a 的 5.6 亦同）。本批做完
5.5 即停下汇报，不等 CI/真机验证，由协调方继续派 B7b；5.6 保持未勾选、
写“未验证”。此偏离同步记入提交说明。

## B7b 实施记录（2026-11-18，O7 严格手动锁定 + 统计页 + 横幅）

### 已拍板值

- 锁是运行时选择，不进 `BalancerBean` 记录：本批不升记录版本（v6 保持），
  B7c 的层级字段仍按计划升 v7。锁存 `DataStore.balancerManualLock`
 （configurationStore，`"<balancerId>:<memberId>;..."` 编码）。
- 锁定生效点在配置生成层（唯一能让核心不自动切换的点）：锁定时该策略组
  生成 `selector`（`default` = 锁定成员），而不是继续跑 urltest/loadbalance。
- 断开开关：本批不提供——selector 用 sing-box 默认
  `interrupt_exist_connections = false`，锁定与层级回切 MUST NOT 默认断开
  现有连接；用户显式开启断开开关是 B7c 或后续批次的事，不在本批擅自加。

### 落地内容（6.1–6.3）

- 6.1：新增 `BalancerManualLockTest`（22 项）：锁定后断连/超时不自动切、
  恢复自动后恢复自动、只解一组的锁、锁替换成员、非法 id 忽略、编解码往返与
  容错、锁定期间失败计入统计（连续失败累计、成功清零并记延迟）、分组成员统计
  快照、锁定成员取 tag（在列表里取到、不在返回 null 回落自动）、快照 tag↔id
  双向解析（profileTags 优先、展示名精确匹配回退、重名不猜）、横幅模型
  （自动/锁定、承载节点、延迟、锁定不可用标红、未连接/非策略组隐藏）、
  ConfigBuilder/DataStore/统计页/主页源码级接线断言、双语文案存在断言。
- 6.2：新增 `fmt/BalancerManualLock.kt`——`BalancerLockState` 状态机
  （lock/resumeAuto/isLocked/shouldAutoSwitch/onFailure）、
  `encode`/`decode`（容错解析，脏条目跳过、重复 balancerId 后者赢）、
  `lockedMemberTag`（锁定成员在成员列表里才取 tag）、
  `resolveSnapshotMemberId` / `snapshotTagForMember`（快照 tag↔id 双向解析）。
  `ConfigBuilder.buildChain` 的策略组分支：成员链改为保留
  `(memberId, chainTag)` 对照（`memberTagPairs`）；锁定时生成 selector
  （`default` = 锁定成员 tag，成员列表不变），锁定成员不在列表里时回落
  自动生成、锁仍保留；导出/测试配置不带运行时锁定（与 `useAutoSelect`
  同一 `!forTest && !forExport` 守卫）。全策略（leastPing 与 loadbalance
  各策略）统一走锁定分支。
- 6.2 统计：新增 `bg/proto/BalancerMemberStats.kt`——进程内内存态
  （与 `UrlTestCache` 同级，不进持久化，重启后由下一次检查重建）；
  `failures` 为自上次成功以来的连续失败次数，成功后清零并记延迟、清掉旧报错。
- 6.3 统计页：既有 `PolicyGroupsActivity`（Root 快照 + Clash API +
  `node tags`，未另开核心直连）——成员行展示失败数/最近报错；
  “整组测速”按钮改为一键“立即全部检查”（`policy_check_all`），每次结果
  记入统计（成功记延迟、失败记失败数与报错），锁定期间的失败同样计入；
  自动组成员行点按即锁定（确认弹窗，解析不到 id 的行不可点，不得猜 id），
  锁定行显示已锁定摘要 + 锁定说明 + 「恢复自动」按钮，锁定成员不在快照
  成员列表里时红字提示“锁定节点不可用”；锁定/解锁后
  `SagerNet.reloadService()` 重建配置生效，未连接时只落盘、下次连接时生效。
  锁只对 `BalancerBean` 策略组实体生效：顶层 proxy selector 与编辑器组
  没有策略组实体，不提供锁定入口。
- 6.3 横幅：`DashboardFragment` 当前节点卡片下方新增 `balancerBanner`
  （`layout_dashboard.xml`），`ui/BalancerBanner.kt` 纯模型（模式/承载节点/
  延迟/锁定不可用标红/未连接或非策略组隐藏）；数据来自 `node tags` +
  Clash API 快照 + 本地 DB/统计，30 秒一刷、回首页即刷，快照读不到时保留
  上一次横幅（不猜状态）；点横幅进统计页；锁定不可用时详情行红字
  （`R.color.connect_error_fill`）。承载节点：锁定时取锁定成员名，
  自动时取快照 `now`；延迟优先取统计页最近一次实测，否则取成员缓存延迟
  （`status == 1` 的 `ping`）。

### 与上游原文的差异（均有意为之）

- 上游 8.12 的锁定落在 autoselect 引擎内（引擎自己停切）；wanBox 不采
  autoselect，等价实现是配置生成层把锁定的组改发 selector——核心侧无
  autoselect 也能做到“不断连、不自动切”，且恢复自动只需重建回 urltest。
- 锁不进 `BalancerBean` 记录：上游的锁定态与 autoselect 持久化字段绑定；
  wanBox 的锁是运行时选择（与“当前选中节点”同级），放 DataStore 更贴切，
  且不占用 B7c 预留的 v7。
- 统计不进 DB：上游 8.12 的统计随 autoselect 引擎持久化；wanBox 的统计只
  服务于统计页展示与横幅提示，进程内内存态足够，重启后由检查重建。
- 锁定入口只在统计页：上游在策略组设置页也有锁定入口；wanBox 的锁定需要
  看到运行快照的实时成员（`now` 与延迟）才有意义，设置页只有静态成员列表，
  故本批只做统计页入口，设置页不加。
- `policy_measure`（整组测速）改名为一键“立即全部检查”
  （`policy_check_all`）：动作本身就是全员逐个测速，改名只为与 delta spec
  的“一键全检”措辞对齐；旧串已从默认语言与简体中文删除，无其他引用。

### 本批未做

- 6.6 验证阶段未跑：CI（`verify-android.yml`）与真机结果待用户回传，
  不得勾选；Kotlin 侧未经本地编译（沙箱 Gradle 插件解析失败，见提交说明），
  待 CI 验证；Go 侧无改动。
- B7c（层级调度 + 优先级页）不在本批。

### 偏离记录：tasks.md 6.6 的“验证未通过前不得开始 B7c”已被覆盖

用户 2026-11-18 12:07 下令“剩下六批全部做完，绿了合并”，覆盖 tasks.md
原先“验证未通过前不得开始下一批”的串行要求（B7b 的 6.6 亦同）。本批做完
6.5 即停下汇报，不等 CI/真机验证，由协调方继续派 B7c；6.6 保持未勾选、
写“未验证”。此偏离同步记入提交说明。

## B7c 实施记录（2026-11-18，O7 层级调度 + 优先级页）

上游依据：OwnBox `0911277`（8.16）、`d76bba7`（8.17）、`e72bb2a`（8.18 相关部分）。
本批只做 O7「按优先级顺序」多层级调度 + 节点优先级二级页（O9d 并入此处），
不碰 autoselect 统一引擎（O8 已拍板不采）。

### 模式定义（本批定）

- 「按优先级顺序」是**新增策略值** `STRATEGY_PRIORITY_ORDER = "priorityOrder"`，
  与既有模式并存（用户已拍板，不再问）。
- 「最快最稳」=`STRATEGY_LEAST_PING`——此映射由本批定义（任务书只写了模式名，
  未写与既有策略值的对应关系）；「最快最稳」模式 MUST 隐藏层级相关入口
  （不展示添加层级、层级行与优先级列表），由 `updateStrategyVisibility` 控制。

### 层级调度：Kotlin 模型 + Go 强制执行（分工）

- Kotlin 侧（`fmt/BalancerTierScheduler.kt`，纯模型、无 Android 依赖）：
  层级规则编解码、定层级（首条匹配规则获胜，未匹配进保底层级）、层内择优
  （延迟 + 容差，语义复用 Go `selectLocked`）、回切双重门
  （`TIER_SWITCH_BACK_MIN_CHECKS = 3` 次连续健康检查 +
  `TIER_SWITCH_BACK_MIN_FAILURE_FREE_MS = 60_000` 毫秒无失败）、
  流量只在当前最低健康层内分散（`activeTierMemberIds`；全灭时退化返回全部成员，
  流量仍能走）。
- Go 侧（`libcore/protocol/urltest/outbound.go`）：层级 tag 约定
  `"<balancerTag> [T<n>]"` / `"<balancerTag> [FB]"`（Kotlin `tierGroupTag`，
  同格式）；`parseTierTagIndex` 解析；层级**仅当全部成员 tag 都带后缀且
  ≥2 个不同层级时激活**（防普通节点名恰好以 ` [T<n>]` 结尾误触发）；
  `selectLocked` 按层级重排选择，`currentTier` 由 TCP（否则 UDP）选择结果跟踪，
  保底层级序号哨兵 `1 << 30`；回切门状态（`tierRecoveryGateState`：
  `consecutiveOK` / `lastFailure`）只在每轮结束的新 `performRoundEndCheck`
  里更新（`urlTest` 轮末改调它；流式就绪仍调 `performUpdateCheck`，不计轮次）。
- 配置生成（`ConfigBuilder.buildChain`，优先级模式）：按 `applyPriorityOrder`
  排成员（优先级顺序即平局决胜，不做冷启动延迟排序），按层级生成嵌套 urltest——
  每层一个内层 urltest（同间隔/容差/空闲），外层 urltest 对层级 tag 择优；
  单个非空层不嵌套（普通单 urltest）；零成员走既有 `TAG_DIRECT` 兜底。
  内层 urltest 直接加进 `outbounds`，不进 `tagMap.values`（不泄漏进顶层 selector）。
- 探测负载：不新增探测器、不压间隔（仍钳制 ≥10 秒）；外层 urltest 每轮对每个
  层级组多一次 dial（即每轮每层 +1 次探测，同 ≥10 秒间隔），属可接受增量。
- 回切不断现有连接：嵌套 urltest 的 `interrupt_exist_connections = false`
  （B7b 锁定 selector 同款默认值）；`SINGBOX_VERSION` 与版本号未变。
- 无新配置 schema 字段：sing-box 选项解析用 `DisallowUnknownFields` 且 fork
  在本分支之外，层级改用嵌套 urltest 组 + tag 约定实现，不加字段。
- 统计页锁定不受影响：层级是配置生成层的分组概念、没有独立成员 id，
  统计页仍按成员行提供锁定入口（B7b 规则不变）；层级组本身没有可锁定的行。
- preview.4 复审修正（2026-11-19）：`failoverCandidates` 旧实现把候选限定在
  当前生效层，导致「高层历史新鲜但该节点实际建连失败」时完全不尝试健康低层；
  现改为「当前层其余候选 → 按优先级更低层」，每层内部仍按新鲜度/延迟排序，
  成功者同时把 `currentTier` 下移到该层（否则下一次择优会被高层新鲜历史拉回）。
  回切门未开的高层仍不得进入候选。`updateTierGateLocked` 旧实现按新鲜历史
  给整层计数，缓存跳过全部探测、或一轮只有低层完成探测时，高层也会凭旧历史
  累计健康轮并提前回切；现只按本轮成员观测（新成功/新失败/跳过/取消）裁决，
  跳过与取消保持中性、低层结果不得替高层计数，观测集合每轮独立且在
  `batch.Wait()` 之后才收尾。不变量与场景见 `openspec/specs/libcore-integration/spec.md`
  的「分层 URLTest 的向下容灾与向上回切不变量」。

### 节点优先级（`fmt/BalancerPriorityOrder.kt` + 二级页）

- 模型：`reconcilePriorityOrder`（已存 ∩ 当前成员，再按解析顺序补未列出的，
  已排除的丢弃）、`applyPriorityOrder` / `movePriorityOrder` /
  `removePriorityOrder` / `replacePriorityOrder`（保位、去重）、
  `sortPriorityOrderByLatency`（未知延迟排最后、稳定排序）/
  `sortPriorityOrderByLatencyWithinTiers`、`formatPriorityNumber`（index+1 紧凑编号）。
- 移除语义：散装成员同时从 `proxies` 移除（真正离组）；分组来源成员记入
  `priorityExcluded`——**排除过滤只在优先级模式生效**，切回其他策略即恢复
  全部成员（已记入主规范）。
- 二级页：新 `BalancerPriorityActivity` + `layout_balancer_priority.xml`
  （复用 `LayoutProfileBinding` 的 RecyclerView；拖拽 ItemTouchHelper、
  滑动/按钮移除、点击替换经 `ProfileSelectActivity`、多选添加、
  按延迟排序（有层级时层内排序）；草稿态编辑，返回时经结果 intent
  （`EXTRA_PRIORITY_ORDER` / `EXTRA_EXCLUDED_IDS` / `EXTRA_LOOSE_IDS`）
  回写 DataStore 草稿并自动生效；新成员层级按名称正则重算、不继承被替成员的层级。
- 设置页内嵌列表：位于「指定节点」下方、「高级」上方
  （`updatePrioritySummary`：数量 + 前 3 名预览）；策略数组新增
  `priorityOrder` 项；探测设置（容差/URL/间隔）在 leastPing **或**
  priorityOrder 时可见；保存时层级正则错误拦截
  （`balancer_tier_invalid_message`）。
- 主页横幅：`DashboardFragment.strategyLabel` 新增 priorityOrder 分支。

### 记录升级 v6→v7

- `BalancerBean.serialize` 写 `writeInt(7)`；新增 `tierRules`（String，编码后规则表）、
  `priorityOrder`（`List<Long>`）、`priorityExcluded`（`List<Long>`）；
  `v1–v6` 仍可读，v7 字段缺失时取 `""` / 空列表；`initializeDefaultValues`
  只做 v7 字段空值守卫；新增 `serializeV6` / `downgradeToV6Bytes`
  （复用 V5 模式），`serializeV5` / `downgradeToV5Bytes` 链式复用、仍可用。
- 草稿态另存 DataStore（profileCacheStore）：`balancerTierRules` /
  `balancerPriorityOrder` / `balancerPriorityExcluded`，保存时才写入 `BalancerBean`。
- 本批顺手修了 B7b 测试的一个错误期望：`BalancerMixedSourceTest.emptySourcesResolveToEmpty`
  沿用含散装 `proxies=[4,2]` 的 `mixedBean`，而 MIXED 下散装节点本就独立解析
  （`mixedSourcesDedupGroupFirstLooseAfter` 已确认该语义），空分组时仍应返回散装成员；
  改为无散装节点的 bean 测“空来源”。实现（`resolveBalancerMembers`）未动。

### 本批未做

- 7.6 验证阶段未跑：CI（`verify-android.yml`）与真机结果待用户回传，
  不得勾选；`ConfigBuilder` / `BalancerSettingsActivity` /
  `BalancerPriorityActivity` / `DashboardFragment` / AndroidManifest 未经本地编译
  （沙箱 Gradle 插件解析失败，见提交说明），待 CI 验证。
- 本地已验证：Go 侧 `go vet` + `go test -race` 全过；Kotlin 纯模型与三组测试
  （`BalancerTierSchedulerTest` / `BalancerTierPersistenceTest` /
  `BalancerMixedSourceTest`，共 44 项）经离线 kotlinc + javac 桩编译后全绿
  （桩只替 Android/KryoConverters/JavaUtil 等外部依赖，不替被测代码）。

### 偏离记录：tasks.md 7.6 的“验证未通过前不得进入最终边界检查”已被覆盖

用户 2026-11-18 12:07 下令“剩下六批全部做完，绿了合并”，后于 12:09 再改为
“工作词全部完成、都绿了再合并发新预览版”，覆盖 tasks.md 原先“验证未通过前
不得开始下一批 / 不得进入最终边界检查”的串行要求（B7c 的 7.6 亦同）。
本批做完 7.5 即停下汇报，不等 CI/真机验证；7.6 保持未勾选、写“未验证”。
此偏离同步记入提交说明。
