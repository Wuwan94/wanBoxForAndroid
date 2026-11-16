# 设计：策略组编辑与资源统一

## 基线与模块↔界面对照（2fca51a）

| 模块/数据能力 | 当前界面与代码事实 | 缺口/目标 |
|---|---|---|
| 主组 selector/urltest/loadbalance | DataStore.isGroupUrlTest/isGroupLoadBalance 读 configurationStore 的 group_<id> 标志，ConfigBuilder 消费；GroupSettingsActivity.init 读入缓存，serialize 却将两标志写 false | group_preferences 只有 groupIsSelector 开关，没有 URLTest/LoadBalance 开关；补 Selector/URLTest 创建和成员编辑，禁止普通编辑重置已有模式 |
| URLTest 参数 | GroupSettingsActivity 加载并保存 URL override、interval、tolerance、idle timeout、interrupt existing；XML 无对应可见参数编辑项 | 补五项参数可见编辑；空 URL override 保持现有继承语义 |
| Balancer 实体 | BalancerSettingsActivity 与 balancer_preferences 已编辑名称、类型、目标组、策略、URL、interval、tolerance/unit、前后置代理/启用开关、名称 include/exclude；显式成员列表支持选择/删除/排序 | 保留现有编辑能力；它是独立实体编辑器，不等于主组 LoadBalance 开关；本批不扩展 LoadBalance 能力 |
| 资源节点 | ConfigurationFragment 用分组 pager 展示本地及订阅组内节点，含选择、编辑和测速 | 不误称只显示本地节点；统一页保留节点操作 |
| 资源分组/订阅 | GroupFragment（layout_group）展示分组（含 BASIC/SUBSCRIPTION）；订阅有单项、所选、全部更新入口与进度/流量信息 | 与节点页同一资源入口，保留三类更新入口及失败反馈 |
| 运行策略组 | PolicyGroupsActivity 读取 Clash API 快照，支持浏览/选择/测速 | 运行快照不作为数据库编辑真相；增加本地编辑可达入口 |
| 配置应用 | RootModuleClient.startOrReload → startOrReloadLocked → RootRouteBuildSnapshot/ProxyInstance → RootModuleSnapshot → config apply | 当前 App 不先发 config validate；编辑器的 validate → apply 是新增接口工作，apply 内部仍独立重验 |
| 备份 | BackupFragment 本地/分享/WebDAV/自动备份复用 BackupHelper，PortableBackup 格式 2，BackupRestore 全量解析/引用检查与跨两库单连接事务 | 规范化迁移说明与双向兼容验收，不重置数据库 |

## 数据流与身份边界

本地编辑以稳定实体 ID、分组模式及参数为真相，不从 Clash 运行快照反写数据库。稳定实体 ID 是数据库主键和节点成员引用键（节点/组 ID 独立命名空间，规则仍沿用既有 outbound tag 契约），不等同于显示名或运行时 outbound tag。Selector/URLTest 创建时选择类型并校验身份及参数；成员有效性由编辑器新增的图校验负责：顶点为策略组和节点实体 ID，有向边为组到其成员；从候选组可达自身即为循环，错误定位到导致回边的成员 ID/编辑行。空成员、重复成员、缺失实体、循环和参数不合法均在持久化或生成候选快照前阻断并定位字段。ConfigBuilder 当前 resolveChainInternal 对缺失链成员采用跳过行为，不提供该成员图的循环错误契约；该校验属于本批新增能力。URLTest 间隔为正秒数、容差为非负毫秒数、idle timeout 为核心支持的非负时长；URL 非空时仅接受合法 HTTP(S) URL，空 override 沿用已有继承。取消编辑不落盘。

