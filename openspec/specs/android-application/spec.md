# Android Application Specification

## Purpose

定义 Android 应用层的主要职责、关键用户能力以及与 libcore 之间必须保持的行为契约。

> **验证状态标记**：条目末尾的 `> 验证：` 行说明该条目的验证状态。「CI 通过」指该条目所属批次的分支验证（port #190 / feat #194）与 main `Verify Android Build` #197 第 2 次尝试全绿，run 号见标记正文；「待真机验证」指 Magisk / KernelSU / APatch 真机矩阵未执行。未标注的条目为历史基线或待拍板项，不代表已验证。

## Requirements

### Requirement: Missing route outbounds are visible before users start the core

Android MUST mark rules referencing missing positive node or strategy-group IDs red, including their name, outbound and error indicator. Rules whose policy-group target (N6) references a missing group MUST also be marked red, with the localized message that the rule targets a policy group that no longer exists. Built-in targets and existing IDs MUST remain valid, and recycled rows MUST reset their error appearance. Validation MUST refresh with the rules.

Explicit core start and reload requests MUST validate enabled rules before creating or applying configuration. A modal MUST list safe rule names, rule IDs, missing reference types and IDs, explain that unresolved rules cannot be applied, and offer Cancel, Review rules and Continue Anyway. Back and outside cancellation MUST cancel the request; Review rules MUST cancel before navigating to rules.

Installer-data selection MUST first finish restoring the database; preflight MUST validate the final restored rules before generated core configuration is created or applied, rather than validating the database that the restore will replace.

Continue MUST consume a one-use token bound to the exact issue snapshot and revalidate under the root mutation lock. Changed issues MUST require fresh confirmation while other commands remain blocked; fixed issues MAY proceed. Explicit cancellation, completion, failure and coroutine cancellation MUST release the command lock exactly once. Diagnostics MUST NOT retain configuration, authentication data, proxy addresses or raw core stderr.

Warnings from `:bg` MUST first queue a pending-review marker in private shared app storage with interprocess synchronization, then signal an already-visible MainActivity through an explicit package-scoped broadcast received by a non-exported receiver. The reporting process MUST NOT create a confirmation or retain a confirmation lock. The persisted marker MUST contain only a random request ID and the `startIfStopped` boolean, with no rule names, rule/reference IDs, config, secrets, stderr or proxy data; multiple outstanding requests coalesce to the latest request. MainActivity MUST peek without clearing, then query current rules. It MUST compare-and-clear only the matching request ID when no warning is needed or after the warning DialogFragment is actually shown; stale acknowledgment MUST preserve newer requests. Busy controllers, discovery errors, saved FragmentManager state and failed confirmation acquisition MUST leave the marker queued, with retries on resume and return to Idle. If persistence fails, the reporting process MUST still attempt the broadcast and generic toast without logging user data; a live broadcast MAY supply the same minimal request value when the marker is unavailable. Ordinary Activity launch/resume without an outstanding background warning request MUST NOT run outbound preflight, show its modal or start a stopped core. A localized generic instruction MUST direct users to open the app; the event MUST NOT carry config, stderr, credentials or proxy data.

Successful explicit Continue MUST retain the original unresolved issue snapshot in confirmed command state and show a result-mode DialogFragment explaining that those rules were not applied. It MUST offer acknowledgment and Review, never another Continue. Back/outside cancellation MUST acknowledge the result without cancelling the completed operation; Review MUST return to rule editing.

#### Scenario: Missing references block startup until an explicit decision

- **GIVEN** enabled rules reference missing outbounds
- **WHEN** startup or reload is requested
- **THEN** configuration is not created or applied before the warning decision
- **AND** changed references on Continue produce a fresh modal
- **AND** Cancel releases the command controller without applying configuration

#### Scenario: Opening the app alone does not request a core start

- **GIVEN** enabled rules reference missing outbounds and no background warning request is outstanding
- **WHEN** MainActivity opens or resumes
- **THEN** no outbound preflight modal is shown and no core start is requested

#### Scenario: A background warning survives the absence of an Activity

- **GIVEN** `:bg` reports a warning when no Activity exists
- **WHEN** MainActivity next resumes
- **THEN** it peeks at the minimal pending marker and queries current rules for confirmation
- **AND** it acknowledges only the matching request after dialog presentation or validation finds no warning
- **AND** the original `startIfStopped` intent is preserved without persisting diagnostic data

### Requirement: Android 模块职责分层

`app/` MUST 承载 UI、后台服务、数据库、配置解析和 JNI 平台适配。`io.nekohasekai.sagernet` MUST 保持 SagerNet 核心框架职责，`moe.matsuri.nb4a` MUST 承担 NekoBox/Throne 兼容扩展、附加协议绑定和工具职责。资源 MUST 按 Android 标准目录组织，并维持已支持语言的本地化字符串；测试菜单和设置中的用户可见名称 MUST 使用可翻译资源，不得以不可翻译的英文文案替代。

#### Scenario: 添加 Android 功能

- **GIVEN** 一个新功能需要 UI、持久化或 libcore 平台能力
- **WHEN** 实现该功能
- **THEN** 代码按核心框架与兼容扩展的职责放入对应包
- **AND** 用户可见文本进入资源并同步适用语言

#### Scenario: 显示本组测试入口

- **GIVEN** 用户打开配置列表的测试菜单
- **WHEN** 应用展示 URL 延迟测试与速度测试入口
- **THEN** 当前语言显示“URL 测试本组”和“速度测试本组”的等义本地化名称
- **AND** 不显示硬编码或声明为不可翻译的旧英文 URL 测试文案

### Requirement: sing-box 配置符合官方 schema

配置生成 MUST 以当前 `SINGBOX_VERSION` 对应的官方 schema 为准。入站嗅探与目标解析 MUST 使用位于路由规则前部的 `sniff`/`resolve` 动作；TUN 地址 MUST 使用合并后的 `address` 字段；双网络加速 MUST 映射为 `default_network_strategy: "hybrid"`。已被官方移除的 legacy 字段 MUST NOT 被发射。

#### Scenario: 生成正式连接配置

- **GIVEN** 用户启用流量嗅探、IPv6 或双网络加速等设置
- **WHEN** `ConfigBuilder` 生成 sing-box 配置
- **THEN** 输出可被目标官方内核 schema 接受
- **AND** 不包含已移除的入站 sniff、legacy TUN 地址或 `route.concurrent_dial` 字段

### Requirement: Root 路由顺序保持域名命中与拒绝优先级

Android 配置生成器 MUST 在启用嗅探时将 `sniff` action 放在所有基于域名的路由规则之前，包括 Root mixed 入站探测规则。显式用户规则 MUST 位于 geographic fallback 前并保持彼此的相对顺序；fallback 重排 MUST 将拒绝型 geographic fallback 放在内置国内 geosite/geoip 直连 fallback 之前，且 MUST NOT 改写数据库中的用户顺序。Root DNS 的 port 53 与 protocol dns 流量 MUST 由 `hijack-dns` 处理，MUST NOT 生成重复的 53 端口代理兜底。组播 reject MUST 只匹配目的地址，MUST NOT 包含 `source_ip_cidr`。

#### Scenario: sniff 先于 Root 探测和业务域名规则

- **GIVEN** 用户启用流量嗅探，Root 配置包含 mixed 入站探测域名和用户域名分流规则
- **WHEN** Android 生成 sing-box route rules
- **THEN** `action=sniff` 位于探测域名及其他域名路由之前
- **AND** 用户域名规则仍按显式规则语义参与路由

#### Scenario: 自定义 reject 优先于国内 direct fallback

- **GIVEN** 启用规则中同时存在用户自定义 reject geographic fallback 与内置国内 direct fallback
- **WHEN** wanBox 排序并生成普通规则路由
- **THEN** reject fallback 位于国内 direct fallback 之前
- **AND** Room 中原始用户顺序保持不变

#### Scenario: DNS 与组播规则保持目的流量语义

- **GIVEN** Android 为 Root 模式生成 DNS 与组播 route rules
- **WHEN** 配置生成 port 53/protocol dns 的 hijack 及组播 reject
- **THEN** DNS 流量由 `hijack-dns` 处理且不存在冗余的 53 端口代理兜底
- **AND** 组播 reject 匹配目标 CIDR，且没有 `source_ip_cidr`

### Requirement: 缺失的路由出站引用必须显式告警

Android MUST 对规则实际使用的出站引用检查节点与策略组是否存在；内置 proxy/direct/block 目标 MUST 被识别为有效引用。引用缺失的规则 MUST 在路由列表显示红色错误状态，且核心启动或应用新配置前 MUST 显示列出规则与缺失引用类型/ID 的提醒。提醒 MUST 提供返回路由列表修复或明确继续的选项；继续时 MUST 保留未应用规则的可见提醒，不得静默忽略。脱敏 issue MUST NOT 包含凭据、代理地址或原始核心 stderr，也 MUST NOT 持久化到规则实体。

#### Scenario: 路由列表标记悬空引用

- **GIVEN** 一条规则引用的节点或策略组已被删除
- **WHEN** 用户打开路由规则列表
- **THEN** 对应规则显示红色错误状态及安全的缺失引用摘要
- **AND** 有效节点、策略组及内置出站不会被误标

#### Scenario: 启动前提醒且仅由用户决定继续

- **GIVEN** 当前启用规则含一个或多个不存在的出站引用
- **WHEN** 用户启动核心或应用新的 Root 配置
- **THEN** 配置应用前出现包含规则名称/ID 与缺失引用类型/ID 的提醒
- **AND** 用户可返回路由列表修复或明确继续；取消不得启动/应用，继续后未应用规则仍列在提醒结果中

### Requirement: Core settings provide an independent QUIC UDP 443 switch

核心设置 MUST 提供独立的“拦截 QUIC (UDP 443)”开关，默认 MUST 开启。开启时，Root 配置 MUST 在普通用户路由规则之前产生只匹配 `network=udp`、`port=443` 的 `action=reject` 规则；该自动规则 MUST NOT 匹配 TCP 443 或 UDP 非 443。关闭时 MUST NOT 产生该自动规则，UDP 443/HTTP/3/QUIC MUST 继续按剩余有效路由规则处理，且 MUST 保留用户显式保存的规则。设置文案 MUST 在默认语言与简体中文资源中可见。导出包含 settings 区段的便携备份时，MUST 将缺失的 `blockQuic` 默认物化为 `true`，并 MUST 在导入/导出往返中保留显式值。

