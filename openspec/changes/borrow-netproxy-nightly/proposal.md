# 借鉴移植 NetProxy nightly（Magisk）的功能与修复

## 为什么

NetProxy（Fanju6/NetProxy-Magisk）与 wanBox 同为 Root-only Android sing-box 透明代理工具，
核心同为 `reF1nd/sing-box v1.15.0-alpha.13-reF1nd`，但在停核可编辑、脱敏诊断、按网络策略、
共享网络接管、分应用按用户配置、路由规则指定策略组、热更新契约测试、eBPF 入站等方面
有 wanBox 尚未具备或尚未做全的设计。wanBox 的 Core/Root 架构、配置生成和持久化模型已大幅
分叉，必须逐项评估并适配，不能直接 cherry-pick 或复制代码。

## 源 B 内容核对结果

核对所用本地副本：`~/workspace/wanbox-port/netproxy`，HEAD `6f2362d98bb092cefba529bd1e0b1b08aa12e84b`
（2026-11-15 17:55:29 +0800，`feat: 支持路由规则指定策略组`），tag 为 `nightly`。

| 工作词说法 | 实际仓库内容 | 结论 |
|---|---|---|
| NetProxy 是 GPL-3.0 | 根目录 `LICENSE` 为 GNU GENERAL PUBLIC LICENSE Version 3（2026-11-15）；`src/native/netproxy` 等处亦有 GPL-3.0-or-later 声明 | 相符。只借设计与行为，代码落到 wanBox 既有结构里重写；确实需要复制片段的见下文“许可证登记”。 |
| sing-box 透明代理模块 + Go 原生组件 + WebUI + 管理器 App 四件套 | `src/module/`（Magisk/KernelSU/APatch 模块）、`src/native/netproxy/`（Go 组件，唯一可执行文件 `netproxyctl`）、`src/webui/`（TypeScript 终端式 WebUI，构建产物写入 `src/module/webroot/netproxy/`）、`src/android/`（Compose 管理器 App） | 相符。`AGENTS.md` 亦以此四件套划定项目边界。 |
| 核心同为 reF1nd 分支 | `src/native/netproxy/go.mod`：`github.com/sagernet/sing-box v1.15.0-alpha.13-reF1nd`，并 `replace` 到 `github.com/reF1nd/sing-box v1.15.0-alpha.13-reF1nd` | 相符，与 wanBox `nb4a.properties` 的 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 一致。 |
| 版本 v8.7.0（2026-11-15） | `src/module/module.prop`：`version=v8.7.0`，`versionCode=5`（AGENTS.md 说明 `versionCode` 由 CI 按提交数写入，人手改写会被覆盖）；`docs/changelog.md` 顶部为“版本 8.7.0（2026-11-12）”，不含 nightly；HEAD 提交日期为 2026-11-15 | 基本相符，日期需拆开说：8.7.0 发布于 2026-11-12，2026-11-15 是本次 nightly HEAD 提交的日期，不是 8.7.0 的发布日期。 |
| build 1103 | 仓库内未找到 `1103`：`module.prop` 的 `versionCode=5` 是占位值；管理器 `src/android/app/build.gradle.kts` 用 `gitCommitCount()`（仓库提交数）作 `versionCode`，`versionName` 取 Gradle 属性 `netproxyManagerVersion`。本次克隆为单提交 squash 历史（`git rev-list --count HEAD` = 1），无法从本地复算 CI 时的提交数 | **无法核实**。1103 很可能是 CI 打包时当时的提交数，但本副本中无任何来源可证实或证伪；不得在规格或文档中把 1103 写成已核实值。 |
| nightly HEAD 提交正是 N6 功能 | HEAD 提交信息即“支持路由规则指定策略组”，body 说明：指定策略组写入 sing-box 原生路由分区，不追加规则集文件或扩展 HTTP 契约；无明确插入位置时交给 JSON 编辑器；出站分区事务内保护路由仍引用的策略组。但本次克隆只有一次提交（无父提交），**无法用 diff 核对该提交到底改了哪些文件**；实现以下列树内证据为准：`src/native/netproxy/internal/module/route_groups.go`（`validateRemovedRouteGroups`：删除/改名被路由引用的策略组时阻断）、`docs/config/routing.md` 与 `AGENTS.md`“路由规则表单”段（单条件规则保存到原生 `route.rules`，位于默认全局模式、DNS 接管及本地快捷规则之后、内置站点分流之前；无法确认插入位置时交给 JSON 编辑器） | 相符（以树内证据为准，diff 不可用已记录）。 |

