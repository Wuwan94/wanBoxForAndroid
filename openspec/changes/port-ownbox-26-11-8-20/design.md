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
  不照抄上游 autoselect 形状的 v6）；B7c 层级持久化如需新字段再升至 v7。
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
| 冷启动：正在按缓存延迟预选 | Picking fastest known node… | 正在按缓存延迟预选最快节点… |

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
