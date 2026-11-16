# 任务：批次 4（本轮只完成规范）

## 4a 图形化策略组编辑

- [x] 1.1 核查模式标志读写、现有开关与 Balancer 字段，完成对照表
- [x] 1.2 定义成员/参数、身份锁定、outbounds 隔离、Root 校验及故障测试缝
- [ ] 2.1 实现 Selector/URLTest 创建和 tag/type 锁定
- [ ] 2.2 实现成员与五项参数编辑、合法性校验及取消语义
- [ ] 2.3 接入新增的不可变候选快照 validate/apply、revision 冲突、新鲜状态确认及编辑器内缺失引用阻断（普通重载保留确认链）
- [ ] 2.4 在既有配置/Root 测试缝覆盖分区不变、回滚、停止态与 Fail-Closed
- [ ] 2.5 同步 android-application 主规范并执行 git diff --check、Python 工具门禁；推送/提交仅在后续实现授权时进行
- [ ] 2.6 留痕：明确本节门禁失败只修复 4a、失败不得进入 4b。实现后运行 Verify CI 的 verify-android（JVM/instrumentation）与 Build LibCore（含 Go Runtime 测试）门禁，实际 job 名以当时 workflow 为准；Root 真机验证预检失败、缺引用、revision 冲突、运行重载/停止不启动及清理失败阻断。回传 CI run/job 结果、脱敏 revision/state 与界面证据；任一 CI/真机项失败须附失败证据并只修复 4a，未通过或未收到完整结果不得进入 4b。

## 4b 资源页统一呈现

- [x] 1.3 核查两个 Fragment 的分组/节点及订阅单项/所选/全部更新入口
- [x] 1.4 定义同页呈现、来源消歧、改名不丢节点/引用及 pager/UI 测试缝
- [ ] 2.7 统一资源页并保留节点操作与三类订阅更新、进度和失败反馈
- [ ] 2.8 实现显示层消歧及稳定 ID 改名规则
- [ ] 2.9 在 pager/Android UI 测试缝验证重名、改名、更新范围与路由/成员引用不变
- [ ] 2.10 同步主规范并执行静态/Python 门禁
- [ ] 2.11 留痕：区分无需新增 Go 专项测试与 CI 仍运行既有 Go 门禁。实现后运行 Verify CI 的 verify-android（含工作流级 Build LibCore/Go 门禁）；真机用虚构同名本地/订阅节点验证一页可达、三类更新及改名前后 ID/路由一致，回传 run/job、截图与脱敏 ID 对比。失败时只修复 4b、不得进入 4c；回传失败 job/步骤及脱敏 revision/state（适用时）和截图/ID 证据，CI/真机均通过且证据完整后方可推进。

## 4c 迁移说明规范化

- [x] 1.5 核查 BackupFragment、PortableBackup、BackupRestore、备份文档与升级约定，区分显式恢复与重置式迁移
- [x] 1.6 定义双向兼容、结果检测、备份优先、回滚/Fail-Closed 与必读说明
- [ ] 2.12 实现必读说明及备份前置确认
- [ ] 2.13 保持版本化双向备份；旧导出→新导入时旧格式可表示字段等值、旧格式没有的字段采用明确缺省语义；新导出→旧导入时，字段无法完整表示或目标读取能力未验证，须在写入前明确拒绝并报告具体字段及原因
- [ ] 2.14 实现迁移结果报告、事务失败恢复与核心结果分开呈现
- [ ] 2.15 复用备份兼容/跨版本/故障注入测试按方向矩阵验证旧→新可表示字段等值及缺失字段缺省语义、新→旧无法完整表示或读取能力未验证时写入前拒绝、未知格式拒绝、取消及晚期错误两库回滚
- [ ] 2.16 同步主规范并执行静态/Python 门禁
- [ ] 2.17 留痕：没有新增模块逻辑时无需新增 Go 专项测试，但 Verify CI 仍运行工作流级 Build LibCore/Go 门禁。实现后运行 verify-android 迁移与 instrumentation 门禁；用批次前后配套 APK/模块及虚构备份在真机按兼容矩阵双向导入，旧→新核对可表示字段等值及旧格式没有字段的明确缺省语义，新→旧核对全部待保真字段等值，无法完整表示或读取能力未验证则核对写入前拒绝，并注入备份失败/取消/事务失败检查原数据；回传 run/job、字段比对、错误与恢复证据。失败时只修复 4c、不得推进后续批次；回传失败 job/步骤、脱敏 revision/state（适用时）及恢复证据，CI/真机均通过且证据完整后方可完成。

