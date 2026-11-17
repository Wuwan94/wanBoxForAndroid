# 任务：移植 OwnBox v26.11.8.5–v26.11.8.20-preview

约定：每批先写测试、再实现、再同步主规范与 change delta，最后自审、提交到
`port/ownbox-26.11.8.20` 分支（不推远程、不碰 main、不发 Release、不删/建 tag、不开 PR）。
每批后立即安排对应的 CI/真机验证阶段；CI 结果未返回并通过前，不得开始下一批。
没有实际验证结果时，不得勾选 CI/真机验证任务。本地只做静态检查；
Python 辅助脚本如需写只放 `tools/diagnostics/`、用 `uv run` 跑，禁止 `python -c`。
不改版本号、不改 `SINGBOX_VERSION`（保持 `v1.15.0-alpha.13-reF1nd`）。

## 0. O8 评估 + 原型验证（只评估，不实现）

- [ ] 0.1 通读上游 `libcore/protocol/autoselect/`（options/outbound/passive/probe/scorer/snapshot）
  及 8.14–8.18 的增删（8.18 删项清单），写出与 wanBox URLTest/LoadBalance 的能力对照表。
- [ ] 0.2 在隔离 scratch 分支（不合入 `port/ownbox-26.11.8.20`）用 mock `OutboundManager` 跑通
  scorer/probe 最小闭环：种子延迟预选、单次失败后备选切换、eTLD+1 站点记忆命中；记录机制成立与否。
- [ ] 0.3 把原型结论（机制成立/不成立、等价点、接入成本量级）写回 `design.md` 附录与本节；
  结论为“不采（本轮）”时不再开实现批次；是否重估须用户另行拍板。
- [ ] 0.4 验证阶段：无 CI 门（无实现代码）；证据为对照表 + 原型运行记录，附在 0.3 的提交说明里。

## 1. B1：O2 + O3 + O4 核查修复

B1 追加规则（用户 2026-11-17 22:18，覆盖“本地只做静态检查”）：做完后先在本地
构建成功再推送分支（只推 `port/ownbox-26.11.8.20`，不碰 main）；构建没过不许 push；
推送后仍不开 PR、不跑发布工作流（开 PR / 跑发布工作流前先问用户）。

- [x] 1.1 先写可复现测试（Go 单测为主，Android 侧为单测）：
  `ExchangeContext.Success` 后 `done()` 被调用（DNS 不挂起）；
  `speedInterval` 未设置时读到 1000；
  探测失败续期时间戳（既有测试要求）、惩罚饱和封顶 65000（`oldHist.Delay + 300` 不回绕）、
  连续两次探测失败清故障史、建连单步超时后转移且计入失败；
  `URLTest` 继续实现 `adapter.OutboundGroup`（O4 防护断言）。
  （已落地：`libcore/dns_box_test.go`、`libcore/protocol/urltest/b1_regression_test.go`、
  `SwitchProfileHotSwitchTest.kt`、`ColdStartMemberOrderTest.kt`、`RouteReorderTest.kt`、
  `LandingIpCacheInvalidationTest.kt`、`libcore/io_test.go`、`libcore/interface_monitor_test.go`。）
- [x] 1.2 O2a：`libcore/dns_box.go` 的 `Success()` 补 `c.done()`，与 `RawSuccess`/`ErrorCode`/`ErrnoCode` 对齐。
- [x] 1.3 O2c：`DataStore.kt:136` 的 `speedInterval` 默认改为 `{ 1000 }`，与设置页一致。
- [x] 1.4 O2d：新增纯节点热切换通道（`switchProfileService` 等价物）：`SwitchActivity` 与
  `QuickToggleShortcut` 的纯节点切换走热切换；路由规则、代理模式及分组设置等配置类变更仍走
  配置重建。先核查 `ProfileSelection.kt`/`CoalescedReload.kt` 的既有去重逻辑，避免双重重载。
  （已落地：`SagerNet.switchProfileService`——运行时走既有 `selectProfile` 热切换通道，
  停止时保存选择并连接；`SwitchActivity.returnProfile` 改走该通道；
  `RootModuleClient.toggleService` 的 START 分支在运行中切另一节点时直接
  `call("node select …")` 热切——已持有 changes 锁，不得调 `selectNode()`。）
