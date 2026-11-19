# v3.0.18-preview.1 发行说明

**状态：已发布的稳定性预览版。**

本版基于 `main` 的 `0a30baaf939a97b1941210609ce377e00d386157`（轻量 tag，PR #77 与 PR #78 合入后的 `preview.yml` 热修提交），`Preview Build` run `44516333684` 的四个作业（`Native Build (LibCore)`、`Build OSS APK`、`Candidate cold-start measurement (emulator only)`、`Publish Preview Release`）全部成功并于 2026-11-19T11:55:25Z 发布（prerelease，发布 job 断言 `APPROVED_SHA=0a30baaf` 并回读 tag）。正式线 `3.0.17 / 2035`（APK `10175`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 均未变；预览元数据 `3.0.18-preview.1 / 2036`，派生 APK `versionCode=10181`、Root 模块码 `2036`，均高于 preview.4 的 `10174 / 2034`。发布后 `prune_prereleases.py` 在 11:55:35Z 删除了 `v3.0.17-preview.4` 的 Release/tag（现为 404），正式版 `v3.0.17`、`v3.0.14`、`v3.0.11` 保留。

## ✦ 与上一版对比

| 项目 | v3.0.17-preview.4 | v3.0.18-preview.1 |
|---|---|---|
| 内容 | 故障转移回切门、过期候选兜底、direct 有界重试、空白名单与 `su` 超时分离 | urltest 层级状态按 TCP/UDP 拆分；App UI 对齐模块功能（自启/重启核心/配置回滚/模块版本） |
| APK `versionCode` | 10174 | 10181 |
| Root 模块码 | 2034 | 2036 |

URLTest 稳定性约束（检测间隔 ≥ 10 秒、空闲超时 ≥ 30 分钟、切换时不中断现有连接）、`default` 保真、层级回切门（连续 3 轮健康 + 60 秒无失败）本版均未放宽。

## ✦ 本版更新

### 1. urltest：层级状态按网络拆分，消除跨协议污染（PR #77）

- **问题**：`currentTier` 与 `tierGate` 原来只有一份。分流器同时持有 TCP 与 UDP 出站时，TCP 侧一次建连失败就把它所在的生效层降下去，UDP 侧被动跟着降 —— 每一轮都会继续往下拖，哪怕 UDP 成员一直健康。
- **改法**：`currentTier` / `tierGate` 拆成 `tierTCP` / `tierUDP`，各带独立生效层与独立 map 门（两侧 map 分别新建，不共享实例）；`effectiveTierLocked`、`canSwitchBackTo` 按网络取状态，未知网络直接返回不允许回切；`updateTierGateLocked` 先按成员支持的网络过滤再推进对应一侧；`promoteFailover` 只动被拨的那个网络；轮次收尾里 TCP/UDP 的互斥 `else if` 改为两侧独立同步。
- **说服力**：「T1/T2 各一个 TCP-only 与一个 UDP-only 成员」的黑盒用例在旧代码 `554340fc` 上红于「UDP 仍在 T1 却被拖到 T2」，在本版代码上绿；另有 11 条隔离回归用例（含未知网络不改状态）。

### 2. App UI 对齐模块能力（PR #78）

- **开机自启开关恢复**：`isAutoConnect` 重新出现在 root 设置分区，写回 DataStore 并立即向模块下发 `setAutoStart`；旧键的取值与控件类型仍由 `settings-before-3.0.7.json` 逐项契约比对兜住。
- **「重启核心」**：设置页新增一行带确认弹窗的操作，走模块 `restart`，用于配置改了但不想整机重启时重载核心。
- **「回滚配置」**：新增一行带确认弹窗的操作，客户端命令白名单补上 `config rollback`，用于「新配置把自己关在门外」时退回上一份可用配置。
- **顶栏模块版本**：仪表盘副标题跟随 `moduleVersionFlow`，装完新模块不必再进设置页确认版本号。
- 模块侧零改动：三个入口都是已存在命令的 UI 对齐，不新增守护逻辑；文案中英双份。