#### Scenario: 默认拦截 UDP 443

- **GIVEN** 用户首次安装或此前没有保存该设置
- **WHEN** Android 生成并下发 Root sing-box 配置
- **THEN** QUIC 开关默认开启且生成 UDP/443 reject
- **AND** 自动规则位于普通用户规则之前，TCP/443 与 UDP/非443 不匹配该规则

#### Scenario: 关闭后允许 HTTP/3 与 QUIC

- **GIVEN** 用户关闭独立 QUIC 开关且没有显式自定义拒绝规则
- **WHEN** Android 重新生成并下发 Root 配置
- **THEN** 自动 UDP/443 reject 不存在
- **AND** HTTP/3/QUIC 流量可按剩余路由规则通过

#### Scenario: 关闭开关不删除用户自定义拒绝规则

- **GIVEN** 用户关闭自动 QUIC 拦截，同时保存了一个仅匹配指定域名 UDP/443 的自定义 reject 规则
- **WHEN** Android 重新生成 Root 配置
- **THEN** 全局自动 UDP/443 reject 不存在
- **AND** 用户保存的域名范围 reject 规则仍存在且保持原有匹配条件

#### Scenario: 便携备份保留 QUIC 默认与显式设置

- **GIVEN** 便携备份包含 settings 区段，且一个备份没有显式保存 `blockQuic`、另一个备份显式保存 `false`
- **WHEN** Android 导出并再次导入这两份备份
- **THEN** 缺失值在备份中物化为 `true`，导入时也补齐为 `true`
- **AND** 显式 `false` 在导出和导入后仍为 `false`

### Requirement: 默认广告与 Play 规则不劫持系统下载

默认广告规则和广告规则快捷/预设入口 MUST 使用本地 geosite 转换链支持的 `geosite-category-ads-all` 标签。新建的默认 Play Store 规则 MUST NOT 被应用强制启用，且 MUST NOT 注入 `com.android.providers.downloads` 或 `com.android.providers.downloads.ui`。迁移既有规则时，仅当规则 domains 含 `googleapis.cn` 且包名集合含任一上述旧注入包时，MUST 从该规则移除这两个包；MUST 保留规则原有 `enabled` 状态、其他包名及不符合该特征的规则。

#### Scenario: 创建默认广告与 Play 规则

- **GIVEN** 新安装触发默认路由规则创建，或用户使用广告规则快捷/预设入口
- **WHEN** 应用生成默认 route rule
- **THEN** 广告规则使用 `geosite-category-ads-all` 本地标签
- **AND** 默认 Play 规则不被强制启用且不包含 Downloads Provider/Download Manager 包名

#### Scenario: 升级已有 Play 下载注入规则

- **GIVEN** 已保存的 Play 规则 domains 含 `googleapis.cn`，且包名含一个或两个旧 Downloads 注入项
- **WHEN** `ProfileManager` 读取并迁移路由规则
- **THEN** 两个旧注入包名均从该 Play 规则移除
- **AND** 原有 enabled 状态与其他包名保持不变，非 Play 规则不受影响

### Requirement: 链式配置引用必须解析到最终出站标签

Android 配置生成器 MUST 在写入链式 detour、selector 成员或最终路由引用前确定被引用节点的最终标签。当同一节点既作为独立规则出站又作为另一条链的成员时，生成配置中的全部引用 MUST 指向实际存在的 endpoint 或 outbound 标签；构建顺序 MUST NOT 产生悬空引用。

#### Scenario: 共享节点先于包含它的链生成

- **GIVEN** 当前主节点是普通节点，路由规则同时引用共享节点和包含该共享节点的链式代理
- **WHEN** 应用生成正式连接配置，且共享节点先于链式代理生成
- **THEN** 链中前一跳的 detour 指向共享节点已生成的最终标签
- **AND** 最终配置不存在无法解析的 endpoint、outbound、selector 或 route final 引用

#### Scenario: 包含共享节点的链先生成

- **GIVEN** 当前主节点本身是包含共享节点的链式代理
- **WHEN** 应用生成正式连接配置
- **THEN** 链中的每个 detour 指向实际生成的后继标签
- **AND** 后续规则复用该节点时不得产生重复或悬空标签

### Requirement: 服务启动失败不得导致应用进程崩溃

Android 后台服务 MUST 将配置无效、依赖缺失或内核启动失败报告为服务启动失败，并 MUST 清理已部分初始化的 VPN、插件和 box 资源。清理已关闭资源时产生的可识别关闭完成状态 MUST NOT 作为主线程未处理异常导致 `:bg` 进程崩溃；原始启动错误 MUST 保留为用户可读错误和诊断日志。

#### Scenario: 内核启动回滚后服务执行清理

- **GIVEN** sing-box 在部分启动后因配置依赖错误失败，并已回滚关闭部分资源
- **WHEN** Android 服务执行失败清理
- **THEN** 服务进入停止状态且不触发崩溃处理器
- **AND** 用户看到原始启动失败原因，而不是后续的已关闭资源错误
- **AND** 清理阶段的异常状态被记录用于诊断

### Requirement: DNS 路由避免意外泄露

本机直解场景中的 `hosts` MUST 归一化为 `local`。远程 DNS 遇到 `hosts`、`local`、`localhost` 或 `fakeip` 占位符时 MUST 回退至明确的远程 DoH 地址并提示用户；远程 DNS MUST 显式经当前代理节点 detour。订阅自定义服务器 DNS 的 UI MUST 保持暂停开放，直到上游语义明确且强制解析、批量测速、正式连接三条路径能够一致实现。

#### Scenario: 用户把本机解析器配置为远程 DNS

- **GIVEN** 远程 DNS 设置为本机解析占位符
- **WHEN** 生成连接配置
- **THEN** 配置改用安全的远程 DoH 回退值
- **AND** DNS detour 指向当前节点
- **AND** 用户收到修改设置的提示

### Requirement: 未设置组级测速 URL 动态继承全局 URL

组级测速 URL 未显式设置时 MUST 保持空 override，并在每次生成组级 URLTest/LoadBalance outbound 或解析单节点测速链接时动态使用当前全局 `connectionTestURL`；显式组 URL MUST 优先。编辑页加载与保存 MUST NOT 固化全局/默认 URL。旧版自动写入且精确等于 `default_group_test_url` 的 fallback MUST 迁移为空 override，其他非空值 MUST 保留。Root snapshot 中组级 URLTest/LoadBalance outbound 与单节点解析 MUST 使用同一组级解析结果；Root mixed-inbound 的应用级连接测试探测规则及 Root health-check policy MUST 继续使用全局 `connectionTestURL`，不得继承组级 override。

#### Scenario: 空或旧 fallback override 跟随当前全局值

- **GIVEN** 一个组未设置测速 URL，或只存有旧版固定 `default_group_test_url`
- **WHEN** 全局 `connectionTestURL` 改变并重新生成组级 Root URLTest/LoadBalance outbound
- **THEN** 该组使用最新全局 URL，旧 fallback 被迁移为空 override
- **AND** 再次修改全局 URL 后，空 override 继续跟随新值

#### Scenario: 显式组 URL 保持组级测速优先

- **GIVEN** 某组保存了不同于旧 fallback 的明确测速 URL
- **WHEN** URLTest 或 LoadBalance outbound 生成且全局 URL 改变
- **THEN** 该组仍使用自己的显式 URL
- **AND** 未配置组 URL 的其他分组使用新的全局 URL

#### Scenario: 应用级 Root probe 仍保持全局 URL

- **GIVEN** 某组显式测速 URL 与全局 `connectionTestURL` 不同
- **WHEN** Root 配置生成应用级 mixed-inbound 探测规则和组级测速 outbound
- **THEN** 组级 URLTest/LoadBalance outbound 使用显式组 URL
- **AND** mixed-inbound 探测规则与 health-check policy 仍使用全局 URL

### Requirement: 测速与正式连接保持语义一致且实例隔离

URL 延迟测试 MUST 使用不注册 PlatformLogWriter 的测试实例，从而不创建或共享 `cache.db`。延迟与速度测试 MUST 沿用用户的 IPv6 模式和 outbound `domain_strategy`，并经受测节点对应的默认 outbound 建立连接。URL 延迟测试 MUST 预热后复用连接测量 RTT；速度测试 MUST 按用户选择执行下载+上传、仅下载、仅上传或简单下载，并持续提供每个节点的进度、最终吞吐量、错误和取消状态。成功完成的速度测试结果 MUST 按 profile 持久落地，在节点卡片右下角现有测速信息区域以灰色辅助文字显示；结果 MUST 在进度对话框或通知关闭、列表重绑/刷新及重新进入配置列表后继续可见，直到用户清除测试结果或后续速度测试覆盖该节点结果。吞吐量 MUST 使用专用结果字段或模型，MUST NOT 写入毫秒语义的 `ProxyEntity.ping` 字段。每个 box MUST 拥有独立的平台接口 wrapper，默认接口监视器 MUST 在首拨前同步注册，fd protect 对非 VPN 未运行类故障 MUST fail-fast。配置列表 MUST 提供“URL 测试本组”和“速度测试本组”操作，并 MUST NOT 提供批量直连 TCP Ping 或 ICMP Ping 操作。新安装或尚未持久化对应设置时，延迟测试 URL MUST 默认为 `http://cp.cloudflare.com/`，URL 延迟测试并发数 MUST 默认为 10；Throne 速度测试 MUST 沿用参考版本自己的节点调度和单节点连接并发策略，MUST NOT 使用 URL 延迟并发值 10。下载、上传及简单下载测速默认值 MUST 与采用的 Throne 桌面参考版本一致，并由测试设置和执行路径共同使用。应用升级 MUST 保留用户已持久化的自定义测试 URL、URL 延迟并发数及速度测试设置。

#### Scenario: 并发批量延迟测试

- **GIVEN** 主进程同时测试当前组的多个节点
- **WHEN** 用户执行“URL 测试本组”且测试实例启动并拨号
- **THEN** 各实例不创建共享 cache 文件
- **AND** 各实例拥有正确的网络接口缓存和 protect 结果
- **AND** 测速使用与正式连接一致的协议族与域名策略

#### Scenario: URL 延迟测试使用独立并发配置

- **GIVEN** 主进程同时执行当前组的 URL 延迟测试
- **WHEN** URL 延迟测试工作队列按默认配置启动
- **THEN** 最多 10 个 URL 延迟测试 worker 并发处理节点
- **AND** 该并发值不传递给 Throne 下载/上传速度测试