- [x] 1.5 O2 其余 8.7 项逐项核查，存在才修：GeoSite/GeoIP 读取器关闭与流式过滤
  （`libcore/geoip.go`、`geosite.go`）、Unxz/assets 句柄与版本路径（`libcore/io.go`、
  `assets_android.go`）、路由拖拽排序索引（`RouteFragment.kt`）、订阅拉取 HTTP 连接池关闭
  （`RawUpdater.kt`）、诊断 OkHttp 复用（`ServiceDiagnosticsManager.kt`）。
  核查结果（存在/不存在 + 证据）写入提交说明；不存在的项关闭，不留空头任务。
  （核查结论：geoip 全量建表且无 `networks.Err()` 检查——已修为流式过滤；
  geosite `loadGeoSiteRules` 从不关闭读取器——已修；Unxz 不关闭输出文件且丢弃关闭错误——已修；
  assets 三处（日志写死 geoipDat、`RemoveAll(version)` 缺 dir 前缀、`.xz` 错误被忽略）——已修，
  yacd.zip 分支不移植（wanBox 已移除 yacd）；RouteFragment from>to range 错位——已提取
  `reorderRuleItems`；RawUpdater 的共享 OkHttpClient `close()` 是有文档的 no-op，
  上游泄漏不适用——无改动；ServiceDiagnosticsManager 在 wanBox 不存在，
  `WanHttp.kt` 的共享 base 设计已满足复用——无改动。）
- [x] 1.6 O2b 核查关闭：确认 `libcore/protect.go` 的 `defer` 关闭 FD 副本已存在，
  加回归测试断言该行为，不改实现。
  （已确认：`handleProtectConn` 已有 defer 循环关闭全部 fd + MSG_CTRUNC 守卫；
  回归断言已存在：`TestProtectClosesReceivedCopyAndKeepsSender`，不改实现。）
- [x] 1.7 O3：`libcore/protocol/urltest/outbound.go` 探测失败路径改为饱和惩罚
  （`penalizeURLTestDelay` 等价物，uint32 求和、封顶 65000），`Time` 仍按既有测试
  要求续期为当前时间（见下文 B1 实施记录“Time 续期冲突”）；
  连续两次探测失败清理故障史；`URLTest.DialContext`/`ListenPacket` 增加单步黑洞超时
  （单步超时取 3 秒——用户 2026-11-17 已拍板，与 wanBox 现有探针超时对齐）；
  `ConfigBuilder.kt` 的 leastPing 组按缓存延迟冷启动预排序。
  （已落地：惩罚增量沿用 wanBox 既有 +300；连续失败计数落在持久的 `URLTestGroup`
  上（`failCount`，probe 与 dial 超时共用），`URLTestOutboundsWithGroup` 接线，
  loadbalance 调用方无归属组、只做饱和惩罚不清史；单步超时仅用于“还有后备可试”
  的尝试，最后一个候选不限时；预排序为 wanBox 本地 `sortBalancerMembersForColdStart`，
  只对 leastPing 策略排序。）
- [x] 1.8 O3 切网 DNS 恢复核查：确认 `networkChangeResetConnections` 关闭时，
  切网后是否清理底层 DoH/HTTP 客户端连接池；缺口确认才补，否则关闭。
  （缺口已确认并补：`UpdateDefaultInterface` 的 false 分支原来只更新默认接口直接返回；
  已按上游 8.13 移植 `routerResetTarget`/`setRouterReset`/`resetRouterNetwork`，
  false 分支仍保留长连接，但重置路由器 DNS/HTTP 连接池；Start 注册、Close 清除。）
- [x] 1.9 O9a 核查：确认 `LandingIpManager.clearCache()` 在节点切换、策略组切换、服务重启时
  是否被调用；组内切换成员（profile 不变）60 秒内是否可能读到旧出口信息。
  缺口确认才补节点/outbound 级校验，否则关闭。
  （缺口已确认并补：组内成员切换时 `cbSelectorUpdate(新成员)` 上报，但
  `id != selectedProxy（组 id）` 使 clearCache() 被跳过，而 `getCachedInfo()` 按
  selectedProxy 读缓存，60 秒内返回旧成员出口 IP。已修两处：
  `getCachedInfo()` 改按 `currentProfile`（实际在跑的成员）读；
  `cbSelectorUpdate` 改为任何上报都清缓存。）
- [x] 1.10 O9c 核查关闭：确认 `BackupFragment.kt` 的 WebDAV 默认路径 `"OwnBox"` 与
  404/空目录回退检索（`OwnBox|ownbox|throne`）真实存在，不改实现。
  （已确认存在：默认路径两处 `:239`、`:369`，回退正则 `:406–408`、`:417`，不改实现。）