区分用户可见/路由兼容名称与运行时 outbound tag：主组 Selector、URLTest、LoadBalance outbound 固定使用 `proxy`；非主组用于路由兼容的名称取 trim 后组名。节点链路 outbound tag 由 buildChain 根据链路上下文生成：直接节点使用 `g-<id>`，链式节点使用 `chainTag-<id>`。这些 tag 都不是数据库键；稳定实体 ID 才是数据库和节点成员引用键；规则仍沿用既有 outbound tag 契约。新建策略组的路由兼容名称和类型创建成功后只读；主组运行时 tag 仍为固定 `proxy`，不随名称变化。界面不把资源重命名当成组身份或运行时 tag 重命名。不对全部历史分组新增禁止改名规则；本地/订阅节点显示名可改，不能由显示名派生持久引用。重名只追加来源显示后缀；来源也重名时追加实体 ID。不能把显示标签用作数据库键、路由键或 Provider 标签。

成员选择保存稳定 ID 及顺序，转换成既有 outbound tags；4a 修改仅限配置 JSON 的 outbounds 分区，其他分区结构和值、路由生成及用户规则顺序保持相同。RootModuleSnapshot 外层 expectedRevision 等并发控制元数据不属于配置分区；不得绕过 revision 冲突校验。

现有普通配置重载路径由 SagerNet.configurationReload → CoalescedReload → RootModuleClient.startOrReload(false) 直接构建快照并发出 `config apply`；Runtime.Apply 内部执行 Stage → Validate 后应用，但没有“先 validate 命令再 apply 命令”的既有接口。策略组编辑必须新增专用提交接口：在既有变更串行锁内捕获一次不可变 CandidateSnapshot，包含规范化配置 JSON、数据库/引用输入版本和基于当前模块状态读取的 expectedRevision；validate 与 apply 两条命令都从该对象读取完全相同的字节/摘要及 expectedRevision，不得重新读取数据库或重建配置。validate 成功后、apply 前再次确认输入版本未变且模块 revision 仍等于 expectedRevision；任一变化都拒绝并提示重读重试。apply 仍独立重验快照、expectedRevision 并在 Runtime.Apply 中执行 Stage → Validate → 原子切换 current → 活跃核心重启，防止锁外或模块侧变化造成覆盖。预检成功不代表提交成功。停止态只更新配置，不启动核心；运行态重载成功后用 callFreshStatus 绕过 4 秒缓存核验 revision/state，确认前不显示成功。失败保留编辑草稿，标明未应用，不能使失败草稿成为下一次隐式启动配置。

普通服务启动/重载遇到缺失路由引用时仍沿用既有确认链：RootModuleClient 抛 OutboundReferenceWarningRequired，经 ServiceCommandController.ConfirmationRequired、MainActivity.showOutboundWarning 进入 OutboundReferenceWarningDialog；用户继续时调用 continueOutboundConfirmation() 后再次应用。策略组编辑提交是独立阻断分支：缺失成员/出站在编辑器校验中直接进入该编辑器的字段错误状态，不创建全局确认请求、不调用 continueOutboundConfirmation()，不执行 validate/apply，也不改变持久有效配置或 current；用户修复引用后方可再次提交。其余普通重载确认语义不变。当前没有独立 validate 按钮及其专用失败界面；新编辑器的校验/预检错误留在编辑器显示，控制器操作普通异常仍进入 CommandState.Error。validate 失败不调用 apply、不改变 current；重载失败由模块恢复旧 generation，若清理/回滚不确定则停止并 Fail-Closed，明确要求恢复，不能宣称恢复成功。

## 备份与迁移约定

现有证据：docs/portable-backup-and-batch-test.zh-CN.md 规定 format=wanbox.android.backup、schemaVersion=2，兼容旧手动 version=1/profiles 与旧自动无 version/proxies；明确不保证任意旧客户端读取格式 2。docs/APP_ID_MIGRATION.md 说明不会自动继承旧应用私有数据，恢复所选类别会覆盖，迁移完成前保留源数据。BackupRestore.parse 校验全部栏目，apply 校验目标引用后用 ATTACH + 单连接事务覆盖用户显式选择的类别；异常/取消回滚两库。这是用户确认的备份恢复，不是升级时清库迁移；数据库恢复事务不等于核心 Apply 回滚，不得混称。