#### Scenario: 测试菜单不再提供直连 Ping

- **GIVEN** 用户打开当前组的测试菜单
- **WHEN** 菜单完成渲染
- **THEN** 用户可以选择“URL 测试本组”和“速度测试本组”
- **AND** 菜单中不存在批量 TCP Ping 或 ICMP Ping 操作

#### Scenario: 当前组执行下载和上传速度测试

- **GIVEN** 用户选择下载+上传模式且当前组包含多个可测试节点
- **WHEN** 用户执行“速度测试本组”
- **THEN** 每个节点的测速流量均经该节点建立
- **AND** 界面持续显示下载与上传进度
- **AND** 每个成功节点的最终下载与上传吞吐量按 profile 保存，并在对应节点卡片右下角以灰色辅助文字显示
- **AND** 关闭进度界面、刷新列表或重新进入配置列表后，该结果仍然可见
- **AND** 用户清除测试结果或该节点完成下一次速度测试时，旧速度结果被清除或覆盖
- **AND** 吞吐量不写入用于 URL RTT 毫秒值的 `ProxyEntity.ping` 字段
- **AND** 用户取消测试时所有进行中的测速任务停止并报告取消状态

#### Scenario: 当前组执行简单下载测试

- **GIVEN** 用户选择简单下载模式并配置了简单下载 URL
- **WHEN** 用户执行“速度测试本组”
- **THEN** 应用经每个受测节点下载该 URL 的响应体并按实际字节与耗时计算下载吞吐量
- **AND** 不执行上传阶段

#### Scenario: 首次使用测试默认配置

- **GIVEN** 用户是新安装用户或尚未保存过对应测试设置
- **WHEN** 应用读取延迟与速度测试配置
- **THEN** 延迟测试 URL 为 `http://cp.cloudflare.com/`
- **AND** URL 延迟测试并发数为 10
- **AND** 速度测试模式、超时、服务发现/下载/上传 URL 与简单下载 URL 和已记录的 Throne 桌面参考版本默认值一致

#### Scenario: 升级后保留自定义配置

- **GIVEN** 用户在升级前已保存自定义延迟 URL、并发数或速度测试 URL
- **WHEN** 应用升级并加载新版本默认配置
- **THEN** 已保存的自定义值保持不变
- **AND** 新默认值仅用于不存在持久化值的设置

### Requirement: 停止态仍可浏览、编辑与测速

服务停止时，用户 MUST 仍可浏览与编辑节点、订阅、路由规则与策略组，并可执行 URL 测速与
速度测速。任何管理入口 MUST NOT 以核心运行中为前置条件（配置页工具栏菜单与单节点弹出菜单
均 MUST 保留 URL 测试/速度测试入口）；停止态编辑 MUST 只更新持久配置与 Root 快照，
MUST NOT 启动核心。停止态的订阅更新 MUST 在手动更新 `http://` 订阅链接或
`updateWhenConnectedOnly` 且未连接时先请求用户确认；更新失败 MUST 保留上一版有效
Provider 与当前选择（下载/解析失败发生在写库事务之前，写库在同一个事务内完成）。
停止态的策略组编辑提交 MUST 复用现有 Root 编辑器链（CandidateSnapshot + validate →
apply + expectedRevision + 绕缓存确认），确认后 phase 仍为 stopped。
瘦管理器与停止态的测速 MUST NOT 依赖运行态 Clash Controller，改走 Root 侧短生命周期
测试会话（`wanboxctl node urltest/speedtest` → `rootbox --url-test/--speed-test`，
独立测试 box 实例）；完整 APK 仍走进程内 TestInstance。插件出站（external chains）的节点
本批 MUST NOT 走 Root 测试会话，App 侧 MUST 在调用前拒绝。服务在节点列表页由运行转为
停止时，列表 MUST 提示停核后仍可浏览、编辑与测速。

#### Scenario: Stopped service still edits nodes and subscriptions

- **GIVEN** 核心处于停止态
- **WHEN** 用户打开节点列表并编辑一个节点，随后更新一个订阅
- **THEN** 编辑与订阅更新均可完成且不启动核心
- **AND** 订阅更新失败时上一版 Provider 与当前选择保持不变

#### Scenario: Stopped thin-manager speed tests use the Root test session

- **GIVEN** 瘦管理器且核心处于停止态
- **WHEN** 用户对一个节点执行 URL 测试与速度测试
- **THEN** 两项测试经 Root 侧短生命周期测试会话完成并显示结果
- **AND** 工具栏与单节点菜单中的测试入口均可见
- **AND** 正式服务状态、当前选择与 Worker 不被修改

#### Scenario: Plugin-backed nodes refuse the Root test session

- **GIVEN** 节点配置含插件出站（external chains）
- **WHEN** 用户在停止态对其执行 URL 测试或速度测试
- **THEN** 测试被拒绝并显示可读原因，不启动 Root 测试会话

#### Scenario: Stopped service still edits policy groups

- **GIVEN** 核心处于停止态且存在一个可编辑的策略组
- **WHEN** 用户修改策略组成员并提交
- **THEN** 配置被更新且核心仍处于停止态
- **AND** 提交复用 validate → apply 与 expectedRevision 校验链

> 验证：CI 通过（feat 分支 Verify Android Build #194 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 混合入站行为由 TUN 设置显式控制

TUN 模式下，只要混合入站存在，VPN 服务 MUST 将系统 HTTP 代理指向本地混合端口。用户启用“禁用混合入站”时，配置 MUST 不生成 mixed 入站及其专属路由规则，VPN MUST 跳过系统 HTTP 代理设置，相关设置项 MUST 禁用。用户名为空时混合入站 MUST 免认证；用户名非空时应用内部 HTTP 请求 MUST 跟随运行时认证信息。

#### Scenario: TUN 模式禁用混合入站

- **GIVEN** 用户在 TUN 模式开启禁用混合入站
- **WHEN** 服务构建配置并启动 VPN
- **THEN** mixed 入站和专属规则不存在
- **AND** VPN 不调用系统 HTTP 代理设置
- **AND** 代理端口、认证和绕过列表设置显示为不可用

### Requirement: 桌面备份导入保持核心数据语义

Android 应用 MUST 能导入 Throne 桌面 `.thrbackup`：解析 `THRN` QDataStream 与内嵌 SQLite，忽略图标，尽力导入配置档、分组、路由和可映射设置。sing-box outbound SHOULD 优先还原为原生 Bean，失败时回退 `ConfigBean`。导入完成后 MUST 触发完整重启。

#### Scenario: 导入有效桌面备份

- **GIVEN** 用户选择有效的 `.thrbackup`
- **WHEN** 导入器完成解析
- **THEN** 支持的配置档、分组、路由和设置被映射到 Android 数据模型
- **AND** 不支持的 outbound 以自定义 JSON 配置保留
- **AND** 应用触发完整重启

### Requirement: 用户备份在新旧版本间保持可读与兼容

Android 应用 MUST 以 `profiles` 作为新版用户备份字段，MUST 将 route rules 导出为可读 JSON，并在用户选择 `settings` 区段时物化 DataStore 的稳定 Root 网络配置默认值：核心路由/DNS、应用/Wi-Fi/移动网/Root UID 路由策略、规则资源 provider/URLs、LAN 访问、全局 TLS 证书校验策略及共享连接测试 URL。显式保存的设置 MUST 优先保留；`INPUT_METHOD_DIRECT` 这类一次性迁移标记 MUST NOT 被当作普通缺省值写入。导入器 MUST 兼容旧 `proxies` 字段、旧 Base64 Parcel 规则、同一数组内混合 JSON 与 Base64 Parcel rule entries，以及旧 wanBox 用户备份。若 `settings` 区段未被选择，导入计划 MUST 保持未选择且 MUST NOT 创建该区段的默认值。内部 RootModuleSnapshot/data archive MUST NOT 被当作用户 JSON 备份处理。

#### Scenario: 导出新版用户备份并补齐稳定默认

- **GIVEN** 用户选择导出 profiles、rules 和 settings，且部分 Root 网络配置设置没有持久化值
- **WHEN** App 编码用户备份
- **THEN** profiles 使用 `profiles` 字段，route rules 使用可读 JSON
- **AND** settings 中缺失的稳定 Root 网络配置默认值被物化，显式值保持原样
- **AND** 未显式保存的一次性迁移标记不被补入

#### Scenario: 导入旧 wanBox 备份

- **GIVEN** 旧 wanBox 用户备份使用 `proxies`、Base64 Parcel 规则或部分旧版 settings
- **WHEN** 用户导入该备份
- **THEN** profiles、groups、rules 和被选择的 settings 可按兼容规则恢复
- **AND** 已保存值优先，缺失的稳定默认只在存在 settings 区段时补齐

#### Scenario: 混合 route entries 可导入

- **GIVEN** v1 或 v2 用户备份的 rules 数组同时包含 JSON object 与 Base64 Parcel string
- **WHEN** 导入器解析 rules
- **THEN** 两类 entry 均还原为语义等价的 RuleEntity
- **AND** 不支持或损坏的 entry 在写入数据库前被拒绝

#### Scenario: 未选择 settings 时保持未选择

- **GIVEN** 用户只选择导入 profiles 或 rules，备份没有 `settings` 区段
- **WHEN** 导入器解析备份
- **THEN** settings 保持未选择
- **AND** 不会仅因兼容逻辑而添加设置默认值

### Requirement: 启动入口与关键 UI 保持可达

Launcher `MainActivity` MUST 唯一声明静态快捷方式元数据；切换、启用、禁用和扫码四个快捷方式的目标包 MUST 与 `nb4a.properties` 的应用包名一致，目标 Activity MUST 可被 Launcher 启动。扫码界面 MUST 以底部悬浮按钮提供手电筒和图片导入，不依赖会被预览遮挡的工具栏。

#### Scenario: 校验启动快捷方式与扫码入口

- **GIVEN** 包名或扫码 UI 被修改
- **WHEN** 运行对应静态校验
- **THEN** 四个快捷方式、元数据唯一性和导出状态均合法
- **AND** 扫码界面的两个操作可见且已接线

### Requirement: 用户可见错误对话框安全显示

