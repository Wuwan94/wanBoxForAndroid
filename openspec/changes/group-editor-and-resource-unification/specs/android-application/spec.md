# Android Application Specification — Delta: group-editor-and-resource-unification

## ADDED Requirements

### Requirement: 图形化 Selector/URLTest 编辑

管理器 MUST 提供 Selector/URLTest 创建、成员增删及排序；URLTest MUST 可编辑 URL override、interval、tolerance、idle timeout、interrupt existing 并持久化重开。空 URL override MUST 保持既有继承语义；非空 URL MUST 为 HTTP(S)，interval MUST 为正秒数，tolerance MUST 为非负毫秒数，idle timeout MUST 为核心支持的非负时长。实现时 MUST 新增策略组成员图校验：以稳定实体 ID 为顶点、组到成员为有向边；从候选组可达自身即为循环，错误 MUST 定位到导致回边的成员 ID/编辑行，并在写入或生成候选快照前阻断。空成员、重复成员、缺失实体、循环或参数不合法 MUST 阻止保存并指出原因；取消 MUST 不改持久数据。普通编辑 MUST NOT 将已有 URLTest/LoadBalance 模式重置为 selector。

#### Scenario: 参数与成员持久化

- **GIVEN** 两个有效实体和一个 URLTest 组
- **WHEN** 用户调整成员顺序与五项参数、成功提交并重开
- **THEN** 顺序及参数准确读回，生成 URLTest 类型；空 override 使用原继承 URL

#### Scenario: 无效候选与取消

- **GIVEN** 原有组和有效配置
- **WHEN** 候选含空成员、重复成员、循环、缺失成员或非法参数，或用户取消
- **THEN** 新增的图校验定位循环回边成员；其他无效字段显示错误且不能保存，取消不写入，原成员/参数/模式保持不变

### Requirement: 策略组身份与分区隔离

稳定实体 ID MUST 作为数据库键及节点成员引用键，节点与组采用独立命名空间。规则生成仍按既有 outbound tag 契约引用；Selector/URLTest 的直接成员仍为节点，组间引用沿用负载均衡目标组；本批不新增按组 ID 的规则或组直接成员。主组 Selector/URLTest/LoadBalance outbound 的运行时 tag MUST 固定为 `proxy`；非主组路由兼容名称 MUST 使用 trim 后组名。节点链路 tag MUST 按生成上下文区分：直接节点为 `g-<id>`，链式节点为 `chainTag-<id>`，不得要求所有节点均为 `g-<id>`。新建策略组的路由兼容名称 trim 后 MUST 非空；路由兼容名称和类型创建成功后 MUST 只读；主组运行时 tag `proxy` 不随名称变化。资源显示名改动 MUST NOT 隐式修改组身份/tag 或破坏引用，不新增全局历史分组禁改名规则。策略组编辑 MUST 只改变配置 outbounds 分区，MUST 保持其他分区内容、路由生成顺序和用户规则顺序。

#### Scenario: 身份锁定与冲突

- **GIVEN** 已创建 tag=A、type=urltest 的组和另一个 tag=B 的出站
- **WHEN** 用户重开编辑或尝试新建 trim 后 tag=B 的组
- **THEN** A 的路由兼容名/类型只读，新建冲突被拒绝；主组运行时 tag 固定为 `proxy`，成员和参数仍可编辑

#### Scenario: 新建空白名称拒绝

- **GIVEN** 新建策略组的路由兼容名称为空或仅含空白字符
- **WHEN** 用户提交
- **THEN** 拒绝保存并将错误定位到路由兼容名称字段；主组运行时 tag 固定为 `proxy` 不替代名称校验

#### Scenario: 仅更新出站

- **GIVEN** 含路由、DNS、入站和用户规则的配置
- **WHEN** 用户成功修改策略组成员
- **THEN** 仅 outbounds 内容改变，其他分区及规则顺序相同；节点成员引用以稳定实体 ID 保存，并按直接/链式节点规则解析为对应运行时 tag，规则引用契约保持不变

### Requirement: Root 应用链与缺失引用阻断