另有两个上游内部口径不一致，已记录在 design.md，不在本轮解决：

- eBPF 本机数据平面默认值：`docs/config/ebpf.md` 与 `docs/changelog.md`（8.6.2）称新安装本机与共享网络默认均为
  `socket_assign`；而 `AGENTS.md`“架构参考”称“本机默认使用 cgroup socket hook”。两者都承认
  `socket_assign`/`cgroup`（本机）与 `socket_assign`/`packet_rewrite`（共享）为合法选项、旧 `tc` 已移除。
  wanBox 现状是本机 `cgroup`（`RootEbpfInbound.kt`），本轮不改默认值；N8 可行性评估须以真机预检为准，
  不得以任一文档口径代替。
- `docs/changelog.md` 明确“不包含持续更新的 `nightly Pre-release`”，故 8.7.0 changelog 不能作为
  nightly HEAD 功能的发布证据；nightly 功能以 HEAD 提交信息与树内代码/文档为准。

## wanBox 基线

- 工作区 `~/workspace/wanbox-port/wt-featB`，分支 `feat/borrow-netproxy`，基线 main `4627a6f`。
- `nb4a.properties`：正式版 `VERSION_NAME=3.0.14`（`VERSION_CODE=2026`），预览
  `PRE_VERSION_NAME=3.0.17-preview.1`（`PRE_VERSION_CODE=2031`），
  `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd`。本 change 不改版本号、不改内核版本。

## 逐项适用性评估（N1–N8）