Android 应用在前台页面操作失败且已有可用宿主 Activity 时，MUST 显示包含原始可读错误消息的提示对话框。对话框使用主题包装 Context 时 MUST 正常解析其宿主，MUST NOT 因 Context 类型转换失败而丢失提示；宿主不存在、正在结束或已销毁时 MUST 安全跳过显示，且 MUST NOT 产生新的未处理异常。

#### Scenario: 规则资源更新失败且页面仍有效

- **GIVEN** 用户在规则资源页面发起更新，页面 Activity 仍处于可显示窗口的状态
- **WHEN** 更新操作失败并生成可读错误消息，且对话框 Context 被主题 wrapper 包装
- **THEN** 应用显示含原始错误消息的错误提示对话框
- **AND** 不产生 Context 到 Activity 的类型转换异常

#### Scenario: 显示错误前宿主页面已退出

- **GIVEN** 后台操作失败后，对话框准备返回主线程显示
- **WHEN** 宿主 Activity 不存在、正在结束或已经销毁
- **THEN** 应用安全跳过错误对话框显示
- **AND** 不产生新的未处理异常或无效窗口

#### Scenario: 配置状态错误提示复用安全显示入口

- **GIVEN** 用户点击处于错误状态的配置项，且宿主页面仍有效
- **WHEN** 应用请求显示该配置项的错误详情
- **THEN** 应用显示含该错误详情的提示对话框
- **AND** 主题包装 Context 不影响显示结果

#### Scenario: Smart Auto-stop Root snapshot Apply 失败安全处理

- **GIVEN** 用户在 Smart Auto-stop 设置中发起 Root snapshot Apply
- **WHEN** Apply 发生普通失败且宿主 Activity 仍可用
- **THEN** 应用通过现有安全宿主处理入口显示包含原始可读错误消息的对话框，主题包装 Context 不影响宿主解析
- **AND** 若显示时宿主 Activity 正在结束、已销毁或不存在，应用安全跳过显示，不产生新的未处理异常或无效窗口
- **AND** 若 Apply 抛出 `CancellationException`，异常继续传播，MUST NOT 被当成普通错误显示

### Requirement: Unified per-network rules merge Smart Auto-stop and Wi-Fi policy

高级设置 MUST 提供单一「按网络」规则表，取代 F1 智能启停的独立规则编辑入口；MUST NOT 同时存在两个可互相覆盖的网络开关。规则上限 MUST 为 5 条；新规则默认动作 MUST 为 `keep`。每条规则 MUST 由匹配器与动作组成：匹配器可组合 SSID（精确保留空格/大小写/逗号）、`gateway:` 默认网关、CIDR/精确本地地址、实际默认出口类型（`wifi` / `not_wifi`，由 Root 按 policy routing 读取）；同一条规则内多项匹配器为 AND，规则之间为 OR，按用户顺序优先匹配，仅首条匹配规则生效。动作 MUST 三选一且互斥：`suspend`（命中→优雅停核挂起）、`direct`（命中→运行时模式切 `Direct`，只改运行时，不覆盖默认模式）、`keep`（命中→保持代理）。无规则匹配时 MUST 保持当前默认行为。功能 MUST NOT 请求定位权限；SSID MUST 由 Root 侧按实际出口 station 读取（`iw`）。未知网络 MUST NOT 被当作非 Wi-Fi；指纹读取失败或 SSID 不可读时 MUST 保持当前状态并记录，MUST NOT 猜测切换；Wi-Fi 出口上 SSID 读取失败时，同一评估内 `gateway:`/CIDR 规则也不触发。`suspend` 动作 MUST 完整保留 F1 状态机。`direct` 动作 MUST 经 Clash API 切换并回读确认，参数不变时 MUST NOT 重载；API 失败 MUST NOT 重载兜底。F1 已保存规则 MUST 迁移为 `suspend` 动作规则并保留原顺序；功能关闭时 MUST 保留规则，MUST NOT 清空；迁移失败 MUST 保留原 F1 数据并报错。高级设置 MUST 提供默认关闭的网络自动挂起开关与规则表入口，最多允许 5 条规则。启用功能时，Android MUST 在 Root snapshot 中附带经校验的应用 `appPackage`。应用 MUST 只接受面向该应用的固定 `AUTO_STOP_STATE` action 和 `suspended`、`connected`、`stopped`、`failed`、`disabled` phase 值；phase event MUST NOT 携带网络标识、匹配规则或核心输出。命中规则后，Root supervisor MUST 优雅停止当前核心，只有核心确认退出并释放自己的 TUN/auto-route 后才可报告挂起；MUST NOT 全局 flush iptables/nftables 或操作其他实例路由。挂起时 MUST 保留显示“已挂起”及“恢复”“停止”按钮的通知；两个 action 分别调用 Root resume 与 stop。离开匹配网络后 MUST 自动恢复此前自动挂起的核心；用户在同一网络手动恢复后 MUST 抑制该 fingerprint 下的重复挂起，网络变化后才重新匹配；用户手动停止不得被自动恢复覆盖。

#### Scenario: 匹配网络后只在核心退出时显示挂起

- **GIVEN** 用户配置至多 5 条有效 IP/CIDR/`gateway:` 规则且当前物理网络匹配
- **WHEN** Root supervisor 请求核心优雅退出
- **THEN** 只有确认核心退出并释放本实例 TUN/auto-route 后，Android 才显示已挂起 phase 和可操作通知
- **AND** 不请求定位权限，phase event 不包含网络地址、gateway、SSID 或规则文本

#### Scenario: 离开网络与同网手动恢复

- **GIVEN** 核心因当前 network fingerprint 自动挂起
- **WHEN** 设备离开匹配网络，或用户在同一网络手动恢复
- **THEN** 离开网络时原运行意图自动恢复一次；同网手动恢复抑制该 fingerprint 的再次挂起
- **AND** 通知的恢复/停止按钮分别执行 Root resume/stop，手动停止不会被自动恢复覆盖

#### Scenario: First matching rule wins and actions do not fight

- **GIVEN** 用户保存两条规则：首条匹配当前 Wi-Fi 且动作为 `direct`，次条同样匹配且动作为 `suspend`
- **WHEN** 设备接入该 Wi-Fi
- **THEN** 运行时模式切换为 `Direct`，核心不被挂起
- **AND** 默认模式保持不变

#### Scenario: Unknown network never guesses

- **GIVEN** 当前网络指纹不可读（SSID 尚不可读）
- **WHEN** 网络评估触发
- **THEN** 不切换模式、不挂起核心
- **AND** 评估失败被记录用于诊断

#### Scenario: F1 rules migrate without loss

- **GIVEN** 用户此前保存了 3 条 F1 规则且功能开启
- **WHEN** 升级后打开按网络规则表
- **THEN** 3 条规则原顺序保留且动作均为 `suspend`
- **AND** 关闭功能后规则仍在表中

Android MUST reconcile notifications only for the fixed phases `suspended`, `connected`, `stopped`, `failed`, and `disabled`; unknown actions or phases MUST be ignored. After a notification Resume or Stop action, Android MUST query Root status even if the command fails, and MUST use a recognized returned phase as authoritative. If notification delivery or status communication fails, or the queried phase is unknown, Android MUST preserve the last successfully displayed suspended notification rather than dismissing it based on the action alone. Coroutine cancellation MUST propagate.

#### Scenario: Notification action loses its reply or status is unavailable

- **GIVEN** Android last successfully displayed the actionable suspended notification
- **WHEN** the user selects Resume or Stop and the command reply fails
- **THEN** Android MUST still query authoritative Root status and reconcile a recognized phase
- **AND** if the status query fails or returns an unknown phase, Android MUST retain the last suspended notification and its actions
- **AND** a failed notification delivery MUST NOT dismiss the last successfully displayed suspended notification

Before enabling Smart Auto-stop on every supported Android version, Android MUST create/inspect the `smart_auto_stop` notification channel and verify both app-level notification availability and channel importance other than `IMPORTANCE_NONE`. On API 33+ it MUST request missing runtime notification permission first and recheck app/channel availability after the result. If any gate remains blocked, the feature MUST remain disabled with actionable app/channel notification settings. Notification settings MUST remain available while the feature is enabled. Snapshot generation MUST derive the current Android user/profile through a supported Android API and include its integer `userId` alongside `appPackage`.

#### Scenario: App or channel notifications are blocked

- **GIVEN** app notifications are disabled on API 31–32, or the Smart Auto-stop channel has `IMPORTANCE_NONE` on any supported Android version
- **WHEN** the user tries to enable Smart Auto-stop
- **THEN** the feature remains disabled and an actionable notification-settings entry is available
- **AND** granting API 33+ runtime permission alone cannot bypass a blocked channel

#### Scenario: Notification controls remain discoverable

- **GIVEN** Smart Auto-stop is enabled with app and channel notifications available
- **WHEN** the user opens advanced settings
- **THEN** notification settings remain accessible
- **AND** returning from notification settings rechecks availability before retaining the enabled setting

> 验证：CI 通过（feat 分支 Verify Android Build #194 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: Shared network interception is an explicit opt-in eBPF shared path

设置页 MUST 提供默认关闭的“接管共享网络流量（热点 / USB 共享）”开关，说明 MUST 明确本开关不创建热点、DHCP、NAT 或 IPv6 RA。开启时 MUST 使用 eBPF 共享路径：`shared.interface` MUST 含至少一个真实下游接口（不能填 loopback），`data_plane` MUST 为 `socket_assign` 或 `packet_rewrite`，`dns_mode` 默认 `hijack`，`default_interface` 默认 `false`。接口选择 MUST 用 Root 枚举出的真实下游接口候选列表供用户勾选（由 Root 侧校验、loopback 不得入选），MUST NOT 采用手输接口名。开启前 MUST 通过 eBPF 能力预检；预检失败 MUST 明确报错，MUST NOT 自动切换数据平面。`default_interface=false` 时，已配置的共享接口成为默认上游后 MUST 暂时停止共享挂载，恢复为下游后重新挂载。TUN 模式下共享接管 MUST 明确不支持；TUN 用户的 LAN 网关能力保持不变，MUST NOT 在文案中把 LAN 网关描述为流量接管。分应用名单 MUST 只筛选本机应用，MUST NOT 用来识别热点客户端。

#### Scenario: Shared interception requires a downstream interface

- **GIVEN** 用户开启共享接管但未配置下游接口
- **WHEN** 保存并尝试应用
- **THEN** 保存被拒绝并提示未配置下游接口
- **AND** 核心不启动共享挂载

#### Scenario: eBPF preflight failure blocks shared interception