策略组编辑 MUST 新增专用预检/提交接口；既有普通 reload 直接执行 `config apply`，不视作已存在的 validate → apply 接口。编辑器 MUST 捕获不可变 CandidateSnapshot，至少固定规范化配置 JSON/摘要、数据库与引用输入版本及 expectedRevision；validate/apply MUST 复用同一快照字节和 expectedRevision，期间 MUST 禁止重建候选，并在 apply 前重验输入版本及当前 revision。模块 apply MUST 再校验快照并检查 expectedRevision。运行态 MUST 由模块重载，停止态 MUST 不被编辑启动。界面 MUST 在绕过 4 秒缓存读取新状态并确认 revision/state 后才显示已应用；失败 MUST 显示原因；仅确认未生效或已恢复时显示未应用，应用状态不确定时 MUST 提示可能部分生效及先恢复模块，保留草稿且不把草稿当成下次隐式启动配置。编辑器发现缺失引用 MUST 直接进入编辑器错误状态，不创建全局确认请求、不调用 `continueOutboundConfirmation()`、不静默过滤或确认后丢弃；普通服务启动/重载 MUST 继续沿用现有缺失引用确认行为。

#### Scenario: 预检失败或引用缺失

- **GIVEN** 候选有缺失引用或核心校验不通过
- **WHEN** 用户提交
- **THEN** 缺失引用进入编辑器错误状态且不调用 `continueOutboundConfirmation()`；显示引用/校验错误，不执行 apply、不改变 current 或持久有效配置

#### Scenario: 并发冲突

- **GIVEN** validate 成功后另一操作更新了 revision
- **WHEN** 编辑器执行 apply
- **THEN** apply 拒绝旧 expectedRevision，提示重读重试，不覆盖新配置

#### Scenario: 运行与停止态

- **GIVEN** 分别处于运行或停止态的模块
- **WHEN** 有效编辑被提交
- **THEN** 运行态重载并核验新 revision/state，停止态仅更新配置且仍停止，确认前不显示成功

### Requirement: 应用故障恢复必须 Fail-Closed

核心重载失败 MUST 恢复上一有效 generation 并报告失败；若清理或回滚无法确认安全，MUST 停止并 Fail-Closed，明确显示需恢复，MUST NOT 假报回滚成功或绕过保护启动。

#### Scenario: 重载失败

- **GIVEN** 有上一有效 generation
- **WHEN** 新核心启动失败且可安全清理
- **THEN** 恢复上一 generation，界面显示编辑未应用

#### Scenario: 清理失败

- **GIVEN** 新 generation 启动失败且清理状态不确定
- **WHEN** 自动恢复不能证明安全
- **THEN** 模块 Fail-Closed，界面显示阻断状态与恢复步骤

### Requirement: 统一资源页与稳定身份

本地节点、订阅及订阅节点 MUST 在同一资源页可见、可访问其既有节点操作；订阅单项/所选/全部更新、进度及失败反馈 MUST 可达。重名 MUST 仅以来源后缀在界面消歧，来源也相同时 MUST 增加实体 ID。节点/资源改名 MUST 不删除节点、不改实体 ID、组成员及路由引用，MUST NOT 将显示名用作 Provider/tag 或引用键；涉及组 tag 的改名遵循身份锁定规则。

#### Scenario: 同名与改名

- **GIVEN** 本地与订阅各有同名节点，路由与组成员引用其 ID
- **WHEN** 用户查看并修改节点显示名
- **THEN** 同名项可由来源/ID 区分，改名后节点数量、ID、成员及路由引用不变

#### Scenario: 订阅更新入口

- **GIVEN** 两个订阅和本地节点同时显示
- **WHEN** 用户分别更新单项、所选和全部订阅
- **THEN** 更新范围准确，进度/失败可见，本地节点不会被作为订阅更新

### Requirement: 版本化双向备份兼容

备份兼容 MUST 按目标格式建立可验证矩阵。旧导出→新导入时，旧格式可表示字段 MUST 等值；旧格式不可表示的模式/测速字段 MUST 按明确缺省语义导入，不得声称其来自旧备份。新导出→旧版本导入时，仅当该版本格式和解析器可表示全部要求保真的字段时才可标为兼容，并且这些字段 MUST 等值；若字段不可表示或客户端读取能力未验证，导出/导入 MUST 在写入前报告具体字段及原因并拒绝，MUST NOT 静默降级或丢弃。新导出 MUST 标注 format/schemaVersion 和兼容目标；格式 2 MUST NOT 被宣称为任意历史客户端可读取。导入 MUST 保持对既有旧手动 version=1/profiles、旧自动无 version/proxies 格式的支持；未知版本/不兼容字段 MUST 在写入前明确拒绝。

