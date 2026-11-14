## ADDED Requirements

### Requirement: Root route generation preserves domain and reject precedence

Android 配置生成器 MUST 在启用流量嗅探时，把 `sniff` action 放在所有基于域名的路由规则之前，包括 Root mixed 入站探测规则。wanBox 普通规则路由 MUST 保持显式用户规则的相对顺序，并 MUST 将拒绝型 geographic fallback 放在国内 geosite/geoip 直连 fallback 之前；MUST NOT 改写数据库中用户保存的顺序。DNS 的 port 53 与 protocol dns 流量 MUST 由 `hijack-dns` 进入内置 DNS 引擎，MUST NOT 再生成重复的 53 端口代理兜底。组播 reject MUST 只匹配目的地址，MUST NOT 添加对应的 `source_ip_cidr` 条件。

#### Scenario: sniff 先于 Root 探测和业务域名规则

- **GIVEN** 用户启用流量嗅探，Root 配置包含 mixed 入站连接测试域名及用户域名分流规则
- **WHEN** Android 生成 sing-box route rules
- **THEN** `action=sniff` 位于 mixed 探测域名和其他域名路由之前
- **AND** 生成配置可由当前 Root 内核消费

#### Scenario: 自定义 reject 优先于国内 direct fallback

- **GIVEN** 用户保存的自定义 reject geographic rule 排在国内 geosite direct fallback 之后
- **WHEN** wanBox 对普通规则路由的 enabled rules 排序并生成配置
- **THEN** reject fallback 在国内 direct fallback 之前匹配
- **AND** Room 中原始 `userOrder` 与用户规则实体未被重写

#### Scenario: DNS 与组播规则保持目的流量语义

- **GIVEN** Android 为 Root 模式生成 route rules
- **WHEN** 配置包含 port 53/protocol dns 的 hijack 与组播拒绝规则
- **THEN** DNS 流量由 `hijack-dns` 处理且不存在其后的 53 端口/协议代理兜底
- **AND** 组播 reject 有目的地址 `ip_cidr` 但没有 `source_ip_cidr`

### Requirement: Default ad and Play routes do not hijack system downloads

新建默认广告规则 MUST 使用可由 wanBox 本地 geosite 转换链生成的 `geosite-category-ads-all` 标签。Play Store route MUST NOT 自动注入 Android Download Manager/Downloads Provider 作为代理路由包名。升级时仅当已保存规则 domains 含 `googleapis.cn` 且 packages 至少含一个旧注入包名时，才将其识别为待迁移的历史 Play route；MUST 从该规则删除 `com.android.providers.downloads` 与 `com.android.providers.downloads.ui`，同时 MUST 保留原有 `enabled` 状态和其他包名，不得因清理而强制启用或禁用该规则。

#### Scenario: 创建默认规则

- **GIVEN** 用户首次使用应用并由 ProfileManager 创建默认 route rules
- **WHEN** 默认广告规则进入本地 geosite 配置生成链
- **THEN** 规则生成 `geosite-category-ads-all` 本地 rule-set
- **AND** 该标签未被误作为需要远程 URL 的 `ruleset` 字段解析

#### Scenario: 升级含旧 Play 下载注入的规则

- **GIVEN** 已保存规则 domains 含 `googleapis.cn`，且 packages 含一个或两个旧版强制注入的 Android Download Manager/Downloads Provider 包名
- **WHEN** ProfileManager 读取并迁移既有 route
- **THEN** 两个旧注入包名被移除，且 Root 配置不再将这些下载请求强制路由到 Play proxy
- **AND** 原有规则 enabled 状态及其他用户选择保持不变

### Requirement: Unset per-group URL tests dynamically inherit the global URL

未显式设置组级测速 URL 的分组 MUST 保持空的 raw override，并 MUST 在生成组级测速配置时动态使用全局 `connectionTestURL`；只有明确的组级 URL 才能覆盖组级测速目标。编辑页初始化或保存 MUST NOT 把当时的全局/默认 URL 固化为组级配置。Root snapshot 中的组级 URLTest/LoadBalance outbound 与单节点 `resolveLink` MUST 使用一致的组级解析结果。Root mixed-inbound 的应用级全局连接测试探测规则与 Root health-check policy MUST 继续使用全局 `connectionTestURL`，不得被组级 override 改写。旧版自动写入且精确等于 `default_group_test_url` 的固定 fallback MUST 迁移为空 override；其他非空组 URL MUST 保留。全局值变化后，未设组级 URL 的分组在下次生成组级测速配置时 MUST 使用新值。

