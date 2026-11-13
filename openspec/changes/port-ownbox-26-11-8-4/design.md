## Context

OwnBox v26.11.8.4-preview 的参考差异集中在一条提交 `aa93dfb2563e3b2b4dff35d2ca40c86942842ae8`。wanBox 已重建配置生成、Root 透明代理、数据快照和用户备份路径，所有七项行为均须按 wanBox 自身边界适配，而非复制上游文件。

本 change 的基线为 `port/ownbox-26.11.8.4` 分支起点 `ffdfc8e96bbf851558bb81d0b669e8055b79765a`。`nb4a.properties` 当前 `VERSION_NAME=3.0.11`、`PRE_VERSION_NAME=3.0.13-preview.4`、`SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd`；不改变这些值。

wanBox 的 RootModuleClient 将 Android ConfigBuilder 生成的 sing-box 配置快照交给独立 Root 模块。配置规则/QUIC 修复集中在 App 生成器；智能挂起则因 Root 模块拥有 TUN、核心进程、iptables/路由和物理网络观察而必须进入 Root 模块，Android 只提供规则、状态与操作入口。

## Goals / Non-Goals

**Goals:**

- 修复实际 Root 配置中的 sniff、reject fallback、组播、DNS 与 Play 下载路由问题。
- 修复空分组测速 URL 动态继承全局值，验证用户备份的新旧格式。
- 新增独立 UDP 443 QUIC 开关、缺失出站定位告警以及 Root 网络智能挂起/恢复。
- 每个行为有对应纯逻辑/配置输出/状态机测试；按六个最小批次通过 CI gate。
- 不等待真机回传，但清楚记录未真机验证的风险。

**Non-Goals:**

- 不重写 Root 模块整体网络栈，不创建 Android VPN/TUN 实现，也不复制 OwnBox 的 TUN package whitelist。
- 不升级 sing-box，不改版本号、版本编码或正式发布流程；不创建 PR/Release/tag。
- 不在本地安装 Go 或编译 Android/Go；GitHub Actions 负责编译与测试。

## Decisions

### 1. 批次 1：路由优先级与默认规则

`RouteRulePriority.order` 继续保留显式规则的相对次序；拒绝型 geographic fallback 先于其余地理 fallback，防止后添加的广告/reject rule 被国内 direct geosite 先匹配。仅改变生成顺序，不改写 Room `userOrder`。启用嗅探时，`action=sniff` 必须在 Root mixed 探测域名和业务域名规则之前。移除 `hijack-dns` 后重复的 port/protocol 53 代理 fallback。组播 reject 保留当前目的地址 `ip_cidr` 范围，但删除误加的 `source_ip_cidr`。

默认广告规则需使用 `domains="geosite-category-ads-all"`。虽然 OwnBox 将同一标签存入 `ruleset` 字段，wanBox 的 `processRulesetUrl` 只接受 `rsip:`/`rssite:` 远程 URL，故内置 geosite 必须走 `domains` 的 `makeSingBoxRule`/本地 rule-set 转换链。升级时仅迁移 domains 含 `googleapis.cn` 且 packages 含至少一个旧注入包的 Play Store route；从该规则移除 `com.android.providers.downloads` 与 `com.android.providers.downloads.ui` 两个历史包名，同时保留 enabled 状态和其他用户选择，不触及不满足该特征的规则。当前 ConfigBuilder 已没有构建时自动注入，不再新增此类代码。

### 2. 批次 2：分组测速 URL 继承

每组未设置 URL 保持空 raw override，编辑页不得以当前全局值初始化并回写。运行时解析顺序为显式组 URL > 全局 `connectionTestURL` > 安全默认。URLTest、LoadBalance（适用时）、Root snapshot 与 resolveLink 使用一致的 resolver。全局地址更新后，空 override 的组自动使用新值。

### 3. 批次 3：用户备份兼容

先以 fixture 核验 `PortableBackup v2` 和已有测试：新导出 `profiles`、可读 route JSON、核心路由/DNS 默认值；导入旧 `proxies`、Base64 Parcel route rules、旧 wanBox 文件。已完整的行为不重写；如有缺口，只扩展 App `PortableBackup`/`BackupRestore`，不把 RootModuleSnapshot/data archive 混入。

### 4. 批次 4：独立 QUIC 开关

新增持久化 `blockQuic` 开关，默认 `true`，放在“核心设置”。配置打开时注入一条精确的 `network=udp, port=443, action=reject` 规则；关闭时不注入该自动规则，UDP 443/HTTP/3/QUIC 可正常进入后续用户路由。TCP 443、UDP 非 443 不受此开关影响。显式用户自定义 reject 规则仍按用户意图执行，不被全局开关静默删除。设置文案进入默认语言与简体中文资源；RootModuleClient 复用配置快照，无须另写 Go 过滤器。