- **GIVEN** 用户开启共享接管且所选数据平面能力预检未通过
- **WHEN** 应用共享配置
- **THEN** 应用被拒绝并显示预检原因
- **AND** 数据平面不被静默切换

#### Scenario: TUN mode keeps LAN gateway without interception

- **GIVEN** 当前为 TUN 回退模式且用户打开 LAN 共享页
- **WHEN** 查看共享相关文案
- **THEN** LAN 网关能力可用
- **AND** 文案不把 LAN 网关描述为共享流量接管

> 验证：CI 通过（feat 分支 Verify Android Build #194 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: Per-app lists are keyed by Android user and support clones

分应用黑/白名单 MUST 以严格的 `<userId>:<package>` 字符串为持久化键；同一包名在不同用户（含工作资料与应用分身）下 MUST 为不同条目，UI MUST 按用户分组展示。名单解析 MUST 由 Root 侧按用户查询 Android package service 得到 UID 后合并为 `include_uid`/`exclude_uid`，MUST NOT 把包名直接交给 sing-box，MUST NOT 跨用户合并 UID。白名单解析后与原生 include UID/range 均为空时，运行时 MUST 设置 `exclude_uid_range=["0:4294967294"]` 排除全部有效 UID，MUST NOT 生成空 include 后静默变为全量接管，MUST NOT 注入占位 UID；空白名单护栏下管理器自身 UID MUST NOT 被当作唯一 include。仅 eBPF 共享路径时 MUST NOT 查询应用 UID。旧单 UID 名单独 MUST 按当前用户反查包名迁移为 `<userId>:<package>`；无法反查的条目 MUST 保留在旧字段并提示用户手动确认，MUST NOT 静默丢弃；本轮 MUST NOT 设定旧字段清理版本，旧字段只读保留、清理留待以后版本另行决定。

#### Scenario: Clone of the same app is listed separately

- **GIVEN** 同一包名在用户 0 与用户 10（应用分身）均已安装
- **WHEN** 用户打开分应用列表
- **THEN** 该包名出现两次，分属不同用户分组
- **AND** 只勾选其中一个用户时，另一个用户的同包名应用不受影响

#### Scenario: Empty whitelist does not become full interception

- **GIVEN** 用户开启白名单模式但名单为空
- **WHEN** 生成 Root 入站配置
- **THEN** 运行时包含 `exclude_uid_range=["0:4294967294"]`
- **AND** 不生成空 `include_uid`

#### Scenario: Unresolvable legacy UID entries are kept for review

- **GIVEN** 旧名单中有一条无法反查包名的 UID
- **WHEN** 执行迁移
- **THEN** 该条目保留在旧字段并提示用户手动确认
- **AND** 其余可反查条目正常迁移

> 验证：CI 通过（feat 分支 Verify Android Build #194 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 模块诊断必须呈现模块已上报的退出与事件信息

设置页的模块状态区域 MUST 呈现 Root 模块状态中已上报的诊断信息：健康状态、
最近一次退出的原因与退出码、退出时间、看门狗重试次数，以及最近的模块事件。
事件 MUST 有界呈现（最多最近 5 条，最新在前，含时间与事件码）。任一字段
在状态中缺失或为空时，对应条目 MUST 隐藏，MUST NOT 显示编造值或占位假值；
旧模块完全不上报诊断字段时，诊断区 MUST 整体不出现。诊断数据 MUST 来自
既有的模块状态通道，MUST NOT 为此新增模块协议或要求模块改动。

#### Scenario: 核心退出后可查看诊断

- **GIVEN** 模块状态含 lastExit、lastReason、lastExitAt、retryCount 与若干事件
- **WHEN** 用户打开设置页的模块状态区域
- **THEN** 可见退出原因、退出码、本地时间格式的退出时间、重试次数与最近事件

#### Scenario: 旧模块不上报时不显示假诊断

- **GIVEN** 模块状态不含任何诊断字段（旧版本模块）
- **WHEN** 用户打开设置页的模块状态区域
- **THEN** 诊断区不出现，既有状态行与版本码显示不受影响

#### Scenario: 事件过多时有界呈现

- **GIVEN** 模块状态含 8 条事件
- **WHEN** 用户查看诊断区
- **THEN** 只显示最近 5 条且最新一条在最前

### Requirement: 设置页可一键导出脱敏诊断包

设置页 MUST 提供一键“导出诊断包”入口，默认导出到系统 Download 目录，文件名固定为
`wanbox-diagnostics-<时间>.tar.gz`（时间戳为 RFC 3339 本地时间转文件名安全形式，
不含设备标识与用户标识）。导出前 MUST 显示确认说明，明确包内不含凭据、
但含路由规则域名与脱敏后的地址信息（文案见 change 的 design.md §10 原句）。
诊断包 MUST 为 gzip 压缩 tar（`.tar.gz`），先写同目录临时文件并 `fsync` 后原子重命名到目标；
目标 MUST 是普通文件，已存在目录或 symlink 时 MUST 拒绝。包内 MUST 包含脱敏后的
service/core 日志（轮转备份存在则收录、缺失不失败，每文件取尾部，上限 4 MiB/文件）、
Root 快照、生成配置、模块状态与服务状态、本地规则，以及版本/时间/脱敏声明/缺失文件清单的
README。包内 MUST NOT 包含远程规则集原文（只列名称/来源/哈希/更新时间）、用户备份文件、
`cache.db`、私钥/证书或 Catalog staging 临时文件。导出完成 MUST 显示目标路径与大小；
失败 MUST 显示可读原因。缺失的单个文件 MUST 只记入 README 缺失清单，MUST NOT 导致整包失败。
诊断包 MUST NOT 含“可选脱敏用户备份”选项。

脱敏规则 MUST 按 change 的 design.md §2 的表执行：订阅地址全文隐藏；节点链接全文隐藏；
URL 内凭据、认证头与敏感 JSON 键值替换为 `***`；SSID 逐条替换为 `***`（条数保留）；
分应用名单整体替换为 `***`；服务器地址与 SNI 替换为 `***`；公网 IPv4 保留前两个 octet、
其余打码，公网 IPv6 保留前 32 位、其余打码，私网/回环/链路本地/CGNAT 只保留类别 token；
MAC 地址全文打码；无法按字段脱敏的损坏 JSON MUST 省略原文并替换为固定 diagnostic_error 说明；
单行脱敏消息截断上限 1024 rune。

#### Scenario: Export succeeds with redaction applied

- **GIVEN** 用户在设置页打开导出诊断包并确认说明
- **WHEN** 导出完成
- **THEN** 目标路径出现一个 `.tar.gz` 文件且界面显示路径与大小
- **AND** 包内日志与配置不含订阅地址、节点凭据、服务器地址、SSID 明文、完整公网 IP 或 MAC 地址
- **AND** README 列出版本、生成时间与缺失文件清单

#### Scenario: Export refuses a non-file target

- **GIVEN** 用户指定的导出目标是一个已存在目录
- **WHEN** 执行导出
- **THEN** 导出被拒绝并显示可读原因
- **AND** 不创建或覆盖任何文件

#### Scenario: Corrupted JSON is omitted instead of leaked

- **GIVEN** 某份待导出 JSON 配置已损坏、无法按字段脱敏
- **WHEN** 执行导出
- **THEN** 包内该文件被替换为固定 diagnostic_error 说明，不含原文
- **AND** 其余文件仍正常导出

> 验证：CI 通过（feat 分支 Verify Android Build #194 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

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

稳定实体 ID MUST 作为数据库键及节点成员引用键，节点与组采用独立命名空间。规则生成仍按既有 outbound tag 契约引用；Selector/URLTest 的直接成员仍为节点，组间引用沿用负载均衡目标组；N6（borrow-netproxy-nightly）起规则可按组 ID 指定策略组目标（见“Route rules may target a policy group”），仍不新增组直接成员。主组 Selector/URLTest/LoadBalance outbound 的运行时 tag MUST 固定为 `proxy`；非主组路由兼容名称 MUST 使用 trim 后组名。节点链路 tag MUST 按生成上下文区分：直接节点为 `g-<id>`，链式节点为 `chainTag-<id>`，不得要求所有节点均为 `g-<id>`。新建策略组的路由兼容名称 trim 后 MUST 非空；路由兼容名称和类型创建成功后 MUST 只读；主组运行时 tag `proxy` 不随名称变化。资源显示名改动 MUST NOT 隐式修改组身份/tag 或破坏引用，不新增全局历史分组禁改名规则。策略组编辑 MUST 只改变配置 outbounds 分区，MUST 保持其他分区内容、路由生成顺序和用户规则顺序。

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

### Requirement: Route rules may target a policy group (N6)

路由规则 MUST 支持以策略组为目标：`RuleEntity` 新增可空的策略组目标（稳定实体 ID 引用，数据库版本 11 的 `policyGroupId` 列，缺失即 NULL），与节点 `outbound: Long` 正交；两者同时存在时 MUST 拒绝保存并定位到目标字段（节点出站非 0，或动作为非路由动作时均违规）。内置目标（`0`=proxy、`-1`=bypass、`-2`=block）保持不变。生成配置时，规则 MUST 写原生 `route.rules` 的 `outbound=<策略组运行时 tag>`（主组固定 `proxy`，非主组为 trim 后组名；被引用的非主组在生成时获得一个 selector 出站，成员为该组节点）；插入位置 MUST 在默认全局模式、DNS 接管及本地快捷规则之后、内置站点分流之前（组目标规则仍在同一用户规则循环内生成，显式规则天然排在 fallback 层之前）；无法确认插入位置时（多条件/`logical`/`invert` 设置，或全局/当前节点自定义配置可能改写 `route.rules`）MUST 交给 JSON 编辑器，表单拒绝保存。规则引用的策略组不存在时 MUST 复用现有缺失引用预检：列表标红、启动前 modal 汇总（类型文案为“策略组”）。删除被路由规则引用的策略组时 MUST 在同一数据库事务内阻断并定位到该组与首条引用规则（对话框标题为组名，可跳转到引用规则）；已提交的改名 MUST NOT 使引用失效（引用按稳定 ID 解析，tag 在生成时由当前组名重算），空白组名在组设置保存时已有非空回退。组目标类型默认不启用，用户显式选择后才生效；默认规则与默认 Play/广告规则不受影响。多条件、logical、invert 与其他原生字段 MUST 保留原样，走 JSON 编辑器。备份恢复 MUST 校验策略组目标在备份分组或目标设备分组内，否则拒绝写入。