| 项目 | wanBox 当前状态与代码位置 | 评估结论 |
|---|---|---|
| N1 停核可编辑：服务停止时仍可浏览/编辑节点与订阅、并可测速 | 上游机制（`AGENTS.md`“架构参考”）：模块 HTTP API 与 CLI 不依赖核心进程；服务停止时测速用“不含透明代理入站、eBPF/TUN 和 Clash API 的短生命周期 sing-box 会话”，用目标 Provider 快照与随机 loopback Service API 完成，不得修改正式服务状态/选择状态/Worker。wanBox 现状：节点/订阅/路由/策略组编辑本身是 App Room/数据库操作，停止态已有“编辑不启动核心、仅更新配置”的 Root 编辑器链（`RootModuleClient.kt` applyBackend 注释“passive edits never start a stopped module”；主规范“运行态 MUST 由模块重载，停止态 MUST 不被编辑启动”）；URL/速度测速走独立测试 box 实例（主规范“测速与正式连接保持语义一致且实例隔离”），不依赖正式核心运行。但**未逐面核实**停止态下订阅更新、策略组编辑提交、测速入口是否全程可用，也无“停止态可编辑”的显式回归测试。 | **部分采，纳入 B5**。不新造架构：把“停止态可编辑”定为显式契约，逐面核查节点浏览/编辑、订阅更新、路由/策略组编辑、URL 测速、速度测速五面，缺口处补测试与修复；凡发现停止态被运行状态锁住的编辑面，一律视为缺陷修，不另立新开关。 |
| N2 脱敏诊断包：一键导出脱敏诊断包 | 上游实现：`src/native/netproxy/internal/module/logs.go`（`ExportLogs`/`ExportDiagnostics`，tar.gz，目标须为普通文件，先写临时文件再原子替换）+ `src/native/netproxy/internal/logfile/redact.go`（统一脱敏规则）+ `cmd/netproxyctl/logs.go`（`logs export`，默认 `/sdcard/Download/netproxy-diagnostics.tar.gz`）。包内含 service/core 日志（含轮转备份，每文件取尾部，上限 4 MiB）、`config/module.json`、`config/inbound.json`、sing-box `config.json`、本地规则（脱敏）、远程规则（**不脱敏**，见 design.md 取舍）、Catalog 数据（脱敏，跳过 staging）、`state/service.json`，及版本/时间 README。wanBox 现状：设置页已有模块诊断区（呈现模块已上报的退出/事件信息，旧模块不上报时不显示），但**没有一键导出脱敏诊断包**；日志与配置散落在 App/Root 两侧，无统一脱敏规则。 | **已采，纳入 B5**。借包结构与“损坏 JSON 省略原文”的 Fail-Closed 规则；脱敏规则按 wanBox 自家数据模型在 design.md 里重新定死（订阅地址、节点凭据、IP 打码范围、SSID、MAC、服务器地址），不照搬上游正则；远程规则集不脱敏的上游做法**不采纳**——wanBox 诊断包内的远程规则只导出元数据（名称/来源/哈希/更新时间），不导出规则原文。 |
| N3 按网络选策略：按 Wi-Fi 名称与实际出口自动切换网络策略 | 上游实现：`src/native/netproxy/internal/config/config.go`（`module.json` 的 `wifi`：`enabled`、`mode=blacklist/whitelist`、`blacklist`/`whitelist` SSID 数组、`proxy_on_non_wifi`；SSID 精确保留空格/大小写/逗号，`mode=off` 不存在）；`internal/module/network.go`（`EvaluateNetwork`/`networkPolicy`：命中名单或非 Wi-Fi 且 `proxy_on_non_wifi=false` 时把运行时模式切为 `Direct`，否则保持默认模式；只改运行时，不覆盖默认模式）；`internal/worker/network_route_unix.go` + `network_wifi_unix.go`（按 Android policy routing 读实际默认出口，再读该出口的 station SSID；未知网络不当作非 Wi-Fi；开机未知时校准默认模式）。上游只做“模式切换”（Rule/Proxy/Direct），**不停核**。wanBox 现状：F1 智能启停（主规范 android-application + libcore-integration）已实现另一半：最多 5 条本地 IPv4/IPv6、CIDR 与 `gateway:` 规则，命中→优雅停核挂起、离开→自动恢复、同 fingerprint 手动恢复抑制、无定位权限。两者触发源重叠（都是“当前物理网络”），动作不同（停核 vs 切 Direct 模式），若各立开关会互相打架。 | **已采，纳入 B6，与 F1 合并为一套「按网络」规则**。单规则表、单指纹源（SSID + 实际出口接口/网关/CIDR）、单优先级裁决；每条规则的动作三选一：挂起（停核）、直连模式、保持代理；默认动作与冲突裁决在 design.md 定死，B6 不得交付两个互相覆盖的开关。F1 已实现的挂起状态机、通知、抑制与 Fail-Closed 屏障全部保留，不得为合并且重写。 |
| N4 共享流量接管：热点与 USB 共享网络的流量一并接管 | 上游实现：eBPF 入站 `local`/`shared` 双路径独立启用（`docs/config/ebpf.md`、`internal/inbound/config.go`）；共享路径 `shared.enabled`、`shared.interface`（至少一个真实下游接口，不能填 loopback）、`data_plane=socket_assign`（推荐，支持以太网/raw-IP/PPP/隧道）或 `packet_rewrite`（兼容，需以太网帧，适用普通热点/LAN 下游）、`default_interface`（默认关）、`dns_mode` 默认 `hijack`；模块不创建热点/DHCP/NAT/IPv6 RA/IP 转发。wanBox 现状：`RootEbpfInbound.kt` 的 `buildRootEbpfInbound` 固定 `shared={enabled:false}`，只有本机路径；`LanSharingActivity.kt` 做的是 LAN 共享（把本机 mixed 入站当代理网关供其他设备填 IP），**不是接管热点/USB 共享客户端流量**；TUN 回退无 shared 映射（与上游一致，上游 TUN 也不建 local/shared 映射）。 | **部分采，纳入 B6**。只接 eBPF 共享路径：共享开关、接口选择、数据平面二选一、默认关闭；TUN 模式下共享接管明确不支持（保持 LAN 网关能力不变），不得为共享另造 TUN 映射。默认关闭，开启前须过 eBPF 能力预检；接口缺失/变为默认上游时的行为按 design.md。 |
| N5 分应用细化：黑/白名单按 Android 用户分别配置、支持应用分身 | 上游实现：`AGENTS.md` 防回退条款与架构参考——分应用策略持久化严格的 `<user-id>:<package>` 引用，Android 每个用户独立展示；Go 通过 Android package service 按用户查询 UID，运行时生成 `include_uid`/`exclude_uid`；白名单解析后与原生 include UID/range 均为空时，仅在运行时设置 `exclude_uid_range=["0:4294967294"]` 排除全部有效 UID（不得生成空 include 后静默变全量接管，也不得注入占位 UID）；仅 eBPF 共享路径时不查询应用 UID。wanBox 现状：`AppManagerActivity.kt` 以 `applicationInfo.uid`（单用户视角的 UID）存取黑/白名单（`DataStore.individual`），`DataStore.kt:154` 的 `userIndex` 只用于配置隔离；`ConfigBuilder.kt` 的 Root eBPF/TUN UID 筛选按单用户 UID 列表生成；**无 `<userId>:<package>` 键、无按用户查询 UID、无分身区分**，工作资料/分身应用的同包名不同用户实例会被合并或漏配。 | **已采，纳入 B6**。数据模型改为 `<userId>:<package>` 键，Go/Root 侧按用户查询 UID 后合并为 include/exclude UID（含空白名单的 exclude-all-range 护栏）；UI 按用户分组展示；旧单 UID 名单须迁移，不得静默丢弃。 |
| N6 路由规则指定策略组 | 上游实现见上文“源 B 内容核对”：单条件规则写原生 `route.rules` 的 `outbound=<策略组 tag>`，位于默认全局模式、DNS 接管及本地快捷规则之后、内置站点分流之前；无法确认插入位置时交给 JSON 编辑器；出站分区事务内 `validateRemovedRouteGroups` 保护被路由引用的策略组不被删除/改名后留失效出口。wanBox 现状：`RuleEntity.outbound: Long` 只能表示节点 ID（`0`=proxy、`-1`=bypass、`-2`=block），**规则无法指定策略组**；主规范明确写着“本批不新增按组 ID 的规则或组直接成员”（group-editor 批次的边界）。策略组运行时 tag 已存在（主组固定 `proxy`，非主组为 trim 后组名），缺失引用预检（列表标红 + 启动前 modal）已存在，可复用。 | **已采，纳入 B7**。新增规则→策略组目标：数据模型、编辑 UI、生成时写组运行时 tag、插入位置、缺失引用预检复用；多条件/logical/invert 等原生字段保留原样走 JSON 编辑器（与上游一致）。 |
| N7 安装器热更新契约测试 | 上游做法：`tests/customize_hot_update_test.sh`（716 行，POSIX sh）：用 awk 只抽取 `customize.sh` 的生产函数（从 `print_title()` 到后台分支前），在 `mktemp` 沙箱里搭 live/staging 两套模块 fixture（module.json、inbound.json、singbox config、本地/远程规则、cache.db、自定义状态、Catalog、日志、telemetry、锁文件），桩掉 `ui_print`/`chown`/`chcon`/`pidof`/`su`，覆盖安装选择（音量键/超时默认）、三种快照模式（保留全数据/仅节点与订阅/全新）、热切换互斥、快照失败/重命名失败、缺失数据、权限、服务失败、管理器安装等；由 `tests/ci_verify.sh` 编排。wanBox 现状：热更新实现不在 `customize.sh`（本仓库 `rootmodule/package/customize.sh` 仅 23 行：校验架构/API、调 `installer-options.sh`、调 `wanboxctl __internal schedule-install` 做后台更新）；真正逻辑在 Go `rootmodule/hot_update.go`、`install.go`、`install_handoff.go`，已有 `hot_update_test.go`（9 个 Test，含 canary 失败回滚、route guard）与 `install_test.go` 等。 | **已采，纳入 B8，只借测试方法，不动热更新实现**。把上游“fixture 双目录 + 桩外部命令 + 逐模式断言快照内容 + 失败恢复”的测法，落到 wanBox 的 Go 热更新测试（补快照模式/锁顺序/失败恢复缺口）与一层薄 shell 测试（`customize.sh`/`installer-options.sh` 的委托与失败语义）；除非测试暴露缺陷，暴露了单独上报，不擅自改实现。 |
| N8 eBPF 入站：默认 eBPF、可切 TUN+auto_redirect | 上游实现：eBPF 与 TUN 都是 sing-box 入站实现，每次只运行一个受管入站；本机 `socket_assign`（推荐，需网络命名空间、veth、透明 socket、策略路由）或 `cgroup`（兼容，需 cgroup v2 与 socket hook）；共享 `socket_assign` 或 `packet_rewrite`；`ebpf status` 预检（`configured`/`all`，`--raw` 才给原始 JSON；预检通过≠实际接管成功）；`udp_timeout` 默认 `5m`、最小 `5s`；TUN 固定 `auto_route=true`、`auto_redirect=true`，地址 `172.19.0.1/30` 与 `fdfe:dcba:9876::1/126`。wanBox 现状：**eBPF 入站已经是 Root 主路径**——`RootEbpfInbound.kt` 生成 `type=ebpf` 入站（本机 `data_plane=cgroup`、`dns_mode=respect_policy`、`shared.enabled=false`），`RootModuleClient.kt` 先试 EBPF、特定错误回退 TUN；`libcore` 以 `with_ebpf` 编译（`libcore/build.sh` 注释：不可裁，Root 分应用代理依赖它，实测 0.98 MiB/端）。所以 N8 不是“从无到有”，而是“已有 cgroup 本机 eBPF，要不要补 socket_assign 数据平面选择、共享路径（已在 N4）、`ebpf status` 式预检诊断、bypass_rule_set 等”。 | **本轮只做可行性评估，不进实现批次**。评估结论见下节；B5–B8 均不含 N8 实现任务。 |