### 5. 批次 5：无效出站显式呈现

当路由规则的正向 outbound ID 找不到节点或策略组时，数据层/配置生成层产生稳定、脱敏的 issue（规则 ID/name、缺失类型/ID），而不是仅 toast 或静默跳过。规则列表通过状态绑定标红相应规则。服务启动前读取本次 ConfigBuildResult 的 issues，向用户显示可定位的 warning modal；用户可以返回规则列表修复或明确继续，确保继续时 invalid rule 被显式列为未应用。规则名缺失时退回安全的 `Rule <id>`，不得输出凭据、地址或原始 core stderr。Root/Core 仍是最终 schema 校验权威，不在 UI 另建完整 outbound validator。

### 6. 批次 6：Root 网络智能挂起/恢复

设置页最多接受 5 条网络匹配规则：本地接口 IP、CIDR 和 `gateway:` 网关地址；读取 `ConnectivityManager`/Root 物理网络信息，不请求 location/SSID 权限。Root 模块用有界状态机保存用户原始运行意图与 network fingerprint：规则命中时仅挂起核心/清理本次代理拥有的 iptables 与路由；网络离开匹配条件时一次性恢复；相同 fingerprint 下用户手动恢复会抑制重复挂起，换网后清除抑制；用户点“停止”则按手动停止语义不自动重启。

状态/事件经受限 RootModulePort、RootModuleClient 和通知 action 往返。挂起状态通知保留“恢复”“停止”按钮。清理/恢复操作必须幂等、只删除本模块拥有的规则，不可残留阻断整机网络的 route/iptables 条目；失败时状态报告不能伪称已恢复。Root package 应有纯 Go matcher/state-machine 测试，Android/契约测试验证配置、通知和 command 行为。

### 7. 测试 seam 与批次门控

- 纯逻辑：`RouteRulePriority`、group URL resolver、backup codecs、network-rule parser/matcher/state transitions。
- 最高可行集成 seam：实际 `ConfigBuilder` JSON、RootModuleSnapshot、`ProfileManager` Room 数据、RootModulePort/wanboxctl command/state。
- 每个 bugfix 先写可复现测试再修；不得本地执行 Android/Go 编译。每批同步对应主规范、自审、提交到 feature branch、执行 `verify-android.yml` 非发布 workflow_dispatch，并等该批 CI 完成后才开启下一批。
- 真实设备用于观察 Root route/iptables、通知权限和网络切换行为；用户已指示不等回传，因此最终报告须标明未验证项。

## Risks / Trade-offs

- Reject fallback 优先级改变冲突规则结果；这是用户要求的行为，显式规则与 Room 顺序仍保留。
- `geosite-category-ads-all` 依赖本地 geosite.db；生成 JSON 与 Root snapshot 均需覆盖。
- 清理历史 Play 下载包名可能移除用户手动选择的相同 package；迁移仅限识别出的 Play rule，并保留启停及其他字段。
- 分组 URL 的动态继承改变缺省行为，但显式组级地址保持不变。
- QUIC 开关关闭只撤除自动 UDP/443 rule；明确自定义 reject 仍生效，UI/测试需区分两者。
- 缺失出站告警不能暴露 endpoint credentials；modal 允许继续必须清楚列出未应用规则。
- Root 自动挂起/恢复涉及 iptables、路由和核心生命周期，错误清理可能造成断网；必须通过 Go 状态机/command 测试及 CI 静态审查，并在报告中注明未真机验证。
- `Verify Android Build` 通过 workflow_dispatch 在 feature branch 运行，不创建 Release/tag；它会构建并上传未发布的 release-variant workflow artifacts。不得调用独立 release workflow。

## Migration Plan

1. 先完成所有七项适用性评估与本 change，然后只实现批次 1。
2. 对每批执行测试先行、实现、对应主规范、自审和语义提交；仅推送 `port/ownbox-26.11.8.4` 并运行非发布 `verify-android.yml` workflow_dispatch。
3. 收到当前批 CI 绿灯前不得开始下一批；失败时仅修当前批。真机结果不作为推进条件。
4. 若 workflow_dispatch 不可用而只能由 PR 触发，完成当前批后停下，报告需要用户决定是否开 PR。
5. 六批结束前不创建 PR、不运行 release workflow、不修改正式版或任何 tag。