本轮规范验收：五个文件齐全，所有 1.x 仅表示规范完成；全部 2.x 未实现。本轮只运行用户指定 Python 工具与空白检查，不执行 Android/Go 构建，不提交；CI/真机结果不得预先勾选。双轴审查与本地门禁的实际结果见复审/修复日志。

## 本轮复审修复记录

- Standards P1 — proposal.md:37：说明本轮未改生产能力所以暂不同步主规范，并要求任一实现批次完成前同步；删除“直到实施/归档”承诺。
- Standards P1 — tasks.md:12、22、33：三个批次门禁均规定失败只修复当前节、失败不得推进，并回传 CI run/job、脱敏 revision/state 及对应失败/真机证据。
- Standards P2 — tasks.md:22、33：明确无需新增 Go 专项测试不代表跳过 CI；Verify CI 仍运行工作流级 Build LibCore/Go 门禁。
- Spec P1 — design.md:20、spec.md:23、29、35、77：区分固定主组运行时 tag `proxy`、非主组 trim 名称、直接节点 `g-<id>`、链式节点 `chainTag-<id>` 与稳定实体 ID 引用键。
- Spec P1 — design.md:32、spec.md:93、99：以方向明确的目标格式兼容矩阵验收：旧→新可表示字段等值、旧格式没有的字段采用明确缺省语义；新→旧无法完整表示或读取能力未验证时，在写入前报告具体字段及原因并拒绝，不承诺无条件双向保真。
- Spec P1 — design.md:26、spec.md:39、45：策略组编辑缺失引用进入编辑器错误、不调用 continueOutboundConfirmation；普通服务启动/重载保留已有确认链。
- Spec P2 — design.md:13、24、spec.md:39：声明 validate → apply 为新增接口，固定不可变候选快照字节/摘要、输入版本与 expectedRevision，并在 apply 前重验及由模块再校验。
- Spec P2 — design.md:18、spec.md:7、19：定义新增成员图顶点/边、回边成员错误定位及写入/快照前阻断；注明当前 resolveChainInternal 缺失成员采用跳过且没有既有循环错误契约。

- F1 · P1 — proposal.md:24：区分稳定实体 ID、非主组路由兼容名称、固定主组运行时 tag `proxy` 与直接/链式节点 tag，删除无条件 tag 概括。
- F2 · P2 — proposal.md:27；tasks.md:29、31、33、43：旧→新采用可表示字段等值及缺失字段缺省语义；新→旧无法完整表示或读取能力未验证时写入前报告并拒绝，统一任务正文与留痕。
- F3 · P2 — specs/android-application/spec.md:23、31–35：恢复新建路由兼容名称 trim 后 MUST 非空，新增空/仅空白名称提交被拒绝并定位字段的场景，与固定 `proxy` 分开。


## 4a 修复轮：统筹方 F3 裁决原文与依据