#### Scenario: Rule targets a policy group

- **GIVEN** 存在一个名为 `streaming` 的策略组与一条单条件域名规则
- **WHEN** 用户将该规则目标设为 `streaming` 并启动核心
- **THEN** 生成配置的对应 `route.rules` 条目 `outbound` 为 `streaming`
- **AND** 该规则位于本地快捷规则之后、内置站点分流之前

#### Scenario: Rule targets a missing group

- **GIVEN** 一条规则的目标策略组已被删除
- **WHEN** 用户打开路由规则列表并尝试启动核心
- **THEN** 该规则显示红色错误状态与“该规则指向的策略组已不存在。”
- **AND** 启动前出现列出规则与缺失策略组的提醒

#### Scenario: Deleting a referenced group is blocked

- **GIVEN** 一条启用规则引用策略组 `streaming`
- **WHEN** 用户尝试删除 `streaming`
- **THEN** 删除被阻断并定位到 `streaming`
- **AND** 规则与配置保持不变，选中节点/分组不被迁移

#### Scenario: Rule cannot target both a node and a group

- **GIVEN** 一条规则已选节点目标
- **WHEN** 用户同时为其选择策略组目标并提交
- **THEN** 提交被拒绝并定位到目标字段
- **AND** 原规则保持不变

> 验证：CI 通过（feat 分支 Verify Android Build #194 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

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

### Requirement: 4a effective baseline and recoverable editor transaction

编辑器候选 MUST 以 expectedRevision 对应的模块当前有效完整快照为基准，仅对 config.outbounds 做 copy-on-write 补丁，保留 TUN 回退、DNS、入站、资源与其他分区。无法读取或保持基准 MUST 拒绝编辑提交；当前无有效配置时允许拒绝并要求先建立有效配置，不得以双重建比较代替基准。未应用的 App 设置 MUST NOT 被重建带入候选。

编辑器 MUST 先记录旧草稿/偏好值、实体存在性和基准快照，再完成可回滚本地准备，随后 validate → apply → 绕缓存确认。同一候选 bytes 与 expectedRevision MUST 贯穿 validate/apply。任一阶段失败 MUST 恢复本地旧值；apply 已尝试时 MUST 先绕缓存读取 current revision；仅当其等于本次 apply 响应实际产生的 revision 或 baseline 原 revision 时，才允许经相同 validate/apply 通道回滚基准并绕缓存确认 revision/state。第三种 revision MUST NOT 写回 baseline，MUST 保留日志并返回 cleanup 类阻断错误；apply 响应丢失而无法确认 revision 归属时同样阻断。回滚不确定 MUST 返回 cleanup 类错误并保留恢复日志、阻断后续提交/普通重载；只有明确恢复旧配置后才解除阻断。停止态恢复 MUST 不启动核心。

新建组 MUST 进入既有 ProxyGroup 列表、负载均衡目标候选及冻结组集合。负载均衡目标组 MUST 使用已保存的稳定节点成员列表；节点 ID 与组 ID MUST 采用独立命名空间，缺失链成员、嵌套组目标或组成员 MUST 在 capture 前定位字段并阻断。普通配置生成 MUST 保持 DataStore.resolvedGroupUrlTestUrl 的既有契约，仅对精确等于默认值的存储值执行持久迁移。普通构建最终 outbounds 补丁 MUST 使用该解析的最终 URL，不得被保存草稿中的旧默认值覆盖。候选的空 URL override MUST 继承 DataStore 全局测试 URL，不得继承基准 outbound 中的旧 override。

#### Scenario: cleanup 后非法草稿仍保持阻断

- **GIVEN** 编辑器进入 cleanup_required，应用状态尚未确认
- **WHEN** 再次提交空成员或非法 interval 草稿，或页面重建
- **THEN** MUST 保持不确定/阻断文案，MUST NOT 被普通校验的“未应用”覆盖；恢复确认前 MUST NOT 重新启用草稿表单。仅恢复完成、journal 清除且 fresh status 确认模块已回到已知状态后，才允许解除阻断。

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

Discarding an unfinished edit MUST first restore journaled local values while retaining the durable journal. If fresh module status reports connected, the current local configuration MUST be rebuilt under the existing mutation lock without removing that journal; a subsequent cache-bypassing fresh status MUST confirm the applied revision, the matching running revision and a known connected/suspended state before journal deletion or success. A rebuild failure, timeout, unknown state or mismatched revision MUST retain the journal, recovery entry and connection/editing barrier. The same persisted journal MUST keep recovery available after Activity destruction/recreation and MUST support retry. Discard MUST NOT use its narrow locked recovery permission to bypass the journal barrier for normal startup or reload. An unresolved outbound-reference warning about the configuration being reapplied MUST NOT block that locked rebuild: discard restores the pre-edit configuration the module was already running, so the warning computed from the same build snapshot MUST stand in for that rebuild's confirmation, while the next normal start or reload MUST still require explicit confirmation. Fresh stopped or suspended status MUST result only in local restoration/marker cleanup, with no rebuild or start and no change to module running intent. Other initial module states or installer selection MUST remain blocked.

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

#### Scenario: Dangling outbound reference does not deadlock discard

- **GIVEN** an enabled rule whose node was deleted, so the current configuration raises an outbound-reference warning, and a durable unfinished-edit journal
- **WHEN** the user discards the unfinished edit while the module is connected
- **THEN** the locked rebuild MUST apply the configuration that same snapshot describes, the confirmed applied/current/running revisions MUST still gate journal deletion, and the next normal start or reload MUST ask for that warning again

### Requirement: 开机自启开关与模块 boot 配置保持一致（v3.0.18-preview.1 复审修复）

开机自启有两个持久点：模块 `boot.json`（`wanboxctl autostart on/off`，决定开机是否
拉起核心）与 App 的 `DataStore.persistAcrossReboot`（`isAutoConnect`，决定
`BootReceiver` 与界面显示）。设置页的切换 MUST 走「下发 → 回读确认 → 落库」：
命令退出码 MUST NOT 单独作为生效证据，MUST 用模块状态回读（`state.autoStart`，
`boot.json` 生效值；没有覆盖项时回落最近快照）核对；仅当回读值等于目标值（或
模块为不支持回读的老版本）时才写 `DataStore` 并同步 `BootReceiver`。
失败、超时与结果未知 MUST 重试一次；仍失败 MUST 保持原值（不落库）、把开关显示
恢复到已确认值并给出中文错误提示，MUST NOT 留下“界面已开、模块未开”的假象。
快速连续切换 MUST 由最后一次选择落地：更早的请求在发现选择已变后 MUST NOT 落库，
MUST NOT 用迟到结果覆盖新选择。返回设置页或重新进入应用 MUST 回读模块并与
`DataStore` 对比：不一致时 MUST 显示可见的“待同步”状态并允许再次切换同步。
模块未安装 / 未运行 / 运行中三种情况 MUST 都能下发并回读（`boot.json` 与快照
不依赖核心进程）；未运行时 MUST NOT 因此擅自启动核心。

**boot 配置不可用的状态契约（PR #80 复审 P2 修复）**：`boot.json` 的三种情况 MUST
分开处理，MUST NOT 一律报开、报关或报错：

- **不存在**：没有覆盖项，MUST 回落快照的 `autoStart`，并给出 `autoStartSource=snapshot`；
- **格式损坏 / 字段缺失 / 字段类型无效**：MUST 报 `boot_settings_invalid`，
  `autoStart` MUST 为 null（未知）、MUST NOT 用快照值冒充生效值，`Boot()` MUST
  拒绝自启并返回同一错误码，MUST NOT 改写或删除损坏文件；
- **存在但读不出来（权限、I/O、同名目录）**：MUST 报 `boot_settings_unreadable`，
  语义同上；
- **生效值可读**：MUST 给出 `autoStart` 与 `autoStartSource=boot`。

App 收到 `autoStartError` 时 MUST 按“待处理”提示（配置损坏、本次开机不会自启、
再切一次开关即重写配置），MUST NOT 把它当成待同步、也 MUST NOT 把开关状态当成已生效；
本次下发 MUST 按失败处理、不落库。用户显式下发 MUST 是修复路径：原子重写覆盖项后
回读恢复正常，且 `boot.json` 只存这一个布尔，MUST NOT 丢弃其他可恢复信息。

- **GIVEN** 用户打开开机自启且模块写入成功
- **WHEN** 下发完成
- **THEN** 回读为开、`DataStore` 与 `BootReceiver` 同时为开
- **GIVEN** root 被拒或命令超时
- **WHEN** 重试后仍失败
- **THEN** 开关回到原值、`DataStore` 不变，并显示中文错误
- **GIVEN** 命令返回成功但模块 `boot.json` 仍是旧值
- **WHEN** 回读核对
- **THEN** 按未生效处理并回滚，不落库
- **GIVEN** 用户快速连点开关两次（开→关）
- **WHEN** 两个下发互相交错
- **THEN** 只有最后一次选择被落库，先到的迟到结果不得覆盖
- **GIVEN** 上次下发超时但模块其实写成功
- **WHEN** 再次进入设置页
- **THEN** 显示“待同步”并提示再切一次开关同步，而不是静默改写用户数据
- **GIVEN** 模块未安装 / 已停止
- **WHEN** 切换开关
- **THEN** 命令给出稳定错误码并保持原值；模块已停止时不得被擅自启动
- **GIVEN** `boot.json` 损坏（`{`、空文件、缺字段、字段类型不对）而快照为开启
- **WHEN** 读取状态并尝试开机自启
- **THEN** 状态报 `autoStart=null` + `boot_settings_invalid`，`Boot()` 以同一错误码拒绝启动，两边口径一致
- **GIVEN** `boot.json` 不可读（权限/I/O）
- **WHEN** 读取状态
- **THEN** 报 `boot_settings_unreadable`，MUST NOT 伪装成开启或关闭
- **GIVEN** 模块 `boot.json` 损坏时用户再切一次开关
- **WHEN** 下发成功
- **THEN** 覆盖项被原子重写、回读恢复正常值，开关与 `DataStore` 按新值落地
- **GIVEN** 下发写入失败（例如覆盖项路径不可写）
- **WHEN** 回读状态
- **THEN** 保持未知 + 错误码，MUST NOT 显示未经确认的生效状态