#### Scenario: 批次前后版本往返

- **GIVEN** 本批前版本完整备份含配置、规则、设置与策略参数
- **WHEN** 新版导入后兼容导出，由批次前版本导入
- **THEN** 旧格式可表示字段与引用等值且兼容范围可识别；若模式/测速等字段无法由该目标版本表示，则明确拒绝兼容导出/导入并列出字段，原数据不变

#### Scenario: 未知版本

- **GIVEN** 未知 schema 或不能保真的字段
- **WHEN** 用户导入
- **THEN** 写入前拒绝并说明兼容版本/重新导出步骤，原数据不变

### Requirement: 迁移必须备份优先、可检测与可恢复

数据转换前 MUST 确认有可读备份并先校验全部栏目及目标引用；写入 MUST 使用原子事务。失败/取消 MUST 回滚，无法确认安全 MUST Fail-Closed。升级 MUST NOT 静默覆盖或采用重置式迁移；用户明确选择且确认的备份恢复仅覆盖所选类别。结果 MUST 显示源/目标 schema、各类记录数、ID/引用检查、完成/回滚/阻断及恢复指引，MUST 不泄露凭据；数据库恢复与核心应用结果 MUST 分开报告。

#### Scenario: 备份失败、取消或晚期写入错误

- **GIVEN** 原数据库有节点、规则及设置
- **WHEN** 备份不可读、用户取消或事务中后段失败
- **THEN** 不转换或回滚两库，原记录与引用不变，明确显示阻断/回滚原因

#### Scenario: 成功与显式恢复

- **GIVEN** 用户确认备份且选择恢复类别
- **WHEN** 完整校验及事务成功
- **THEN** 只替换所选类别，报告版本、数量和引用检查，核心应用结果单独报告

### Requirement: 升级前必读说明

升级迁移说明 MUST 在转换前可见并要求确认，MUST 含源/目标应用和模块版本、备份位置与验证、兼容矩阵、影响范围、操作步骤、成功检测、失败码/恢复与回退配套 APK/模块步骤及保留源数据时点；无需转换时 MUST 明确说明。未确认 MUST 不转换。

#### Scenario: 未读或确认说明

- **GIVEN** 一次待执行升级转换
- **WHEN** 用户取消或确认必读说明
- **THEN** 取消不写数据；确认后也只有备份和校验通过才可转换，失败仍按恢复指引处理

### Requirement: 4a effective baseline and recoverable editor transaction

编辑器候选 MUST 以 expectedRevision 对应的模块当前有效完整快照为基准，仅对 config.outbounds 做 copy-on-write 补丁，保留 TUN 回退、DNS、入站、资源与其他分区。无法读取或保持基准 MUST 拒绝编辑提交；当前无有效配置时允许拒绝并要求先建立有效配置，不得以双重建比较代替基准。未应用的 App 设置 MUST NOT 被重建带入候选。

编辑器 MUST 先记录旧草稿/偏好值、实体存在性和基准快照，再完成可回滚本地准备，随后 validate → apply → 绕缓存确认。同一候选 bytes 与 expectedRevision MUST 贯穿 validate/apply。任一阶段失败 MUST 恢复本地旧值；apply 已尝试时 MUST 先绕缓存读取 current revision；仅当其等于本次 apply 响应实际产生的 revision 或 baseline 原 revision 时，才允许经相同 validate/apply 通道回滚基准并绕缓存确认 revision/state。第三种 revision MUST NOT 写回 baseline，MUST 保留日志并返回 cleanup 类阻断错误；apply 响应丢失而无法确认 revision 归属时同样阻断。回滚不确定 MUST 返回 cleanup 类错误并保留恢复日志、阻断后续提交/普通重载；只有明确恢复旧配置后才解除阻断。停止态恢复 MUST 不启动核心。

