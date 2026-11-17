# 提案：策略组编辑与资源统一（批次 4）

## 问题陈述

策略组模式标志和 URLTest 参数已存在于 DataStore/ConfigBuilder，但分组编辑页未提供可保存的模式开关；节点与订阅分在两个 tab，重名资源难以辨认。引用键必须继续使用稳定实体 ID，避免改名或迁移造成悬空路由。

## 方案

新增图形化策略组编辑面，编辑 Selector/URLTest 成员及测速参数，并把修改限制在配置 outbounds 分区，经 Root 的 validate → apply → core 重载链提交。统一资源页以来源消歧，保持实体 ID 引用。迁移采用版本化双向备份，解析和引用校验失败即回滚/Fail-Closed，并在升级前显示说明。

## 用户故事

1. 作为用户，我希望编辑 Selector/URLTest 成员，以便控制组策略。
2. 作为用户，我希望调整 URLTest URL、间隔、容差、空闲超时和中断策略，以便控制测速行为。
3. 作为用户，我希望新建组的 tag/type 创建后不可改，以便不破坏已有引用。
4. 作为用户，我希望缺失成员或出站被明确指出，以便修复而不是静默丢失。
5. 作为用户，我希望本地节点和订阅在同一资源页查看和更新，以便统一管理。
6. 作为用户，我希望重名资源有来源提示，并且改名不影响路由。
7. 作为用户，我希望升级前看到可执行的备份和迁移说明，并在失败时保留原数据。

## 实现决策

- 以 DataStore 的 `group_<id>_isUrlTest`/`isLoadBalance` 和现有 profileCacheStore 参数为编辑状态；补齐组编辑的可见读写入口；BalancerSettingsActivity 的既有能力保持现状，不扩展 LoadBalance。
- 只生成/更新配置快照的 outbounds 分区；不改变路由生成顺序、用户规则顺序或 Root-only 边界。稳定实体 ID 是数据库及成员/规则引用键；非主组路由兼容名称为 trim 后组名，主组运行时 tag 固定为 `proxy`；链路 tag 按上下文生成，直接节点为 `g-<id>`、链式节点为 `chainTag-<id>`。新建策略组的路由兼容名称和类型创建成功后锁定。
- 提交复用 RootModuleClient 的 `config validate`、`config apply` 与核心重载/回滚链；复用 OutboundReferenceWarningDialog 显示缺失引用，错误状态进入现有 Error 表现。
- 资源页统一本地节点与订阅，显示层可追加来源后缀；稳定实体 ID 始终是引用键，不采用 NetProxy 的 Provider 标签语义。
- 备份沿用 PortableBackup schemaVersion、BackupRestore 全量解析/引用校验及单连接事务；迁移必须可检测；旧导出→新导入时旧格式可表示字段等值，旧格式没有的字段采用明确缺省语义；新导出→旧导入仅在目标格式和解析器可完整表示全部待保真字段且读取能力已验证时承诺等值，否则在写入前报告具体字段及原因并拒绝；失败回滚或 Fail-Closed，禁止静默覆盖和重置式迁移。

## 测试决策

优先复用配置生成/快照与现有 RootModuleContract、OutboundReferenceValidator、Root Runtime Apply 故障注入测试；复用 PortableBackup、跨版本兼容及事务回滚测试；资源页复用 ConfigurationFragment pager-id 契约测试，并补 Android 层可观察行为测试。测试只验证外部行为：outbounds 局部更新、ID 引用稳定、缺失引用显式失败、Apply 失败保留旧快照、备份迁移可检测且不破坏原数据。

## 范围外事项

不改 SINGBOX_VERSION、版本元数据、workflow、NetProxy 代码/结构/升级行为、路由顺序、用户规则顺序、Root-only/管理器轻量边界；不做清库或重置式迁移；本变更不包含生产代码、测试代码或提交。

受影响能力为 openspec/specs/android-application。本轮不修改生产能力，因此暂不需同步主规范；任何实现批次完成前 MUST 将已实现状态同步到受影响的主规范。不修改 libcore、构建链或开发工具布局，NetProxy-Magisk v8.7.0 仅为功能参考。现状、参数合法性、身份冲突和失败语义以 design 的核查表与数据流为准。

## preview.4 review correction (2026-11-17)

Limit this correction to subscription-node takeover and premature discard-journal deletion at source `9283ccb0886805bddbd6856835d23a548f20b9d6`. Keep `groupId` as the single membership/ownership truth: reject ordinary-editor takeover of subscription nodes rather than introducing another membership table. Retain the recovery barrier until local restore, any required running-module rebuild and fresh-status confirmation complete. No Go, stable version, sing-box version or unrelated release changes are included. The user explicitly authorized applicable local Kotlin/Android verification for this task; results and unsupported environments are recorded in tasks, not inferred as passes.