- [x] 1.11 O9b 核查关闭：确认底部 StatsBar 已删除（D3），8.9/8.10 三连修无落点，不改实现。
  （已确认：StatsBar 只剩注释提及，无改实现。）
- [x] 1.12 同步主规范（`android-application`、`libcore-integration`）与 change delta/design；
  `git diff --check` 通过；确认版本文件与 `SINGBOX_VERSION` 未变。
  （同步已完成：B1 需求合入 `openspec/specs/` 两主规范；delta 的 dial 超时取值更新为已拍板 3 秒；
  geoip/geosite/Unxz 的 B1 核查结论写入 delta；design.md 增加“B1 实施记录”。
  注意：用户 2026-11-17 22:18 曾要求“本地构建成功后再推送”，2026-11-18 01:25 又改为
  “本地构建完成不了就直接推、用 CI 认证”，以后者为准；`git diff --check` 通过，版本未变。）
- [x] 1.13 提交到 `port/ownbox-26.11.8.20` 分支并推送（用户 2026-11-18 01:25 改主意：
  本地构建完成不了就直接推，改由 CI 验证；不开 PR、不跑 release 工作流），提交说明标注上游 commit
  `4eef8d9`（8.7）、`3edc4eb`（8.13）、`61080e9`（8.15）、`449b35f`（8.5）。
  （已提交并推送；本地构建未通过：2026-11-18 00:19 Gradle 阶段插件
  org.gradle.kotlin.kotlin-dsl-plugins:5.2.0 解析不到，见提交说明与汇报；Go 侧本地已验证通过。）
- [ ] 1.14 B1 验证阶段（未通过前不得开始 B2）：
  CI 为 `.github/workflows/verify-android.yml`（workflow_dispatch），预期 Build LibCore、
  Android build/unit/security、Root module tests、迁移/manifest/下载预算检查均通过；
  真机场景：DNS 解析不再挂起（切节点后解析恢复）、speedInterval 默认 1000（设置页与实际一致）、
  纯节点切换不断现有连接、死节点连续两次失败后不再被选回、黑洞节点单步超时后转移；
  回传证据：CI run 链接 + 真机结果记录（未执行则写明“未验证”，不得勾选）。

## 2. B2：O5 订阅自动更新

- [ ] 2.1 先写可复现测试：预设间隔（15/30 分钟、1/3/6/12 小时、24 小时）读写与摘要格式化
  （“每 24 小时 (1天)”、“已关闭自动更新”）；自定义弹窗分钟/小时/天换算与最小 15 分钟校验；
  初始延迟按到期时间精确计算（无 60 秒截断回归断言）；进订阅页/连接成功触发到期补更，
  且尊重 per-group 失败退避与 `updateWhenConnectedOnly`。
- [ ] 2.2 新增订阅自动更新间隔预设单选 + 自定义单位弹窗 + 人性化摘要；
  更新默认语言与简体中文文案；`autoUpdateDelay` 分钟值语义不变（默认 360）。
- [ ] 2.3 补更触发：进订阅分组页、代理连接成功时检查到期并补更。
- [ ] 2.4 主进程 WorkManager 迁移：**须用户先拍板**（`UpdateTask` 依赖 `:bg` 维护的
  `DataStore.serviceState`）。拍板迁移才做：调度收归主进程原生 `WorkManager`，
  并解决 `serviceState` 来源；拍板保留 `RemoteWorkManager` 则只做 2.2–2.3，
  并在提交说明里记录保留理由。
- [ ] 2.5 同步主规范与 change delta/design；`git diff --check` 通过；
  确认版本文件与 `SINGBOX_VERSION` 未变。
- [ ] 2.6 提交到 `port/ownbox-26.11.8.20` 分支（不推远程），提交说明标注上游 commit `c025f41`（8.6）。
- [ ] 2.7 B2 验证阶段（未通过前不得开始 B3）：
  CI 为 `verify-android.yml`，预期同 B1；
  真机场景：设 15 分钟预设后杀后台/重启手机，调度仍恢复；到期订阅在进订阅页与连接成功时补更；
  到期前不提前更新；关闭自动更新后摘要显示“已关闭自动更新”；
  回传证据：CI run 链接 + 真机结果记录（未执行则写明“未验证”，不得勾选）。

## 3. B3：O6 智能分流适配

- [ ] 3.1 先写可复现测试：系统组件排除名单（下载器、媒体存储、网络栈、Captive Portal 等）
  不被应用层规则错分流；0 规则 `.srs` 占位文件不被当作有效缓存；
  `local:` 本地规则集按核查结果（支持则测解析与多目录，不支持则测明确拒绝/忽略）。
