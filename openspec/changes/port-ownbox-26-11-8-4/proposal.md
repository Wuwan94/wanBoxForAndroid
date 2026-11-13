# 适配移植 OwnBox v26.11.8.4-preview 的功能与修复

## 为什么

OwnBox `v26.11.8.3-preview..v26.11.8.4-preview` 同时包含三项新功能和四项修复。wanBox 的 Core/Root 架构、配置生成和持久化模型已大幅分叉，必须逐项评估并适配，不能直接 cherry-pick。用户已明确完整范围包括原始请求中的全部七项；三项功能与四项修复均纳入本 change，只有评估确认真实不适用时才跳过。

上游依据为 [OwnBox v26.11.8.4-preview commit `aa93dfb2563e3b2b4dff35d2ca40c86942842ae8`](https://github.com/Own716/OwnBoxForAndroid/commit/aa93dfb2563e3b2b4dff35d2ca40c86942842ae8)，比较范围为 [`v26.11.8.3-preview...v26.11.8.4-preview`](https://github.com/Own716/OwnBoxForAndroid/compare/v26.11.8.3-preview...v26.11.8.4-preview)。

## 开工适用性评估

| 项目 | wanBox 当前状态与代码位置 | Root-only / 新架构影响 | 适配判断 |
|---|---|---|---|
| 功能 1：按网络智能挂起/恢复代理 | 已有物理网络指纹、代理健康探测、预算与失败后 TUN/core 重建：`rootmodule/health.go`、`rootmodule/supervisor.go`、`RootTunService.kt`、`DefaultNetworkListener.kt`、`RootModuleClient.kt`。没有“命中用户网络规则→挂起、离开网络→恢复”的用户意图状态机；空指纹目前会跳过。 | TUN、核心进程及物理网络判定归独立 Root 模块所有；不能照搬旧 Android VPN/Service 网络回调。配置和状态应经 `RootModulePort`、`wanboxctl`、`RootModuleClient` 管理面适配。 | 适用并纳入批次 6：Root 网络策略层新增最多 5 条 local-IP/CIDR/`gateway:` 规则、无定位权限、挂起/恢复/手动覆盖状态机、通知状态及 iptables/路由幂等清理。 |
| 功能 2：独立拦截 QUIC（UDP 443）开关 | 首次规则已有 `network=udp`、`protocol=quic` 的 Block QUIC 规则，但 `ProfileManager.kt` 中默认并未启用，也不是独立设置，且没有 `port=443` 限定；实际路由由 `ConfigBuilder.kt` 生成。 | RootModuleClient 下发完整 sing-box 配置；不需要在 Root Go 层另造 UDP 过滤器。设置持久化和配置生成应在 Android 侧共用。 | 适用并纳入批次 4：新增核心设置独立开关，默认开启；开时只自动拒绝 UDP 443，关闭时不生成自动拒绝规则，使 HTTP/3/QUIC 可用；显式用户 route rule 仍按用户配置处理。 |
| 功能 3：无效出站专门告警 | Rootbox/核心会拒绝无效 outbound、detour、selector 或 route final；App 侧主要显示通用配置启动错误，缺少出站级定位告警与规则列表标红。路径包括 `libcore/rootbox.go`、`rootmodule/runtime.go`、`RootModuleClient.kt`、`RootModuleErrors.kt`、`DashboardFragment.kt`、`RuleEntity.kt`。 | 校验权威在 Root/Core；不能在 APK 另造不同步的核心出站模型。应从 App 配置生成/数据库引用检查产生脱敏结构化 issue，再在管理端呈现，不应转发原始 stderr。 | 适用并纳入批次 5：缺失节点或策略组引用在列表标红，启动前由 modal 汇总提醒；不能只静默跳过或压成无上下文 toast。 |
| 修复 1：路由顺序、组播与 DNS 规则 | 已有 `sniff`、用户规则及 `RouteRulePriority`；Root 配置生成位于 `ConfigBuilder.kt` 约 1372–1437、1865–1970。当前 sniff 仍排在 Root mixed 探测域名规则后；reject fallback 与国内 geosite fallback 同属 fallback 时按旧顺序；组播 reject 含 `source_ip_cidr`；DNS hijack 后另有 port/protocol 53 的代理兜底。 | 修复在 App 配置生成器。RootModuleClient 复用同一配置快照，故不需另改 rootmodule Go 路由规则。完整自定义 JSON 仍由现有路径负责，本 change 不擅自合并规则。 | 适用并纳入批次 1：增加纯排序与实际生成配置测试；保留数据库用户顺序，只调整生成结果；移除错误字段和冗余 DNS fallback。 |
| 修复 2：默认广告规则与 Play 下载包强制路由 | 默认广告规则在 `ProfileManager.kt` 以 `domains="geosite:category-ads-all"` 保存，手动广告预设另在 `RouteFragment.kt`；当前 `ConfigBuilder.kt` 没有按 Play 包名强制扩充规则，但旧数据库/备份可能仍保存 `com.android.providers.downloads` / `.ui`。 | wanBox 使用 Root 透明代理，没有 OwnBox 的 Android TUN 白名单；RuleEntity `packages` 会生成 package/UID 匹配。wanBox 的远程 `ruleset` 字段只接受 `rsip:`/`rssite:` URL，内置 geosite 必须通过 `domains` 字段进入本地 geosite 转换。 | 适用并纳入批次 1：默认广告规则归一为可执行的 `geosite-category-ads-all`；只从已识别的历史 Play route 清理旧下载管理器包名，保留用户 enabled 状态；不新增强制路由注入。 |
| 修复 3：分组测速 URL 继承全局设置 | `DataStore.groupUrlTestUrl(groupId)` 在空值时回退到独立默认 URL；`GroupSettingsActivity` 初始化时使用非 raw getter，且保存流程会将该 fallback 写回配置。`group_preferences.xml` 没有独立测速 URL 输入；旧 fallback 精确值是旧自动初始化的候选。组 URL 由 `ConfigBuilder.kt`、`UrlTest.kt` 消费并送入 Root。 | Root-only App 不提供本地批量测速 UI，但组 selector/loadbalance URLTest 仍由 Root 核心执行；错误默认会直接影响 Root 配置。旧固定值与显式值的歧义只需在迁移精确匹配旧 `default_group_test_url` 时评估，其他值可原样保留。 | 适用并纳入批次 2：编辑页改读 raw override；空值动态继承全局；精确等于旧 fallback 的自动存值迁为空，其他非空组地址保持；URLTest、LoadBalance、Root snapshot 与 resolveLink 共用显式组 URL > 全局 `connectionTestURL` > 安全默认的顺序。 |
| 修复 4：备份导入导出与旧格式兼容 | 已有 `PortableBackup` v2、`BackupRestore`、`BackupHelper` 与 JSON/跨版本测试；代码证据显示新格式使用 `profiles`，旧入口兼容 `proxies`，规则有可读 JSON/旧格式解码，设置导出可补核心默认值。路径包括 `database/PortableBackup.kt`、`BackupRestore.kt`、`utils/BackupHelper.kt`、`ui/BackupFragment.kt`、`PortableBackupCompatibilityTest.kt`、`BackupCrossVersionTest.kt`。 | 用户备份属于 App Room/设置数据；Root snapshot 是另一种内部配置交接，不得混为同一种文件或让 rootmodule 解析用户备份。现有 wanBox 格式的测试不等于已实测 OwnBox 外部文件互通。 | 适用并纳入批次 3：对 profiles/proxies、规则 JSON/Base64 Parcel、默认值和旧 wanBox 备份往返做真实 fixture 核验；若覆盖完整则保留现有实现、不重复改写；有缺口才在 App 备份边界适配。 |

## 本次变更内容与批次

1. 批次 1：修复 1 + 修复 2（路由优先级、DNS/组播、广告与 Play 下载规则）。
2. 批次 2：修复 3（分组测速 URL 初始化与全局动态继承）。
3. 批次 3：修复 4（新旧备份格式及默认设置兼容）。
4. 批次 4：功能 2（独立 QUIC UDP 443 开关）。
5. 批次 5：功能 3（无效出站列表标红与启动 modal 提醒）。
6. 批次 6：功能 1（Root 网络智能挂起/恢复，含安全清理），复杂度最高，最后实施。

每批必须先完成该批测试、实现和主规范同步，再自审、提交、推送并运行非发布 CI；CI 结果未返回并通过前，不得开始下一批。按用户最新指示，不等待真机回传，但报告必须明确哪些行为尚未真机验证。

## 影响与边界

- 受影响主规范：`android-application`；Root 网络挂起还影响 `libcore-integration`。不涉及 theme-system、版本编码或构建链。
- 不改变 `VERSION_NAME=3.0.11`、预览版本、`SINGBOX_VERSION` 或正式版行为；本 change 不在本地安装 Go、不编译 Android/Go。
- 仅在 `port/ownbox-26.11.8.4` 分支提交和推送；不推 main、不创建 PR、不运行 release workflow、不发布 Release、不打/删 tag。若适用 CI 只能通过 PR 触发，则停下并向用户报告需其决定是否开 PR。
- 新增用户可见文案至少更新默认语言与简体中文；其他已存在语言继续通过默认资源回退并保持 UI 一致。