> 验证：`AutoStartSyncTest`（JVM，12 条）与 `rootmodule` 的
> `TestAutoStartReadBackReflectsBootSettings`、`TestCorruptBootSettingsReportedAsUnknownAndNeverBooted`、
> `TestBootSettingsDistinguishMissingInvalidAndUnreadable`、`TestBootSettingsWriteFailureAndRepair`
> 覆盖回读、失败、重试、快速切换、待同步与损坏配置；Android 编译与插桩由 PR 的
> `Verify Android Build` 覆盖；真机未验证。

### Requirement: 测速刷新间隔未设置时默认 1000ms（B1 已落地）

`speedInterval` 未读取到用户设置时 MUST 默认 1000ms，与设置页一致，MUST NOT 回退为 0ms
导致高频无效测速开销。

- **GIVEN** 用户从未设置过测速刷新间隔
- **WHEN** 管理器读取 `speedInterval`
- **THEN** 读到 1000，且测速刷新按 1000ms 节奏执行

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 纯节点切换走热切换通道，配置类变更仍走配置重建（B1 已落地）

纯节点切换 MUST 独立走热切换通道（`switchProfileService` 等价物），不得触发完整配置重建；
路由规则、代理模式及分组设置等配置类变更 MUST 严格触发配置重建生效。
热切换 MUST NOT 中断现有连接。

- **GIVEN** 服务已连接，当前节点为 A
- **WHEN** 用户在 SwitchActivity 或快捷切换里把节点切到 B（未改任何配置项）
- **THEN** 只做节点热切换，现有连接不断，B 在秒级接管新连接
- **GIVEN** 用户改了路由规则或代理模式
- **WHEN** 保存并应用
- **THEN** 触发配置重建，而不是热切换

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: leastPing 组冷启动按缓存延迟预排序（B1 已落地）

配置生成时，leastPing 策略组的成员顺序 MUST 按缓存延迟预排序：健康有延迟
（status 1 且 ping > 0）在前并按延迟升序、有延迟但未确认健康其次、未测再次、
已知失败（status 2/3）在后；使内核首包不盲连列表首位。排序只改变生成配置里的
成员顺序，不改变任何持久化数据；failover 等其他策略 MUST 保持原有成员顺序。

- **GIVEN** leastPing 组内节点 A 健康延迟 80ms、节点 B 健康延迟 900ms、节点 C 未测、节点 D 已知失败
- **WHEN** 生成配置
- **THEN** 成员顺序为 A、B、C、D（失败在后）
- **GIVEN** failover 策略组
- **WHEN** 生成配置
- **THEN** 成员顺序保持原有顺序，不做冷启动预排序

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 组内成员切换后落地 IP 缓存必须失效（B1 已落地）

落地 IP 缓存 MUST 按实际在跑的节点（`currentProfile`）键入，而不是按选中项
（`selectedProxy`，组内切换时仍是组 id）；选择器上报的任何变化 MUST 清空缓存，
否则组内成员切换后 60 秒 TTL 内仍会返回旧成员的出口 IP。

- **GIVEN** 组内成员从 M1 切换到 M2
- **WHEN** 首页读取落地 IP 缓存
- **THEN** 不得返回 M1 的旧出口 IP，应重新查询

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 订阅自动更新间隔预设、自定义弹窗与人性化摘要（B2 已落地）

订阅设置 MUST 提供常用间隔单选预设（15 分钟、30 分钟、1 小时、3 小时、6 小时、12 小时、24 小时/1 天），
并支持自定义弹窗：纯数字软键盘、「分钟 / 小时 / 天」单位切换、明确的有效性校验与最小 15 分钟限制提示。
设置项摘要 MUST 人性化展示（例如“每 24 小时 (1天)”、“每 12 小时”），关闭自动更新时 MUST 明确显示“已关闭自动更新”。
`autoUpdateDelay` 的分钟值语义 MUST 不变（默认 360）。

- **GIVEN** 用户打开订阅自动更新设置
- **WHEN** 选择“24 小时”预设
- **THEN** 摘要显示“每 24 小时 (1天)”，且 `autoUpdateDelay` 存为 1440
- **GIVEN** 用户在自定义弹窗输入 10 分钟
- **WHEN** 确认
- **THEN** 提示最小为 15 分钟并拒绝保存
- **GIVEN** 用户关闭自动更新
- **WHEN** 返回订阅设置页
- **THEN** 摘要显示“已关闭自动更新”

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 订阅到期补更触发（B2 已落地）

进入订阅分组页及代理连接成功时，管理器 MUST 检查到期订阅并触发补更。
补更 MUST 尊重既有 per-group 失败退避与 `updateWhenConnectedOnly`；
未到期的订阅 MUST NOT 被提前更新。

- **GIVEN** 某订阅已到期且未处于失败退避期
- **WHEN** 用户进入订阅分组页或代理连接成功
- **THEN** 触发该订阅的更新
- **GIVEN** 某订阅处于失败退避期
- **WHEN** 触发补更检查
- **THEN** 跳过该订阅，不打断退避

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 订阅后台调度收归主进程（待用户拍板后才实施）

（补记：本条来自 port delta `openspec/changes/port-ownbox-26-11-8-20/specs/android-application/spec.md` 的 O5 2.4，主规范漏合，2026-11-18 发布后回填；状态仍为**待拍板、未实施**，正文与 delta 一致。）

若用户拍板迁移，订阅后台调度 MUST 收归应用主进程原生 `WorkManager` 管理，
不得继续用 `:bg` 跨进程 `RemoteWorkManager`；
迁移 MUST 先解决 `UpdateTask` 对 `:bg` 维护的 `DataStore.serviceState` 的依赖，
不得在主进程读不到连接状态时误判 `updateWhenConnectedOnly`。
初始延迟 MUST 按各订阅到期时间精确计算，不得截断为 60 秒；
冷启动时 MUST 在后台协程中校验并恢复定时调度任务。

- **GIVEN** 用户拍板迁移且手机重启后首次打开应用
- **WHEN** 应用冷启动完成
- **THEN** 定时调度任务被校验并恢复，到期订阅仍能按时更新
- **GIVEN** 某订阅 3 小时后到期
- **WHEN** 调度任务入队
- **THEN** 初始延迟约为 3 小时，不得被截断为 60 秒

### Requirement: 应用路由生成层维护集中式系统组件排除名单（B3 已落地，#182 修红修正）

应用路由生成层 MUST 维护集中式系统组件排除名单（`SystemExcludedPackages`，唯一来源），至少包含系统下载器
（`com.android.providers.downloads`、`com.android.providers.downloads.ui`）、媒体存储、
网络栈及 Captive Portal 门户组件；名单 MUST NOT 用于改写用户显式规则——用户规则的包名 MUST 原样生成
（修红 #182：生成层过滤会静默丢弃显式规则，使其流量改走默认路由，反而可能被分流进代理，与排除本意相反）；
名单的适用层是智能分流分类（上游 `SmartRouteRules` 的分类派生绑定；wanBox 暂无该层，名单先行作为唯一来源），
与 `ProfileManager` 既有下载器排除（仅剔两个下载器包名）合并去重，不得重复造两份名单。

- **GIVEN** 系统下载器发起下载
- **WHEN** 应用层规则生成并匹配
- **THEN** 生成结果 MUST NOT 包含把下载器强绑进代理的分类派生绑定；用户显式规则原样保留

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 规则集缓存按实际规则条数校验（B3 已落地）

`.srs` 规则集缓存 MUST 前后端同步校验实际规则条数；0 规则占位文件 MUST NOT 被当作有效缓存，
MUST 重新拉取或明确报错。远程规则集定时刷新时 MUST 跳过 `local:` 条目。
`local:` 本地规则集支持以 B3 核查结论为准：wanBox 规则模型不支持 `local:`
（无 `RuleSetCatalog`、无多目录解析、无 `local:` 标签模型），已在评估里写明理由并跳过；
用户输入 `local:` 时配置生成即明确拒绝，不得静默接受。

- **GIVEN** 本地存在 0 规则的 `.srs` 占位缓存
- **WHEN** 规则集被加载或刷新
- **THEN** 该缓存被判定无效并重新拉取，不得静默当作有效规则集使用

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: leastPing 冷启动预排序是纯顺序，过期判定在 Go 侧（B4 已落地）

`sortBalancerMembersForColdStart` 只做成员顺序（健康有延迟在前、失败在后），
MUST NOT 判断种子新鲜度——`ProxyEntity` 无探针时间戳字段，Kotlin 侧无法判定
缓存延迟是否过期；种子过期判定（超过 `3*interval`）与 idle 后重选在 Go 侧
`URLTestGroup` 强制执行。

- **GIVEN** 健康成员缓存延迟极大（可能是过期缓存）
- **WHEN** 生成 leastPing 组配置
- **THEN** 仍按延迟排在未测成员之前，过期判定不在此处做

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 策略组混搭来源与组隔离（B7a 已落地）

策略组 MUST 支持“分组（含名称正则过滤）+ 散装节点”混搭来源（`BalancerBean.TYPE_MIXED = 2`），
成员解析 MUST 由 `BalancerMemberResolver` 统一完成：分组在前、散装在后，按 id 去重；
名称正则只过滤分组来源，散装节点是显式点选、不受正则影响；`maxMembers`（0 = 不限）
按解析顺序截断。设置页 MUST 实时展示匹配计数，正则语法错误 MUST 显示并阻止保存；
配置生成遇到脏正则时 MUST 按未设置处理，不得丢成员、不得起不来。
每个策略组 MUST 独立隔离出站与前置/落地代理链：带组上下文的成员链按
`(balancerId, memberId)` 缓存并隔离构建（出口跳点不进 `globalOutbounds` 共享，
中间跳点与出口 tag 加组后缀防重复），多策略组共享成员时 MUST NOT 串线。
副作用：隔离链不再进入 `tagMap.values`，顶层 selector/urltest 的成员列表不再含
策略组内部成员链（策略组本体的 tag 仍在其中）。

- **GIVEN** 策略组选了一个分组（正则 `^HK`）并叠加 2 个散装节点，其中 1 个已在分组内
- **WHEN** 保存并生成配置
- **THEN** 成员去重后无重复出站，匹配数量显示正确，且该组的出站与代理链与其他组隔离
- **GIVEN** 正则写错
- **WHEN** 编辑页实时校验
- **THEN** 显示正则语法错误提示并阻止保存

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: BalancerBean 记录 v5→v6（B7a 已落地）