新建组 MUST 进入既有 ProxyGroup 列表、负载均衡目标候选及冻结组集合。负载均衡目标组 MUST 使用已保存的稳定节点成员列表；节点 ID 与组 ID MUST 采用独立命名空间，缺失链成员、嵌套组目标或组成员 MUST 在 capture 前定位字段并阻断。普通配置生成 MUST 保持 DataStore.resolvedGroupUrlTestUrl 的既有契约，仅对精确等于默认值的存储值执行持久迁移。普通构建最终 outbounds 补丁 MUST 使用该解析的最终 URL，不得被保存草稿中的旧默认值覆盖。候选的空 URL override MUST 继承 DataStore 全局测试 URL，不得继承基准 outbound 中的旧 override。

#### Scenario: cleanup 后非法草稿仍保持阻断

- **GIVEN** 编辑器进入 cleanup_required，应用状态尚未确认
- **WHEN** 再次提交空成员或非法 interval 草稿，或页面重建
- **THEN** MUST 保持不确定/阻断文案，MUST NOT 被普通校验的“未应用”覆盖；恢复确认前 MUST NOT 重新启用草稿表单。仅恢复完成、journal 清除且 fresh status 确认模块已回到已知状态后，才允许解除阻断。

### Requirement: Subscription ownership survives ordinary policy-group edits

`ProxyEntity.groupId` MUST remain the single ownership and membership truth. A subscription-owned node MUST remain owned by its subscription when edited through a policy-group editor. Ordinary groups, including newly created groups, MUST NOT take over subscription nodes. The editor MUST reject that candidate with an explanation before either database/preferences or the recovery journal is written; a rejected candidate MUST NOT partially move its other nodes. Subscription groups MUST continue allowing reorder/parameter edits but MUST NOT add foreign nodes or remove owned nodes through this editor. This restriction MUST NOT change stable node IDs, subscription ownership or existing route references. Subsequent subscription refresh MUST match the original node within its subscription rather than insert a duplicate.

#### Scenario: Import, reject takeover, refresh without duplicate

- **GIVEN** a node imported by a subscription, a route referencing its ID, and an ordinary group with local nodes
- **WHEN** the user adds the subscription node to the ordinary group and submits, then refreshes the subscription
- **THEN** the editor MUST visibly explain that subscription nodes cannot move to ordinary groups, both stores and the recovery journal MUST remain unchanged by the rejected save, and subscription refresh MUST retain the original node ID, ownership and route reference without a duplicate

#### Scenario: New group cannot bypass subscription ownership

- **GIVEN** a subscription-owned node
- **WHEN** an editor candidate creates a new ordinary group containing that node
- **THEN** validation and local preparation MUST reject it before any persistent write

### Requirement: Discard retains its recovery barrier until confirmed complete

Discarding an unfinished edit MUST first restore journaled local values while retaining the durable journal. If fresh module status reports connected, the current local configuration MUST be rebuilt under the existing mutation lock without removing that journal; a subsequent cache-bypassing fresh status MUST confirm the applied revision, the matching running revision and a known connected/suspended state before journal deletion or success. A rebuild failure, timeout, unknown state or mismatched revision MUST retain the journal, recovery entry and connection/editing barrier. The same persisted journal MUST keep recovery available after Activity destruction/recreation and MUST support retry. Discard MUST NOT use its narrow locked recovery permission to bypass the journal barrier for normal startup or reload. Fresh stopped or suspended status MUST result only in local restoration/marker cleanup, with no rebuild or start and no change to module running intent. Other initial module states or installer selection MUST remain blocked.

#### Scenario: Failed rebuild survives page recreation and retry

- **GIVEN** a durable unfinished-edit journal and a connected module
- **WHEN** discard restores locally but rebuilding fails, or its fresh status times out, is unknown or confirms a different revision
- **THEN** discard MUST report failure, the durable journal MUST remain readable, and reopening the policy-group Activity MUST still offer recovery/discard; retry MUST be able to complete

#### Scenario: Confirmed rebuild releases the barrier

- **GIVEN** a durable journal and a connected module
- **WHEN** local restoration and the required rebuild succeed and fresh status confirms matching applied/current/running revisions in a known state
- **THEN** and only then the journal MUST be removed, success MAY be shown, and the recovery barrier MUST be released

#### Scenario: Non-running module remains non-running

- **GIVEN** a durable journal and fresh stopped or suspended module status
- **WHEN** the user discards the unfinished edit
- **THEN** local restoration MUST complete before journal cleanup and the module MUST NOT be rebuilt or started