- [ ] 3.2 核查 wanBox 规则集校验现状（`libcore/ruleset.go`、`ConfigBuilder.kt`）与 `local:` 支持情况；
  核查结果写入提交说明；`local:` 若规则集模型不支持则跳过并写明理由，不硬做。
- [ ] 3.3 在应用路由生成层建立集中式系统组件排除名单（以上游 8.8 的
  `SYSTEM_EXCLUDED_PACKAGES` 增量为准），与 `ProfileManager.kt:302-306` 的既有下载器排除合并去重。
- [ ] 3.4 前后端同步校验 `.srs` 实际规则条数，0 规则占位文件视为无效缓存并重新拉取/报错，不得静默当有效。
- [ ] 3.5 同步主规范与 change delta/design；更新默认语言与简体中文文案（如有新增）；
  `git diff --check` 通过；确认版本文件与 `SINGBOX_VERSION` 未变。
- [ ] 3.6 提交到 `port/ownbox-26.11.8.20` 分支（不推远程），提交说明标注上游 commit `d401e93`（8.8）。
- [ ] 3.7 B3 验证阶段（未通过前不得开始 B4）：
  CI 为 `verify-android.yml`，预期同 B1；
  真机场景：系统下载/媒体存储/网络认证页不走代理；0 规则占位缓存被识别为无效并重新拉取；
  回传证据：CI run 链接 + 真机结果记录（未执行则写明“未验证”，不得勾选）。

## 4. B4：O1 约束对表 + 冷启动秒连与快速转移（URLTest 适配版）

- [ ] 4.1 先过约束对表（不写实现代码）：逐条核对 design.md 的 O1 对表——
  间隔 ≥10 秒、空闲 ≥30 分钟、切换不中断现有连接、种子不续期时间戳、不重置探测间隔时钟；
  单次建连超时取值（2 秒 vs 3 秒）写入待拍板清单，**未拍板不得进入 4.2**。
- [ ] 4.2 先写可复现测试（Go 单测）：冷启动按缓存延迟预排序（健康低延迟在前、失败在后）、
  种子过期（超过 `3 * interval`）视为无种子、种子不改写 `history.Time`；
  批量探针单个成功即流式就绪并通知，不等整批跑完；同轮更优节点按容差平滑切换；
  多候选单次尝试超时后转移且计入连续失败，现有连接不断。
- [ ] 4.3 `ConfigBuilder.kt` 的 leastPing 组按缓存延迟冷启动预排序（与 B1 的 1.7 预排序合并实现时，
  以 B1 为准，此处只做 O1 增量：种子过期判定与 idle 后重选）。
- [ ] 4.4 `URLTestOutbounds` 批量探针流式就绪：单个成员成功即写回 history 并 `NotifyUpdated`，
  倒计时仍按整轮结束重置。
- [ ] 4.5 `URLTest.DialContext`/`ListenPacket` 单步超时按拍板值落地（见 4.1），超时后故障转移。
- [ ] 4.6 同步主规范与 change delta/design；`git diff --check` 通过；
  确认版本文件与 `SINGBOX_VERSION` 未变。
- [ ] 4.7 提交到 `port/ownbox-26.11.8.20` 分支（不推远程），提交说明标注上游 commit `cb43ea4`（8.20）。
- [ ] 4.8 B4 验证阶段（未通过前不得开始 B7a）：
  CI 为 `verify-android.yml`，预期同 B1；
  真机场景：冷启动首连秒级（不再盲连列表首位超时节点）、死节点不等 5 秒跑满即被优质节点抢占、
  多候选建连失败 2 秒级切换、检查间隔倒计时不受流式就绪打乱、idle 后首次连接重新预选；
  回传证据：CI run 链接 + 真机结果记录（未执行则写明“未验证”，不得勾选）。

## 5. B7a：O7 混搭来源 + 组隔离出站与代理链

- [ ] 5.1 先写可复现测试：分组（含名称正则）+ 散装节点混搭去重与实时匹配计数、正则语法错误提示；
  多组共享成员不串线（各组独立出站与前置/落地代理链）；`BalancerBean` 记录升级 v5→v6：
  历史 v5 数据可读、新字段默认值正确、`0`（不限）不被默认值覆盖、导出可降级。
- [ ] 5.2 `BalancerBean` 记录升级设计与实现（字段形状由本批定，不照抄上游 autoselect 形状的 v6）；
  成员解析层支持混搭来源与去重；配置生成层按组隔离出站与代理链。