## N8 可行性评估结论

- **内核版本要求**：无统一最低内核版本号可写。`socket_assign` 需要网络命名空间、veth、透明 socket 与策略路由；
  `cgroup` 需要 cgroup v2 与 socket hook；厂商内核可能关闭或回移单项能力（上游 `ebpf.md` 原话），须以
  `ebpf status` 式实际预检与启动结果为准，不得以 Android 版本或内核版本号代替能力判断。wanBox 现有
  `cgroup` 路径已在真机跑通（Root 主路径），是已验证的基线。
- **与现有 TUN 接管的共存/回退**：共存模型已存在且不得改变——每次只运行一个受管入站；EBPF 失败时按
  `RootModuleClient.kt` 现有白名单错误（`core_config_invalid`、`new_core_failed_rolled_back`、
  `core_start_failed`、`startup_timeout`）回退 TUN，其他错误保持可见；切换后端先停旧实例再启动新实例，
  失败不自动改用另一后端（与上游一致）。`socket_assign` 若引入，MUST 复用同一回退表，不得另立回退语义；
  `socket_assign` 缺能力时须明确报错，不得静默切 `cgroup`（上游亦如此）。
- **体积预算 26 MiB 的影响**：eBPF 入站已在 `libcore` 内（`with_ebpf` 不可裁，约 0.98 MiB/端），
  `socket_assign` 是同一 sing-box 入站的选项值，不是新二进制/新依赖，**预计对 APK+模块 zip 合计
  26 MiB 预算无可度量的新增占用**；但须在实现批次由 `buildScript/size-verify.sh` 实测确认，
  本轮无实测数据，不得写成已验证。真正的体积风险在 N4 的共享路径文案与预检诊断 UI，不在内核。