`BalancerBean` 记录版本 v5→v6（`writeInt(6)`），v6 字段形状由 wanBox 本批定，
不照抄上游 autoselect 形状的 v6：新增 `TYPE_MIXED = 2` 与 `maxMembers`（int，
0 = 不限）。`v1–v5` 历史数据 MUST 能读、能升（缺失字段取安全默认值，
`maxMembers = 0`）；`initializeDefaultValues` MUST NOT 覆盖显式 `0`（不限），
只归一化非法负值。降级导出 MUST 可用：`serializeV5` / `downgradeToV5Bytes`
按 v5 布局写出 v5 子集（类型强制降为 `TYPE_LIST`，proxies 为调用方先解析出的
唯一成员 id 列表，分组来源与上限已 baked 进列表，不得静默丢字段）。
8.15 同类问题核查结论：既有字段无同类覆盖缺陷——`tolerance` 的 0 早已是 `< 0`
守卫（0 = 严格切换，不会被覆盖）；`interval` 的 0 无“不限”语义（探测周期，
`<= 0 → 300` 不是覆盖）；`frontProxy`/`landingProxy` 的 `0 → -1` 只是 id
归一化（0 不是合法 profile id）。

- **GIVEN** 旧版 v5 策略组数据（interval 300、tolerance 300）
- **WHEN** 升级到新版并打开策略组设置
- **THEN** 旧字段原值保留，新字段取安全默认值，可正常保存与建连

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: BalancerBean 记录 v6→v7（B7c 已落地）

`BalancerBean` 记录版本 v6→v7（`writeInt(7)`），新增 `tierRules`（String，
URL 安全 Base64 编码后的层级规则表）、`priorityOrder`（`List<Long>`，
节点优先级顺序）、`priorityExcluded`（`List<Long>`，仅优先级模式排除的
分组来源成员 id）。`v1–v6` 历史数据 MUST 能读、能升（v7 字段缺失时取 `""` /
空列表）；`initializeDefaultValues` 只做 v7 字段空值守卫、不覆盖已有值。
降级导出 MUST 可用：`serializeV6` / `downgradeToV6Bytes` 按 v6 布局写出 v6 子集
（v7 字段丢弃），`serializeV5` / `downgradeToV5Bytes` 链式复用、仍可用。
层级规则与优先级顺序的草稿态另存 DataStore（`balancerTierRules` /
`balancerPriorityOrder` / `balancerPriorityExcluded`，profileCacheStore），
保存时才写入 `BalancerBean`。

- **GIVEN** 旧版 v6 策略组数据（含 `maxMembers`、无层级字段）
- **WHEN** 升级到新版并打开策略组设置
- **THEN** 旧字段原值保留，层级规则为空、优先级顺序为空，可正常保存与建连

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 策略组严格手动锁定（B7b 已落地）

策略组成员被手动锁定后，断连或超时 MUST NOT 自动切换，直到用户点击「恢复自动」解锁。
锁定期间的失败 MUST 计入统计，MUST NOT 触发自动切换。锁定成员不可用时，主页横幅 MUST 红字高亮提示。
锁是运行时选择，存在 `DataStore.balancerManualLock`（configurationStore，
`"<balancerId>:<memberId>;..."` 编码，空 = 全部自动），不进 `BalancerBean` 记录、
不占记录版本号（B7c 的层级字段仍按计划升 v7）。
锁定在配置生成层生效：锁定时该策略组生成 `selector` 出站（`default` = 锁定成员），
核心不再自动切换；锁定与层级回切 MUST NOT 默认断开现有连接
（selector 用 sing-box 默认 `interrupt_exist_connections = false`，
断开只在用户显式开启对应开关时发生，本批不提供该开关）。
锁定成员不在成员列表里时回落自动生成，锁仍保留、横幅红字提示。
导出/测试配置不带运行时锁定。

- **GIVEN** 用户手动锁定节点 A 且 A 已断连
- **WHEN** 经过一次检查间隔
- **THEN** 仍锁定在 A，不自动切到 B，横幅红字提示“锁定节点不可用”
- **GIVEN** 用户点击「恢复自动」
- **WHEN** 下一次检查完成
- **THEN** 恢复自动选择，可切到健康节点

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 策略统计页与主页横幅（B7b 已落地）

开启策略组连接时，主页 MUST 在当前节点卡片下方显示策略组状态横幅
（当前策略模式、正在使用/锁定节点及实时延迟）。点击横幅 MUST 进入策略统计页，
展示各成员状态/报错信息，并提供一键“立即全部检查”。
统计页数据 MUST 来自 Root 快照/Clash API 等既有通道，不得为统计另开核心直连。
统计为进程内内存态（`BalancerMemberStats`）：失败为自上次成功以来的连续失败次数，
成功后清零并记延迟、清掉旧报错；锁定期间的失败同样计入。
锁定的唯一入口在统计页：自动组成员行点按即锁定（解析不到 id 的成员行不可点，
不得猜 id），锁定/解锁后重建配置生效，未连接时只落盘、下次连接时生效。
锁只对 `BalancerBean` 策略组实体生效，顶层 proxy selector 与编辑器组不提供锁定。

- **GIVEN** 策略组已连接
- **WHEN** 用户查看主页
- **THEN** 横幅显示当前模式、承载节点与实时延迟
- **GIVEN** 用户在统计页点击“立即全部检查”
- **WHEN** 检查完成
- **THEN** 各成员状态与报错信息刷新展示

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 「按优先级顺序」多层级调度（B7c 已落地）

「按优先级顺序」模式（`BalancerBean.STRATEGY_PRIORITY_ORDER = "priorityOrder"`，
新增策略值、与既有模式并存；「最快最稳」=`STRATEGY_LEAST_PING`）MUST 支持按
服务器分组与节点名称正则定义层级（层级 1、层级 2…），未匹配节点自动归入保底层级；
层级支持上移/下移与删除。层内 MUST 按实测延迟与切换容差择优；高顺位层全数不可用时
MUST 立即回退下一层；恢复后 MUST 按双重门槛（连续健康检查 ≥3 次 + 无失败 ≥60 秒）
受控回切。启用流量分配时 MUST 只在当前最低健康层级内分散流量。
「最快最稳」模式 MUST 隐藏「添加层级」、层级行与节点优先级列表。
层级规则存 `BalancerBean.tierRules`（单条正则 URL 安全 Base64 无填充编码，
`include:exclude`、`;` 分隔，上限 10 条，脏条目解码跳过）。
Kotlin 侧 `BalancerTierScheduler` 负责定层级/层内择优/回切双重门/流量层内分散；
Go 侧 `libcore/protocol/urltest/outbound.go` 强制执行——Kotlin 生成嵌套 urltest：
每层一个内层 urltest（同间隔/容差/空闲），外层 urltest 对层级 tag
（`"<balancerTag> [T<n>]"` / `"<balancerTag> [FB]"`）择优；单层不嵌套。
层级解析仅当全部成员 tag 都带层级后缀且 ≥2 个不同层级时激活，避免普通节点名误触发。
回切 MUST NOT 默认断开现有连接（`interrupt_exist_connections = false`），
探测间隔仍钳制 ≥10 秒，不为层级检查新增更密的探测。

向下容灾与向上回切 MUST 分开裁决：高顺位层节点历史仍新鲜但实际建连失败时，
数据面 MUST 能及时降级到健康低层并把该层记为当前生效层；低层工作正常时
MUST NOT 因为高层更快或历史新鲜而提前回切，只有轮次门连续 ≥3 轮新成功
且 ≥60 秒无失败才允许回切。恢复门 MUST 只统计本轮真实新观测，缓存跳过、
预算取消、旧缓存以及其他层的结果 MUST NOT 替高层累计健康轮次（详见
`libcore-integration/spec.md` 的「向下容灾与向上回切不变量」）。

- **GIVEN** 层级 1 全部节点不可用，层级 2 有健康节点
- **WHEN** 下一次检查完成
- **THEN** 流量回退到层级 2 的最优节点
- **GIVEN** 层级 1 恢复可用但未达到双重回切门槛
- **WHEN** 检查完成
- **THEN** 不回切，仍留在层级 2
- **GIVEN** 层级 1 节点历史新鲜但该节点实际建连失败，层级 2 有健康节点
- **WHEN** 发起一次连接并完成下一轮检查
- **THEN** 本次连接降级到层级 2，下一轮仍留在层级 2，不因层级 1 历史新鲜而回切
- **GIVEN** 连续三轮都因缓存新鲜跳过探测，或只有层级 2 得到新探测结果
- **WHEN** 轮次收尾
- **THEN** 层级 1 不累计健康轮次、不提前回切
- **GIVEN** 用户切到「最快最稳」模式
- **WHEN** 打开策略组设置页
- **THEN** 不显示添加层级、层级行与节点优先级列表

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。

### Requirement: 节点优先级二级页与内嵌节点顺序列表（B7c 已落地）

策略组设置页 MUST 提供内嵌式节点顺序列表（位于「指定节点」下方、「高级」上方），
支持紧凑编号（`1, 2, 3...`）、拖拽调序、快速移除及点击替换节点；不得在页面底部
另挂外挂列表。「节点优先级」MUST 收纳为独立二级页面（新 `BalancerPriorityActivity`，
草稿态编辑、返回时回写并自动生效），摘要实时显示节点数量与前 3 个节点预览；
二级页 MUST 支持顺位标号、拖动调序、删除、点击替换、添加/多选节点与「按当前延迟排序」
（配置有层级时按层级分组内延迟排优）；返回 MUST 自动生效。
移除语义：散装成员同时从 `proxies` 移除（真正离组），分组来源成员记入
`BalancerBean.priorityExcluded`（仅优先级模式过滤成员，切回其他策略即恢复）；
新成员的层级按名称正则重算、不继承被替换成员的层级。

- **GIVEN** 策略组有 5 个指定节点
- **WHEN** 用户在内嵌列表拖动第 5 个节点到第 1 位
- **THEN** 顺序即时生效，编号变为 `1..5` 且与实际选路顺序一致
- **GIVEN** 用户打开「节点优先级」二级页
- **WHEN** 点击「按当前延迟排序」
- **THEN** 节点按实测延迟升序排列（有层级时层内排序），返回后生效

> 验证：CI 通过（port 分支 Verify Android Build #190 全绿，main #197 第 2 次尝试全绿）；Magisk / KernelSU / APatch 真机矩阵未执行，待真机验证。