## ✦ 资产与安装

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.18-preview.1-arm64-v8a-release.apk` | 6,366,243 | `459e5bee4ee404eccf165b9ca09169ab42ee3ad3839af4525e3058bf7bdacd4d` |
| `wanbox-root-module-arm64-v8a.zip` | 18,134,172 | `b53e8653de2aa460acb66006d1ba8b6884220a941e46a44b4d9418803b5f398f` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23,778,516 | `8d219e336b2b63539611b9ee37f9871c5c3a437f56317013fa190e693ebbe794` |
| `SHA256SUMS` | 333 | `5ac21d47eb74fb63b0419dd7279a8470509268169da0efe0924bd0d3062234e5` |

APK 与普通 Root 模块合计 `24,500,415` 字节（23.37 MiB），上限 `27,262,976` 字节（26 MiB），余量 `2,762,561` 字节 → `size_budget --enforce-limit` PASS；带管理器合一包 22.68 MiB。瘦管理器无 `lib/` 条目，`versionName=3.0.18-preview.1` / `versionCode=10181`；模块 `version=3.0.18-preview.1` / `versionCode=2036`；证书 SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`（与 v3.0.14 / v3.0.17 同一张，可同包名覆盖安装）。

- **运行要求**：Android 12+（API 31+）、arm64-v8a、Magisk / KernelSU / APatch 等 Root 环境。
- **成套同版**：管理器 APK 与 Root 模块必须来自同一 Release；严禁跨 Release 混用。
- **回退**：换回正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17) 的 APK 与配套模块（模块码 `2035`）成套重装。
- **升级**：APK 由 `10175` 升到 `10181`、模块由 `2035` 升到 `2036`，严格递增且同签名，可直接覆盖安装。

## 🛡️ 验证与边界

- **PR 门禁**：PR #77 的 `Verify Android Build` run `44486772559`（head `91e64a1`）含 `go test -race ./protocol/urltest ./protocol/loadbalance` 并通过；PR #78 的 `Verify Android Build` run `44506930043`（head `4a8ab9b`）全绿 —— Python 工具 125 项、`rootmodule` 28 项、文档门禁与插桩 81 项均通过。
- **发布门禁**：首个 `Preview Build` run `44511891425`（`6215b45`）红在 `preview.yml` 残留的正式版硬编码 `3.0.14` 版本断言，未创建 Release/tag；`0a30baa` 把该断言改为读 `nb4a.properties` 的 `VERSION_NAME` 后，run `44516333684` 四作业全绿并发布。`0a30baa` 是直接推 main 的热修，没有单独的 `Verify Android Build` 记录，其 Go/Kotlin 树与已过门禁的 `4a8ab9b` 相同。
- **四件套复核**：`sha256sum -c SHA256SUMS` 三件 OK；三个 ZIP `testzip` 无损坏；合一包与普通包仅差 `manager.apk`（与外部 APK 逐字节相同）；`version-control-info.textproto` 的 `revision` 与 tag、`targetCommitish`、`APPROVED_SHA` 四处同为 `0a30baaf939a97b1941210609ce377e00d386157`；模块二进制内可检出 `network_direct_failed` / `network_policy_invalid`，发布二进制即本版代码。
- **已知边界**：跨协议隔离做到 `currentTier` 与 `tierGate` 层；一次探测的观测仍按 tag 记录、由双栈成员的两个网络共享（只有单网络成员被过滤），嵌套 URLTest 组的聚合 summary 仍与网络无关。
- **未验证边界**：未执行 Magisk / KernelSU / APatch 真机矩阵（CI 自标 `runtime_regression: not_verified`）；两个新 UI 入口无自动化覆盖，回滚配置在「核心已停」时只切 `current` 不启动核心。任何自动化结果都不代替真机验证，本版未晋升正式版。

---

## 历史归档

### v3.0.17-preview.4 已发布说明

**状态：已作为稳定性预览版发布；Release/tag 已在 v3.0.18-preview.1 发布后清理。**

本版基于 `main` 的 `906a6ea3f48779cc96c7a4116cfd1faeb63aa354`（PR #72，`Verify Android Build` run `44372679147` 全绿后于 2026-11-18T19:13:48Z 合并），`Preview Build` run `44378329740` 于 2026-11-18T19:38:07Z 发布（发布 job 断言 `APPROVED_SHA=906a6ea3`）。正式稳定版 `v3.0.14`（`VERSION_CODE=2026`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 均未改变；预览元数据为 `PRE_VERSION_CODE=2034`，派生 APK `versionCode=10174`、Root 模块码 `2034`，均高于已发布 preview.3 的 `10168` / `2033`。其 Release/tag 与资产已由 `v3.0.18-preview.1` 发布后的 `prune_prereleases.py` 删除（run `44516333684` 日志 11:55:35Z），下载链接现为 404，此段仅作历史记录。