#### Scenario: 空组级 override 继承全局 URL

- **GIVEN** 一个组没有显式测速 URL，且全局连接测试 URL 已设置
- **WHEN** 用户打开该组设置，或应用生成组级 Root URLTest/LoadBalance outbound
- **THEN** 组设置仍保留空 override，生成配置使用全局 URL

#### Scenario: 显式组 URL 覆盖组级测速目标

- **GIVEN** 某组保存了明确测速 URL
- **WHEN** 全局 connection test URL 改变并重新生成该组 URLTest/LoadBalance outbound 或执行单节点 `resolveLink`
- **THEN** 该组继续使用自己的显式 URL
- **AND** 其他空 override 分组改用新的全局 URL

#### Scenario: 迁移旧固定 fallback 并保留其他显式 URL

- **GIVEN** 某组仅存有旧版本编辑页自动写入且精确等于 `default_group_test_url` 的 fallback，另一个组保存了不同的非空 URL
- **WHEN** 新版本解析两组的测速地址
- **THEN** 旧 fallback 被视为空 override 并动态使用当前全局 URL
- **AND** 另一个组的显式 URL 保持不变

#### Scenario: Root 应用级 mixed probe 仍使用全局 URL

- **GIVEN** 某组显式测速 URL 与全局 `connectionTestURL` 不同
- **WHEN** 应用生成 Root 配置
- **THEN** 组级 URLTest/LoadBalance outbound 使用显式组 URL
- **AND** mixed-inbound 的应用级连接测试探测规则与 Root health-check policy 仍基于全局 URL

### Requirement: User backup export is readable and imports legacy wanBox backups

用户备份 MUST 以 `profiles` 作为新导出字段，规则 MUST 以可读 JSON 表示；当选择 `settings` 区段时，MUST 显式保存跨版本恢复所需的稳定 Root 网络配置默认值，包括核心路由/DNS、应用/Wi-Fi/移动网/Root UID 路由策略、规则资源 provider/URLs、LAN 访问、全局 TLS 证书校验策略及共享连接测试 URL。导入器 MUST 兼容旧 `proxies` 字段、旧 Base64 Parcel route rules、旧 wanBox 用户备份及混合新旧 rule entry；缺失的稳定默认值 MUST 在存在 settings 区段时补齐，已显式保存的用户值 MUST NOT 被覆盖。未选择 settings 时解析计划 MUST 保持未选择。用户备份 MUST 与内部 RootModuleSnapshot/data archive 保持边界分离。

#### Scenario: 导出新版用户备份

- **GIVEN** 用户选择导出 profiles、rules 或核心设置
- **WHEN** App 生成用户备份
- **THEN** profiles 写入 `profiles`，规则以可读 JSON 导出
- **AND** 缺少持久化项的 Root 网络配置设置按 DataStore 稳定默认显式写入

#### Scenario: 导入旧 wanBox 备份

- **GIVEN** 旧 wanBox 用户备份使用 `proxies`、Base64 Parcel 规则或旧版设置集合
- **WHEN** 用户选择导入该备份
- **THEN** profiles、rules 和设置均可恢复
- **AND** 显式备份值优先于新版本默认值，未保存的默认值按稳定契约补齐

#### Scenario: 新旧 route entry 混合导入

- **GIVEN** 一个备份的 route rules 同时包含可读 JSON entry 和 Base64 Parcel entry
- **WHEN** 导入器解析 rules
- **THEN** 两类规则均被还原为语义等价的 RuleEntity
- **AND** Root 模块内部 snapshot/data archive 不被当作用户 JSON 备份处理

#### Scenario: 未选择设置区段时不物化默认值

- **GIVEN** 用户备份未包含 `settings` 区段
- **WHEN** 导入器解析该备份
- **THEN** 解析计划的 settings 保持未选择
- **AND** 导入器不在未选中的区段中创建核心默认设置

### Requirement: Core settings provide an independent QUIC UDP 443 switch

核心设置 MUST 提供独立的“拦截 QUIC (UDP 443)”开关，默认 MUST 开启。开启时，配置生成器 MUST 产生匹配 `network=udp`、`port=443` 的 reject 规则；关闭时 MUST NOT 产生此自动 reject 规则，使 UDP 443/HTTP/3/QUIC 可以继续按其他有效路由规则处理。该开关 MUST NOT 拦截 TCP 443 或 UDP 非 443 流量。设置文案 MUST 在默认语言与简体中文资源中可见。