4c 采用按目标版本验证的兼容矩阵。批次前导出 → 新版导入：旧格式可表示的字段必须等值，格式本身没有表示能力的字段以明确默认值/缺省语义导入，不得伪称旧备份包含这些字段。新版导出 → 批次前版本导入：仅当节点/组 ID、成员/模式/测速参数、规则及顺序、设置等全部待保真字段均由该旧格式和解析器表示时，才可标记该目标版本兼容并要求完整等值；任一字段不可表示或目标旧客户端读取能力未证明时，兼容导出必须在写入前报告具体不兼容字段并拒绝，不得静默降级或丢弃。格式 2 不保证任意旧客户端可读取。优先保持现有 schemaVersion=2；未知版本/不兼容字段在写入前拒绝。完整恢复含配置、规则、设置；局部恢复仍遵守现有所选类别及目标引用校验。

升级前必读说明统一字段：源/目标应用及模块版本、备份位置及验证结果、兼容矩阵、影响范围（无需数据迁移时明确说明）、操作步骤、成功检测、失败码/恢复步骤、回退 APK/配套模块要求、禁止清除源数据的时点。触发数据转换前必须确认已读并有可读备份；用户取消/备份失败不转换。迁移结果显示源/目标 schema、各类记录数、ID/引用检查、已完成/已回滚/阻断及恢复指引，不暴露凭据。转换写入需事务回滚；核心应用失败单独报告并保留旧有效 generation，恢复不确定则 Fail-Closed。

## 测试缝（先于实现确定）

| 行为 | 优先复用缝 | 验收证据 |
|---|---|---|
| 4a 参数/成员、仅 outbounds 改变 | ConfigBuilderLoadBalanceTest/SharedChainTest 风格、RootModuleSnapshotTest、OutboundReferenceValidatorTest | 前后快照对比非 outbounds 分区相等；成员/参数精确值、重复 tag/循环/缺失拒绝 |
| validate/apply/reload 与冲突 | RootModuleContractTest、core/testing/FakeRootModulePort、rootmodule/runtime_test.go/lifecycle_linux_test.go 的 Runtime 故障注入 | 命令顺序、预检失败不 apply、重验失败不换 current、冲突拒绝、运行态回滚/停止态不启动、清理失败 Fail-Closed |
| 4b 身份与交互 | ConfigurationFragmentPagerIdTest；Android 实际页面交互层 | 同名来源消歧、改名前后 ID/路由/成员不变；单项/所选/全部更新及失败反馈仍可达 |
| 4c 版本与事务 | PortableBackupTest、PortableBackupCompatibilityTest、BackupCrossVersionTest | 按目标格式矩阵对可表示字段做双向等值比对；不可表示字段预先报告并拒绝；旧格式样本、未知版本拒绝、取消/晚期 SQLite 故障两库不变 |

只测外部可观察行为，不以源码字符串命中冒充交互验收。优先上述最高层已有缝；若缺 UI 自动化宿主，在已有 Android instrumentation 中补页面行为覆盖，不新建跨层架构。以上为后续测试设计，本轮不添加或运行 Android/Go 测试代码。本轮门禁仅 git diff --check 与用户指定 Python 工具测试；实际设备矩阵及 CI 证据留待各实现批次。


4a 修复轮遵循统筹 F1–F6 裁决：编辑器从模块 current 指针对应的 generations/<revision>/snapshot.json 捕获完整有效基准，只补 outbounds，不以 EBPF 双重建替代。无有效基准或成员运行时 tag 不可取得时拒绝，要求先正常应用配置。提交先记录可恢复旧值/实体存在性/基准快照，完成本地准备后 validate→apply→绕缓存确认；失败补偿两库并经相同命令通道回滚基准，无法确认保留日志且 Fail-Closed。显式恢复入口在确认基准恢复后解除阻断。新建组进入既有实体与快照组集合，负载均衡从目标组编辑器成员 ID 取节点；不改规则或成员模型。
