# 适配移植 OwnBox v26.11.8.5–v26.11.8.20-preview 的功能与修复

## 为什么

OwnBox `v26.11.8.4-preview..v26.11.8.20-preview` 是 16 个预览版本、32 个提交、128 个文件的增量，
改动高度集中于策略组（autoselect 统一引擎、BalancerBean 记录版本 v6→v8、策略统计与层级调度）。
wanBox 的 Core/Root 架构、配置生成和持久化模型已大幅分叉（Root-only、瘦管理器 + Root 模块同版成套、
无 autoselect 出站、无 SmartRouteRules、无底部 StatsBar），必须逐项核查同构缺陷是否存在、再决定采纳、
适配或跳过，不能直接 cherry-pick。

上游依据为 OwnBox `v26.11.8.4-preview`（`aa93dfb2563e3b2b4dff35d2ca40c86942842ae8`）
至 `v26.11.8.20-preview`（`cb43ea4d70976c4b34c40b0ffef4292cc5e05f89`），比较范围为
[`v26.11.8.4-preview...v26.11.8.20-preview`](https://github.com/Own716/OwnBoxForAndroid/compare/v26.11.8.4-preview...v26.11.8.20-preview)。
8.4 基线已由 `port-ownbox-26-11-8-4` change 移植完毕，本次只看 8.4 之后增量。

## Diff 核对结果（实际数字 vs 工作词）

本地仓库 `~/workspace/wanbox-port/ownbox` 先 `git fetch --tags origin` 拿到全部 tag，
再补取 8.20 提交缺失的父提交 `377d7ee`（`docs: empty release notes for v26.11.8.19-preview`，
其父为 8.19 的 `1df91d7`），父链完整后核对如下：

| 数字 | 工作词 | 实际核对 | 结论 |
|---|---|---|---|
| 版本数（8.5–8.20） | 15 个 | **16 个**（8.5、8.6、8.7、8.8、8.9、8.10、8.11、8.12、8.13、8.14、8.15、8.16、8.17、8.18、8.19、8.20） | **不符**，少算 1 个 |
| 提交数 | 32 个 | **32 个**：`8.4..8.19` 线性 30 个 + `377d7ee` + `cb43ea4`（8.20） | 相符 |
| 文件数 | 128 | **128**（`git diff --name-only 8.4..8.20 \| wc -l`） | 相符 |
| 行数 | 约 +14800/−2400 | **+15728/−2383**（tree diff） | 删除相符（−17）；新增比工作词多 **928 行（+6.3%）**，工作词为低估 |
| 最大单版 | — | 8.14：45 文件 +6951/−1153；8.12：32 文件 +3159/−128；8.15：39 文件 +1456/−540 | 8.14 占新增约 44% |

补充说明：

- 浅克隆假象：初次 `fetch --tags` 后 `git rev-list --count 8.4..8.20` 只返回 1，
  是因为 `cb43ea4` 的父提交 `377d7ee` 当时本地缺失（shallow 边界）；补取后父链完整，
  32 个提交的计数以父链核对为准，不是 rev-list 的 1。
- 逐版增量 diffstat 之和大于总 diff，因为 8.11 与 8.19 均为回退/重发，中间改动被抵消。
- 8.11 净值：`8.10..8.11` 为 2 文件 +8/−3，不是严格净零，残留为版本号与 CHANGELOG 行。
- 8.19 净值：`8.18..8.19` 为 2 文件 +7/−3，同样只剩版本号与 CHANGELOG 行；IPv6 改动（`a040b2f`，+149/−29）
  已由 `a0008e5` 完整回退。
- 8.20 超时数字核实：8.19 的 `libcore/protocol/autoselect/probe.go:23` 定义
  `probeTimeout = 5 * time.Second`；8.20 把 `DialContext` 单次尝试超时改为
  `defaultFastFailoverTimeout = 2 * time.Second`（`options.go` 新增常量），
  探针 URL 测速本身仍用 5 秒 `probeTimeout`（`probe.go:303` 未变）。
- “Kryo v6→v8”核实：OwnBox 在 8.20 仍用 `kryo:5.2.1`（与 wanBox 相同）；
  v6（8.14 `bd3f502`）、v7（8.16）、v8（8.18 `e72bb2a`）是 `BalancerBean` **记录版本号**
  （`output.writeInt(N)`），不是 Kryo 库版本。wanBox 的 `BalancerBean.java` 同样存在，
  记录版本为 **v5**（`output.writeInt(5)`），工作词“wanBox 无此 Bean”不成立。

## 开工适用性评估

| 项目 | wanBox 当前状态与代码位置（核查证据） | 适配判断 |
|---|---|---|
| O1 冷启动秒连与快速转移（8.20） | wanBox **无 autoselect 出站**（`libcore/protocol/` 只有 http、juicity、loadbalance、urltest、vless），8.20 的 `initial_delays` 种子、`recordProbeProgress` 流式就绪、`defaultFastFailoverTimeout` 均无直接落点。wanBox 自家约束已核实存在：检测间隔 `coerceAtLeast(10L)`（`ConfigBuilder.kt:168`）、空闲超时默认 `"30m"`（`DataStore.kt:392,437-438`）、切换不中断现有连接 `interruptExist = false`（`ConfigBuilder.kt:986,1008`）。另：`URLTest.DialContext` 目前对全部候选无超时逐个尝试（`urltest/outbound.go:237-270`），批量探针超时为 3 秒（`:679`）。 | **部分采（适配）**：不移植 autoselect 种子，改为 URLTest 适配版——冷启动按缓存延迟预排序候选、批量探针流式就绪、建连单次尝试超时收紧；超时取值（2 秒 vs 3 秒）与“种子是否触动探测间隔时钟”须先过 B4 约束对表，并由用户拍板，见 design.md |
| O2a DNS `ExchangeContext.Success` 未发完成信号（8.7） | **同构缺陷存在**：`libcore/dns_box.go:156-162` 的 `Success()` 只写 `addresses`，不调 `c.done()`；而同文件 `RawSuccess`/`ErrorCode`/`ErrnoCode` 均调 `c.done()`。 | **已采**，纳入 B1 |
| O2b `protect.go` FD 副本泄漏（8.7） | **已有等价修复**：`libcore/protect.go` 已有 `defer` 循环关闭全部接收到的 fd，另有 `MSG_CTRUNC` 兜底与 panic 恢复，比上游只多不少。 | **跳过（已有）**，B1 只加回归断言，不改实现 |
| O2c `speedInterval` 默认 0ms→1000ms（8.7） | **同构缺陷存在**：`DataStore.kt:136` 的 `speedInterval` 未给默认值 lambda；`Preferences.kt:26-31` 的 `stringToInt` 默认 `{ 0 }`，未设置时确为 0ms。 | **已采**，纳入 B1 |
| O2d 纯节点切换独立为 SWITCH_PROFILE 热切换（8.7） | **不存在**：wanBox 无 `switchProfileService`/`SWITCH_PROFILE`（全仓 grep 无命中）；`SagerNet.reloadService()` 即 `configurationReload.request()`（`SagerNet.kt:352`）。上游改动只是 `SwitchActivity`/`QuickToggleShortcut` 把 `reloadService()` 换成 `switchProfileService(profileId)`。 | **部分采（适配）**：新增纯节点热切换通道，配置类变更仍走配置重建；须先核查 `ProfileSelection.kt`/`CoalescedReload.kt` 的既有去重逻辑，避免双重重载，纳入 B1 |
| O2 其余 8.7 项（#6 GeoSite/GeoIP 关闭与流式过滤、#7 Unxz/assets 句柄与路径、#8 路由拖拽索引/订阅 HTTP 池/诊断 OkHttp 复用/实况通知 DB 查询缓存） | 未在任务 O2 四项内逐项列出，但同属 8.7 提交；wanBox 的 `libcore/geoip.go`、`geosite.go`、`io.go`、`assets_android.go`、`RouteFragment.kt`、`RawUpdater.kt` 须逐项核查。wanBox **无 `LiveUpdateFormatter.kt`**，实况通知路径不同。 | **纳入 B1 核查**：存在才修，不存在则在 B1 验证里关闭 |
| O3 死节点滞留修复（8.13） | **同构缺陷存在（变体）**：`libcore/protocol/urltest/outbound.go:705-711` 探测失败时存 `Time: time.Now()`（续期旧时间戳）且 `Delay: oldHist.Delay + 300` 无钳制（uint16 可回绕）；无连续失败计数、无 `penalizeURLTestDelay` 式饱和惩罚；`DialContext` 无单步黑洞超时。冷启动预排序：`ConfigBuilder.kt:978` 的 leastPing 直接用 `memberTags` 原顺序，无延迟预排序。切网 DNS 恢复：wanBox 有 `networkChangeResetConnections` 门控的 `ResetNetwork`（`libcore/interface_monitor.go`），但“不重置连接模式下只清 DoH/HTTP 池”是否覆盖须 B1 核查。 | **部分采**，纳入 B1：饱和惩罚（封顶 65000）+ 失败不续期旧时间戳 + 连续两次失败清故障史 + 建连单步超时 + 冷启动预排序；DNS 恢复按核查结果定 |
| O4 策略组被路由绕过修复（8.15） | **无同类缺陷**：wanBox 无 autoselect 出站；`61080e9` 移除的是 `AutoSelect` 的 `_ adapter.OutboundGroup / _ adapter.URLTestGroup` 断言（sing-box Router 会解包 group 而绕过其 `DialContext` 内的学习/记忆/故障转移）。wanBox 的 `URLTest` 实现 `OutboundGroup`（`libcore/protocol/urltest/outbound.go:49`）是上游 sing-box 的既有设计，选路发生在 group 接口本身，不存在“解包绕过学习”的问题。 | **跳过（无此缺陷）**，B1 加一条防护测试：`URLTest` MUST 继续实现 `OutboundGroup`，不得照抄上游的移除 |
| O5 订阅自动更新（8.6） | 致命空转不存在：`SubscriptionSchedule.plan()` 按到期时间算精确秒级初始延迟，无 60 秒截断（上游旧 bug `if (minInitDelay > 60) minInitDelay = 60` 在 wanBox 无对应代码）。冷启动恢复存在：`BootReceiver.kt:27`、`GroupManager.kt:89-117` 调 `reconfigureUpdater()`。**但**调度仍在 `:bg` 跨进程：`SubscriptionUpdater.kt:61` 用 `RemoteWorkManager` + `RemoteCoroutineWorker`，正是上游 8.6 修复 #2 移除的模式；且 `UpdateTask` 依赖 `:bg` 维护的 `DataStore.serviceState`（`SubscriptionUpdater.kt:90-91` 注释）。预设/自定义弹窗与人性化摘要不存在：无 `SubscriptionAutoUpdateDialog`，`subscriptionAutoUpdateDelay` 默认 360（`DataStore.kt:456`）。进订阅页/连上代理触发到期补更不存在（`GroupFragment.kt` 只有手动更新入口）。 | **部分采**，纳入 B2：预设 + 自定义弹窗 + 摘要、到期补更触发；主进程 WorkManager 迁移为**待用户拍板项**（涉及 `serviceState` 来源，见“需用户拍板”） |
| O6 智能分流分类重构（8.8） | wanBox **无 `SmartRouteRules.kt`/`classifyApps`**，无可移除的“默认加载预置包名表”行为；`proxy_packagename.txt` 只在 `AppManagerActivity.kt:349,382-390` 的手动“需要代理的应用”列表里作空内容回退。系统排除：`ProfileManager.kt:302-306` 已在按应用路径排除下载器（`.downloads` / `.downloads.ui`），但无集中式 `SYSTEM_EXCLUDED_PACKAGES`（媒体存储、网络栈、Captive Portal 不在其中）。规则集 0 规则占位：wanBox 无 `RuleSets.kt`/`RuleSetCatalog.kt`，`local:` 在路由层 grep 无命中；校验逻辑在 `libcore/ruleset.go` 与 `ConfigBuilder.kt`，须 B3 核查。 | **部分采（适配）**，纳入 B3：集中式系统组件排除名单 + 规则集实际条数校验；`local:` 支持按 B3 核查结果定（若规则集模型不支持则跳过并写明理由） |
| O7 策略组增强（8.12/8.14/8.16/8.17） | wanBox `BalancerBean` 为 v5 记录，策略只有 leastPing/leastLoad/random/roundRobin/failover/stable/consistent_hash（`BalancerBean.java:21-28`）；无混搭来源、无层级（tiers）、无严格手动锁定、无 `StrategyStatsActivity`、无节点顺序页（只有 `BalancerSettingsActivity.kt`）。上游 8.18 已删 17 项冗余选项与 300 节点上限——wanBox 无这些死选项，无需清理。 | **部分采，拆 3 小批**：B7a 混搭来源 + 组隔离出站与代理链；B7b 严格手动锁定 + 策略统计页 + 主页横幅；B7c 「按优先级顺序」多层级调度 + 节点优先级二级页。BalancerBean 记录升级（v5→v6+）与迁移须随 B7a 单独设计，不得照抄上游 autoselect 形状的 v6 字段 |
| O8 autoselect 统一引擎（8.14） | Go 侧 `libcore/protocol/autoselect/`（`options.go`、`outbound.go`、`passive.go`、`probe.go`、`scorer.go`、`snapshot.go` 及测试）是 OwnBox 自研引擎，wanBox 完全没有对应模块；且上游 8.18 已回退其中一部分（多样本平滑、全局冷却、8 节点上限等），引擎仍在收敛中。 | **不采（本轮）**，只做评估 + 原型验证，结论写回 design.md；不替换 wanBox 现有 URLTest/LoadBalance 组引擎 |
| O9a 实况通知胶囊国家不更新 + `:bg` 出口 IP 缓存未按节点 ID 校验（8.5） | wanBox 无 `LiveUpdateFormatter.kt`；但 `LandingIpCache`（`utils/LandingIpCache.kt`）已按 profile id + generation ticket + 60 秒 TTL 校验，强于上游修复前。**残留疑问**：缓存键是 profile id，策略组内切换成员不换 profile，60 秒内可能读到旧出口信息；`LandingIpManager.clearCache()` 的调用点须 B1 核查。 | **部分采（待核查）**：B1 核查 `clearCache()` 调用链，缺口确认才补节点/outbound 级校验；否则关闭 |
| O9b 底部状态栏三连修（8.9/8.10） | **无同款状态栏**：wanBox 已删除底部 StatsBar（D3；`DashboardFragment.kt:95`、`layout_main.xml:19` 注释），连接测试/落地 IP 已迁到首页「连接检查」卡片。 | **跳过（无此 UI）** |
| O9c WebDAV 默认备份路径统一 + 云端恢复回退检索历史目录（8.7） | **已有等价实现**：`BackupFragment.kt:239,369` 默认路径已是 `"OwnBox"`；`:406-417` 已有 `OwnBox|ownbox|throne` 回退检索正则。上游 8.7 只是把同一逻辑抽成 `WebdavBackupPaths` 对象。 | **跳过（已有）**，B1 只核查 404/空目录回退路径真实存在 |
| O9d 设置页内嵌节点顺序列表与「节点优先级」二级页（8.15/8.17） | 与 O7 同源（`BalancerNodeOrderActivity`、`BalancerNodeListPreference`），wanBox 均无。 | **并入 O7**，随 B7c 评估 |
| 排除 8.11 | `3890e45` 完整回退 `a53dccc` 的 Throne 策略组实验移植；净值 2 文件 +8/−3（版本/CHANGELOG 残留），无功能 carry-over。 | **排除**，不移植 |
| 排除 8.19 | `a0008e5` 完整回退 `a040b2f` 的 IPv6-prefer 改动；净值 2 文件 +7/−3（版本/CHANGELOG 残留），与 8.18 同码重发。 | **排除**，不移植 |
| 排除 BalancerBean Kryo v6→v8 | 见上：是记录版本不是库版本；wanBox Bean 在 v5 且 Kryo 库同为 5.2.1；v6–v8 字段与 autoselect 形状绑定。 | **不适用为独立项**；仅随 O7 字段采纳时才设计对应的记录升级与迁移 |

## 本次变更内容与批次

1. 0：O8 评估 + 原型验证（只评估，不实现、不替换组引擎）。
2. B1：O2 核查修复（DNS done、speedInterval、SWITCH_PROFILE、8.7 其余项）+ O3 死节点滞留 + O4 核查关闭 + O9a/O9c 核查关闭。
3. B2：O5 订阅自动更新（预设/自定义/摘要/补更触发；主进程迁移待拍板）。
4. B3：O6 智能分流适配（系统排除名单 + 规则集条数校验；`local:` 按核查定）。
5. B4：O1 约束对表先行，再做 URLTest 适配版冷启动秒连与快速转移。
6. B7a：O7 混搭来源 + 组隔离出站与代理链（含 BalancerBean 记录升级设计）。
7. B7b：O7 严格手动锁定 + 策略统计页 + 主页横幅。
8. B7c：O7 「按优先级顺序」多层级调度 + 节点优先级二级页 + 内嵌节点顺序列表。
9. 最终边界检查。

每批必须先完成该批测试、实现和主规范同步，再自审、提交；只提交到 `port/ownbox-26.11.8.20` 分支，
不推远程、不碰 main、不发 Release、不删/建 tag、不开 PR。CI 结果未返回并通过前，不得开始下一批。
没有实际验证结果时，不得勾选 CI/真机验证任务。

## 影响与边界

- 受影响主规范：`android-application`、`libcore-integration`。不涉及 theme-system、版本编码或构建链。
- 基线：wanBox main `4627a6f`（正式版 v3.0.14 / APK 10130 / 模块 2026；最新 main 预览 v3.0.17-preview.1，
  APK 10156 / 模块 2031）。**不改版本号、不改内核版本**：`nb4a.properties` 的 `SINGBOX_VERSION`
  保持 `v1.15.0-alpha.13-reF1nd`。
- 新增用户可见文案至少更新默认语言与简体中文；其他已存在语言继续通过默认资源回退并保持 UI 一致。
- wanBox 自家约束与上游冲突时以自家为准，取舍见 `design.md`：URLTest 检测间隔 ≥10 秒、空闲超时 ≥30 分钟、
  切换不中断现有连接、Fail-Closed 红线、`default` 保真、版本 CAS 与恢复校验链。
- 本 change 只写规格，不写实现代码，不进入实现批次。