## ✦ 与上一版对比

| 项目 | v3.0.17-preview.3 | v3.0.17-preview.4 |
|---|---|---|
| 内容 | URLTest 预算到期后层级永不回切、诊断包 Android 12+ 不可读取 | 故障转移不再绕过层级回切门、过期候选保留兜底；direct 失败有界重试；空白名单与 `su` 超时分离 |
| APK `versionCode` | 10168 | 10174 |
| Root 模块码 | 2033 | 2034 |

URLTest 稳定性约束（检测间隔 ≥ 10 秒、空闲超时 ≥ 30 分钟、切换时不中断现有连接）、`default` 保真、候选应用的版本 CAS 与恢复校验链本版均未放宽。

## ✦ 本版更新

### 1. P2：故障转移不得绕过层级回切门

- 上一版新增的故障转移从**全部层级**取候选，一次建连失败就把胜者所在层写成 `currentTier`，于是 B7c 的「连续 3 轮健康 + 60 秒无失败」回切迟滞被永久跳过（还可能出现一次跨两层跳跃）。
- 现在候选限定在当前生效层内（全层都没有健康成员时才退回全部成员）；故障转移成功只提升数据面选择，`currentTier` 仍只由轮次收尾的层级门推进。

### 2. P2：过期候选保留为最后梯队

- 上一版只要存在新鲜候选就剔除全部过期候选；这个「新鲜」节点恰好已死时，后面没有任何兜底，这次连接直接失败（旧实现会继续试后面的成员）。
- 现在排序仍是新鲜优先、延迟升序，但过期候选排在最后梯队：新鲜候选全部失败后仍能换到过期但可用的成员。

### 3. P2：direct 失败改有界退避重试

- 上一版把 `SetRuntimeMode("direct")` 的失败改成一次锁存，只有离开直连网络才重试；瞬时失败（核心刚重启、Clash API 短暂 5xx、模块 reload 中）会让手机在用户明确要求直连的网络上一直走代理。
- 现在按 15 秒 → 1 分钟 → 5 分钟退避重试，之后保持 5 分钟一次的慢速重试，永不放弃；失败事件在 5 分钟窗口内只记一条，切换成功或离开直连时清零。

### 4. P2：区分 `su` 超时与真正的空白名单

- 上一版把「请求非空但解析后为空」一律当成名单问题抛 `per_user_app_whitelist_empty`，于是 Root 授权弹窗还没点（`PackageCache` 的 `su` 5 秒超时）也会让服务起不来。
- 现在 `PackageCache` 记录跨用户 UID 查询失败码，失败时不再把空表当成功快照缓存满 5 分钟（30 秒后重试，保留上一份成功快照）；`PerUserApps` 只在查询成功却一个有效 UID 都没有时才拦下启动，并把提示改成本地化文案（中/英），不再只显示裸错误码。查询失败则退回旧行为：只代理已解析到的部分，并记日志。

### 5. 顺手收口

- **supervisor 不再吞规则错误**：`supervisor.go` 的 `readUnifiedPolicy` 错误不再用 `_` 丢弃，与 `health.go` 同口径记一次限频 `network_policy_invalid`。
- **预算到期不再虚增健康轮**：轮次收尾改为按「本轮是否真有成员得到判定（成功或失败）」计数；整轮零观测时不再凭历史新鲜度推进层级恢复门。
- **零引用符号澄清**：`BalancerTierScheduler` 的 `TierRecoveryGate`、`selectTieredMember`、`activeTierMemberIds`（及 `BalancerMemberHealth`/`TieredSelection`）标注 `@Deprecated` 并写明运行时唯一实现在 Go 侧；文件本体（规则编解码、层级分配）仍由 `ConfigBuilder` 与设置页使用。

## 📦 版本、资产与安装