裁决：要求「规则按稳定 ID 引用组、编辑器可选组作直接成员」**不成立**——wanBox 既有模型里规则按 outbound tag 引用、Selector/URLTest 成员就是节点实体、组间引用走负载均衡的目标组，这三点是仓库既有契约，不在本批改动范围。但以下部分成立，必须修：
1. 新建组必须是与既有组同等的一等组：出现在组列表、负载均衡目标组候选、快照组集合、缺失引用检查覆盖内。逐项核查现状，缺哪补哪，补测试证明（新建组可被负载均衡选为目标组、生成配置含其 outbound 且 tag 正确）。
2. 全链路审计组/节点 ID 命名空间不得混用：凡是同时可能拿到两种 ID 的地方必须用带类型 ID 或显式命名空间（编辑器图校验已用 EntityId，继续核查成员选择、capture、patch 路径），补组/节点同号不互相误认的测试。
3. 把本条裁决原文记入 `openspec/changes/group-editor-and-resource-unification/tasks.md` 的 4a 留痕（注明统筹方裁决与依据），供复审对照。

依据：统筹方 FIX_BRIEF_BATCH4_4A.md 的 F3 与既有 BalancerBean / RuleEntity / 节点成员契约。组列表及目标组 UI 已使用 groupDao.allGroups；新增组仍创建同一 ProxyGroup 实体。补齐冻结快照的组集合与负载均衡按编辑器稳定节点成员取值；不扩展规则或直接成员模型。CI/真机未验证，不勾选门禁。

## 4a 第二修复轮：统筹 G1–G6 裁决留痕（2026-11-16）

- 接受 astra Standards 1×P2、Spec 1×P1 + 3×P2；此前 F2/F4 完成声明不足，按 G1–G5 修复，不推进 4b/4c。G1 回滚前 fresh status 仅允许 baseline revision 或已知本次 apply revision，外部 revision/丢失响应导致归属未知均保留 journal 并 cleanup_required，禁止覆盖外部配置。
- G2 普通 ConfigBuilder 最终补丁使用既有 resolvedGroupUrlTestUrl 结果；G3 候选空 override 使用全局 URL；G4 双语 cleanup 明确状态不确定、可能部分生效及先恢复；G5 身份锁定读取合入 IO。delta 与主规范同步。
- 测试先行著述新增 CandidateHost 最后检查→apply 竞态、丢失响应阻断及真实 ConfigBuilder 旧默认 URL 输出/清空 override 后候选与保存重建相等用例；Kotlin 待 CI，不勾选验收。
- G6 接缝调查：现有 editorSnapshotProcess 与 commandProcess 能替换进程，但 fresh status 还走全局 channelGate/channel transport，完整 applyGroupDraft 同时依赖真实 Room、持久 journal、RootModuleDataUpdate 与模块进程。现有 TUN/DNS 测试仍仅走真实 ProxyInstance→RootModuleSnapshot→patchSnapshot；缺 applyGroupDraft→readEditorBaseline 的 current 前后核验及完整 validate/apply/fresh-status 链，不能宣称完整链覆盖。未为本轮扩大生产接缝或用字符串伪测；须在 CI/Root 真机补验：有效 TUN generation + 未应用 DNS 设置，提交后比对非 outbounds 完全不变，并测试 current 在读取中变化时拒绝。G3 capture 全局值接线也待该完整链验证。

## 4a 第三修复轮续跑：统筹 H1/H2 裁决留痕

- H1（SP1·P2）：cleanup_required 后保持页面阻断、禁用草稿表单；提交入口和本地校验反馈优先检查 journal/阻断态，普通错误不得覆盖为未应用。仅恢复接口成功、journal 清除及 fresh status 确认已知状态后解锁，页面重建保留阻断。
- 测试先行新增真实页面/持久 journal 的空成员与非法 interval 重复提交用例；Kotlin/instrumentation 待 CI，不勾选验收。该测试从 durable cleanup 边界进入，不模拟模块 apply 故障；完整进程恢复链仍待 CI/Root 真机。
- H2（S1·P2）：GroupSettings 身份锁定标志合入后台加载并保存重建状态；PolicyGroups 后台加载负载均衡组 ID 集合，选择回调只消费结果。同页探测 URL 的配置库读取也移入 IO。线程/交互效果待 instrumentation/真机。
- 修正 G4 旧判断：双语文案修改并不足以闭环，SP1 指出的后续本地校验确有同类假报，本轮补阻断状态处理。未推进 4b/4c。