#### Scenario: 默认拦截 UDP 443

- **GIVEN** 用户首次安装或此前没有保存该设置
- **WHEN** Android 生成 Root sing-box 配置
- **THEN** QUIC 开关默认开启且生成 UDP/443 reject
- **AND** TCP/443 与 UDP/非443 不匹配该自动规则

#### Scenario: 关闭后允许 HTTP/3 与 QUIC

- **GIVEN** 用户关闭独立 QUIC 开关且没有显式自定义拒绝规则
- **WHEN** Android 重新生成并下发 Root 配置
- **THEN** 自动 UDP/443 reject 不存在
- **AND** HTTP/3/QUIC 流量可按剩余路由规则通过

### Requirement: Missing route outbounds are visible before users start the core

路由规则引用的节点或策略组不存在时，Android MUST 在规则列表中将对应规则标为错误状态，并在启动核心前显示可定位的 modal 提醒。该提醒 MUST 列出规则与缺失引用类型，MUST 提供返回编辑或明确继续的路径；用户明确继续时，未应用的规则仍 MUST 被列入提醒结果，不得静默忽略。issue MUST NOT 包含认证信息、代理地址或原始核心 stderr。

#### Scenario: 规则列表显示缺失节点/策略组

- **GIVEN** 一条 route rule 引用的节点或策略组已被删除
- **WHEN** 用户打开规则列表
- **THEN** 该规则显示为红色错误状态
- **AND** 有效节点/策略组引用不会被误标

#### Scenario: 启动前列出未解析引用

- **GIVEN** 当前启用规则含一个或多个不存在的出站引用
- **WHEN** 用户启动核心
- **THEN** 核心启动前出现列明规则和缺失引用的 modal
- **AND** 用户可返回修复或明确继续，继续时仍可见该规则未应用

### Requirement: Smart network auto-stop is bounded, permission-free, and user-controllable

高级设置 MUST 允许最多 5 条网络自动挂起规则，支持匹配本地 IP、CIDR 和 `gateway:` 网关，不得请求定位权限。规则命中时 MUST 自动挂起 Root 核心连接，并显示保持可操作的“已挂起”通知及“恢复”“停止”按钮。离开匹配网络后 MUST 自动恢复此前自动挂起的核心。用户在同一网络手动恢复后，MUST NOT 因同一 network fingerprint 再次自动挂起；网络变化后可重新匹配。用户手动停止必须按停止意图处理，不得被自动恢复覆盖。

#### Scenario: 匹配网络自动挂起

- **GIVEN** 用户配置至多 5 条 IP/CIDR/`gateway:` 规则且当前网络匹配
- **WHEN** Root 网络观察器确认该 network fingerprint
- **THEN** 核心连接挂起，状态通知保留“恢复”“停止”操作
- **AND** 应用没有请求定位权限

#### Scenario: 离开网络与手动恢复

- **GIVEN** 核心因某 network fingerprint 自动挂起
- **WHEN** 设备离开匹配网络，或用户在该网络手动恢复
- **THEN** 离开网络时核心自动恢复
- **AND** 同一网络的手动恢复在网络变化前抑制重复挂起

### Requirement: Root suspension cleans only owned iptables and routes

Root 网络策略在挂起、恢复、停止和失败回滚时 MUST 幂等清理仅由当前 wanBox 实例拥有的 iptables 规则、路由和核心资源。恢复时 MUST 只添加一次所需规则；离开自动挂起网络后 MUST 恢复原用户运行意图。清理或恢复失败时 MUST 报告真实错误和状态，MUST NOT 留下阻断设备网络的规则或谎报已恢复。

#### Scenario: 自动挂起后切换网络

- **GIVEN** Root 核心已在匹配网络挂起且清理了 wanBox 自有转发规则
- **WHEN** 网络变为不匹配状态
- **THEN** 原运行意图恢复且仅重建一次 wanBox 规则
- **AND** 非 wanBox iptables/route 条目保持不变

#### Scenario: 部分清理失败

- **GIVEN** 挂起过程中有 wanBox 路由或 iptables 删除失败
- **WHEN** Root 状态机回滚或继续清理
- **THEN** 状态报告保留失败原因并允许幂等重试
- **AND** 不报告虚假的已挂起/已恢复成功状态