- **运行要求**：Android 12+（API 31+）、arm64-v8a、Magisk / KernelSU / APatch 等 Root 环境。
- **版本对齐**：APK `versionName=3.0.17-preview.4` / `versionCode=10174`；Root 模块码 `2034`。瘦管理器和 Root 模块必须来自同一 Release、同版成套使用。
- **回退**：使用当前正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17) 的 APK 与配套 Root 模块（模块码 `2035`）；不要混用不同 Release 的 APK 和模块。上一预览 preview.3 已按单预览策略在本版发布后被清理。

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.17-preview.4-arm64-v8a-release.apk` | 6,363,791 | `7c6786f2708baa0299b7195c950c819570f867ce909ca54482ed5eaa61a0c9c3` |
| `wanbox-root-module-arm64-v8a.zip` | 18,131,042 | `9b44e0a1226dcf88ef9c1c7c095aa736fcaedb96e83aae4bc28663564da329b6` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23,774,566 | `f3bb069962634cf1583200cb691335f4347a3bb1d76b4a8d8ad038ff626934c8` |
| `SHA256SUMS` | 333 | `0c0a2d9fddf58160c2fc1df7ce37b9048091a30dca4844fbde13c9624ef36783` |

APK 与普通 Root 模块合计 `24,494,833` 字节（23.36 MiB），上限 `27,262,976` 字节（26 MiB），余量 `2,768,143` 字节（2.64 MiB）→ `size_budget --enforce-limit` PASS；带管理器合一包 22.67 MiB。

## 🛡️ 验证与边界

- **回归测试先红后绿**：`libcore/protocol/urltest` 三条新用例在修复前分别红于「跨层转移发生 1 次」「过期可用成员 0 次调用」「零观测轮次把恢复门从 0 推到 1」，修复后全绿；`rootmodule` 的 direct 重试用例在旧锁存语义下红于「15 秒后仍拒绝重试」，改为有界退避后绿。
- **PR 门禁**：`Verify Android Build` run `44372679147`（head `b7b35de`）全绿——Build LibCore 内 Go 测试与 Python 门禁（`tools` 124 项、`rootmodule` 28 项）通过；插桩实测 debug 81 项、release 81 项，另 2 项定向复跑，0 失败。
- **发布门禁**：`Preview Build` run `44378329740` 四个 job 全绿，发布 job 断言 `APPROVED_SHA=906a6ea3` 并回读 tag；`prune_prereleases.py --keep-tag v3.0.17-preview.4` 在 19:38:15Z 删除了 preview.3 的 Release/tag。
- **四件套复核**：`sha256sum -c SHA256SUMS` 三件 OK；三个 ZIP `testzip` 无损坏；`version-control-info.textproto` 的 `revision` 为 `906a6ea3f48779cc96c7a4116cfd1faeb63aa354`，与 tag、`targetCommitish`、`APPROVED_SHA` 同源；合一包内 `manager.apk` 与外部 APK 逐字节相同；签名证书 SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`（与正式版 v3.0.14 同一张）。
- **未验证边界**：未执行 Magisk / KernelSU / APatch 真机矩阵（CI 自标 `runtime_regression: not_verified`）；层级回切在真实订阅下的收敛速度、Root 授权弹窗延迟下的分应用名单行为仍需真机确认。任何自动化结果都不代替真机验证，本版未晋升正式版。

### v3.0.17-preview.3 已发布说明

[v3.0.17-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.3) 已作为稳定性预览版发布（tag/commit `6adc5ac0bcbd87bf9ff4b2097fe367761a4bc841`，PR #69；`Verify Android Build` run `44295650161`，`Preview Build` run `44306599880` 于 2026-11-18T13:08:01Z 发布）。内容为修复 URLTest 预算到期后层级永不回切、诊断包 Android 12+ 不可读取，并收口八项 P2 与 Room 11 schema；APK `10168` / 模块 `2033`。资产快照见[版本对照](docs/current-version.zh-CN.md)；其 Release/tag 已在 preview.4 发布后按单预览策略清理，下载链接不再可用。

### v3.0.17-preview.2 已发布说明

[v3.0.17-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.2) 已作为稳定性预览版发布（tag/commit `49e966197dd608b687b36eecdaf394274dacfe15`，PR #67/#68；`Preview Build` run `44281162788` 于 2026-11-18T10:18:19Z 发布）。内容为 OwnBox/NetProxy 两路借鉴移植合并后的首个预览：核心稳定性与故障转移、订阅自动更新、智能分流、冷启动秒连、策略组混搭/锁定/优先级、停核可编辑与脱敏诊断包、按网络规则、路由规则指定策略组；APK `10162` / 模块 `2032`。其 Release/tag 已在 preview.3 发布后按单预览策略清理，下载链接不再可用。

### 更早预览

v3.0.17-preview.1、v3.0.16-preview.4、v3.0.16-preview.3、v3.0.16-preview.2、v3.0.16-preview.1、v3.0.15-preview.1–.4 与 v3.0.14-preview.1 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。