- **结论**：技术上可行，且是增量演进而非架构重做；但 `socket_assign` 相对 `cgroup` 的收益（跟随默认出口、
  不依赖 cgroup v2）在 wanBox 目标机型上未经真机对比，共享路径已单独立项为 N4，预检诊断可并入 N2。
  **建议下一轮再立项**，以前置条件是：真机跑通 `ebpf status` 式预检、N4 共享路径先验证、size-verify 实测无回归。
  本轮不写 N8 的 MUST 需求，不排实现任务。

## 本次变更内容与批次

1. **B5：N2 脱敏诊断包 + N1 停核可编辑**（先做：N2 是真机排障的基础设施，N1 是其使用前提——停核后仍能操作与测速）。
2. **B6：N3 与 F1 合并为一套「按网络」规则 + N4 共享流量接管 + N5 分应用按用户细化**（三项都动 Root 入站/网络策略面，合一批次内互相校验，避免分批互相覆盖）。
3. **B7：N6 路由规则指定策略组**。
4. **B8：N7 热更新契约测试补强**（只补测试，不动实现；放最后，避免测试改动干扰前面批次的热更新行为基线）。

每批必须先完成该批测试、实现和主规范同步，再自审、提交；CI 结果未返回并通过前，不得开始下一批。
按 openspec/config.yaml，本地只做不依赖 Go/Android SDK 的静态校验，编译与真机验证交给 GitHub Actions 与用户真机；
没有实际验证结果时，不得勾选 CI/真机验证任务。