- [ ] 5.3 设置页支持混搭来源编辑（分组选择 + 正则 + 散装节点）与匹配计数展示。
- [ ] 5.4 同步主规范与 change delta/design；更新默认语言与简体中文文案；
  `git diff --check` 通过；确认版本文件与 `SINGBOX_VERSION` 未变。
- [ ] 5.5 提交到 `port/ownbox-26.11.8.20` 分支（不推远程），提交说明标注上游 commit `a49c528`（8.12）。
- [ ] 5.6 B7a 验证阶段（未通过前不得开始 B7b）：
  CI 为 `verify-android.yml`，预期同 B1；
  真机场景：混搭组正确建连、共享成员的两组互不串线、旧版 v5 策略组升级后配置与行为不变；
  回传证据：CI run 链接 + 真机结果记录（未执行则写明“未验证”，不得勾选）。

## 6. B7b：O7 严格手动锁定 + 策略统计页 + 主页横幅

- [ ] 6.1 先写可复现测试：手动锁定后断连/超时不自动切、点「恢复自动」解锁后恢复自动；
  统计页成员状态/报错展示与一键全检触发；主页横幅显示模式/承载节点/延迟，锁定成员不可用时红色高亮。
- [ ] 6.2 严格手动锁定：锁定态只在用户点「恢复自动」后解除；锁定期间的失败 MUST 计入统计，
  MUST NOT 触发自动切换。
- [ ] 6.3 策略统计页（数据来自 Root 快照/Clash API 等既有通道，不另开核心直连）与主页横幅。
- [ ] 6.4 同步主规范与 change delta/design；更新默认语言与简体中文文案；
  `git diff --check` 通过；确认版本文件与 `SINGBOX_VERSION` 未变。
- [ ] 6.5 提交到 `port/ownbox-26.11.8.20` 分支（不推远程），提交说明标注上游 commit `a49c528`（8.12）。
- [ ] 6.6 B7b 验证阶段（未通过前不得开始 B7c）：
  CI 为 `verify-android.yml`，预期同 B1；
  真机场景：锁定节点断连后保持锁定并红字提示、恢复自动后切到健康节点、统计页一键全检刷新成员状态；
  回传证据：CI run 链接 + 真机结果记录（未执行则写明“未验证”，不得勾选）。

## 7. B7c：O7 「按优先级顺序」多层级调度 + 节点优先级二级页

- [ ] 7.1 先写可复现测试：分组/正则定层级（层级 1、层级 2…、保底）、层内按延迟 + 容差择优、
  高层全灭回退下一层、恢复后双重门槛（检查次数 + 无失败持续时间）受控回切、
  流量分配只在当前最低健康层级内分散；节点优先级页编号/拖拽/移除/替换/按延迟排序；
  层级规则持久化随记录升级迁移（v6→v7 如需新字段）。
- [ ] 7.2 「按优先级顺序」模式与层级调度实现；「最快最稳」模式 MUST 隐藏层级相关入口（不展示层级行与优先级列表）。
- [ ] 7.3 节点优先级二级页 + 设置页内嵌节点顺序列表（编号/拖拽/移除/替换，O9d 并入此处）。
- [ ] 7.4 同步主规范与 change delta/design；更新默认语言与简体中文文案；
  `git diff --check` 通过；确认版本文件与 `SINGBOX_VERSION` 未变。
- [ ] 7.5 提交到 `port/ownbox-26.11.8.20` 分支（不推远程），提交说明标注上游 commit
  `0911277`（8.16）、`d76bba7`（8.17）、`e72bb2a`（8.18 相关部分）。
- [ ] 7.6 B7c 验证阶段（未通过前不得进入最终边界检查）：
  CI 为 `verify-android.yml`，预期同 B1；
  真机场景：高层全灭后回退低层可用、恢复后按双重门槛回切（未达门槛不来回跳）、
  优先级页调序即时生效、最快最稳模式不显示层级入口；
  回传证据：CI run 链接 + 真机结果记录（未执行则写明“未验证”，不得勾选）。

## 8. 最终边界检查

- [ ] 8.1 确认各批均有真实 CI 结果；未运行或未通过的 CI 验证项未标记通过。
  Root 真机矩阵未执行，仍未验证。
- [ ] 8.2 确认 delta spec 已准确合入主规范（`android-application`、`libcore-integration`），
  并区分自动校验结果与待 CI/真机验证事项。
- [ ] 8.3 确认版本文件、`SINGBOX_VERSION`、稳定版 v3.0.14 行为未变；
  未创建 PR、Release 或 tag；未推送 main。