## 影响与边界

- 受影响主规范：`android-application`（N1/N2/N3/N5/N6 的 UI、数据模型、配置生成）、
  `libcore-integration`（N2 的 Root 诊断导出、N3/N4/N5 的 Root 网络与入站行为、N7 的 Root 热更新契约测试）。
  不涉及 `theme-system`、版本编码或构建链。
- 不改变 `VERSION_NAME=3.0.14`、`PRE_VERSION_NAME=3.0.17-preview.1`、`PRE_VERSION_CODE=2031`、
  `VERSION_CODE=2026`、`SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 或正式版行为。
- 中间批次只在 `feat/borrow-netproxy` 分支提交，不推 main、不创建 PR/Release/tag、不推远程。
- 新增用户可见文案至少更新默认语言与简体中文；其他已存在语言继续通过默认资源回退并保持 UI 一致。
- wanBox 自家约束与上游冲突时以自家为准，取舍见 design.md（URLTest 间隔 ≥10 秒、空闲超时 ≥30 分钟、
  切换不中断连接、Fail-Closed 红线、default 保真、版本 CAS 与恢复校验链）。

## 许可证登记

NetProxy 为 GPL-3.0。本 change **只借设计与行为**，不复制上游代码到 wanBox：

| 借鉴内容 | 落法 | 是否复制代码片段 |
|---|---|---|
| N1 短生命周期测速会话（不含透明入站/eBPF/TUN/Clash API） | 按行为描述在 wanBox 测试 box 实例上实现 | 否 |
| N2 诊断包结构（tar.gz、文件清单、临时文件+原子替换、损坏 JSON 省略原文）与脱敏规则思想 | 按 design.md 的脱敏规则表在 wanBox Go/Kotlin 侧重写 | 否；脱敏键表与正则按 wanBox 字段重写，不复制 `redact.go` 原文 |
| N3 Wi-Fi 策略模型（blacklist/whitelist、SSID 精确保留、只改运行时模式、未知网络≠非 Wi-Fi、实际出口读 SSID） | 按行为描述在 wanBox Root 网络规则层重写 | 否 |
| N4 eBPF shared 路径参数（`shared.enabled/interface/data_plane/dns_mode/default_interface`） | 这些是 sing-box 原生入站字段名（上游内核 schema），非 NetProxy 独创；按内核 schema 使用 | 否（字段名属上游 sing-box fork 的公开配置 schema） |
| N5 `<user-id>:<package>` 键与空后白名单 exclude-all-range 护栏 | 按行为描述重写 | 否 |
| N6 路由规则写原生 `route.rules`、插入位置、事务内引用保护 | 按行为描述在 wanBox `RuleEntity`/`ConfigBuilder` 上实现 | 否 |
| N7 fixture 双目录 + 桩外部命令 + 逐模式断言快照的测试方法 | 测试方法本身不受著作权保护；fixture 内容全部用虚构数据重写 | 否；不得把上游测试脚本原文拷入 |
| N8 eBPF 数据平面选项与预检语义 | 评估结论引用上游文档，字段名属 sing-box 原生 schema | 否 |

若实现批次中发现某处确实需要复制上游代码片段，实施者 MUST 在本节追加登记（出处文件、行范围、
许可证 GPL-3.0、复制理由），否则不得复制。
