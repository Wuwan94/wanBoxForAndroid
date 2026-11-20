# v3.0.18-preview.2 发行说明

**状态：已发布的稳定性预览版。**

本版收口 `v3.0.18-preview.1` 源码复审与代码复审提出的六项缺陷（PR #80，合并提交 `ad032285b3ff181677ed6c0ea9c88a7bb4e482a6`，2026-11-20T10:53:47Z，22 个文件 +2887/−167）：URLTest 观测按（成员, 网络）分账、UDP 探测只认真实往返回显、降级即作废旧回切资格、组关闭停拨备用、开机自启「下发 → 回读 → 落库」闭环、`boot.json` 不可用时状态与 `Boot()` 同口径。正式线 `3.0.17 / 2035`（APK `10175`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 均未变；预览元数据升为 `3.0.18-preview.2 / 2037`，派生 APK `versionCode=10187`、Root 模块码 `2037`，均高于已发布 preview.1 的 `10181 / 2036`，已装 preview.1 的用户可同时收到 APK 与模块更新。

本版基于 `main` 的 `e7f1f9366316974d8318e8708dcaaa669bc896a0`（升版 PR #81 合并提交，轻量 tag），`Preview Build` run `44728368529` 的四个作业（`Native Build (LibCore)`、`Build OSS APK`、`Candidate cold-start measurement (emulator only)`、`Publish Preview Release`）全部成功，于 2026-11-20T11:51:54Z 上架（prerelease，发布 job 断言 `APPROVED_SHA=e7f1f936` 并回读 tag）。发布后 `prune_prereleases.py` 在 11:52:04Z 删除了 `v3.0.18-preview.1` 的 Release/tag（现为 404），正式版 `v3.0.17`、`v3.0.14`、`v3.0.11` 保留。

## ✦ 与上一版对比

| 项目 | v3.0.18-preview.1 | v3.0.18-preview.2 |
|---|---|---|
| 内容 | 层级状态按 TCP/UDP 拆分；App UI 对齐模块功能（自启/重启核心/配置回滚/模块版本） | 观测按（成员, 网络）分账、UDP 只认真实回显、降级作废旧回切资格、组关闭停拨备用；开机自启下发→回读→落库闭环、`boot.json` 三态 |
| APK `versionCode` | 10181 | 10187 |
| Root 模块码 | 2036 | 2037 |

URLTest 稳定性约束（检测间隔 ≥ 10 秒、空闲超时 ≥ 30 分钟、切换时不中断现有连接）、`default` 保真、层级回切门（连续 3 轮健康 + 60 秒无失败）本版均未放宽；失败方向仍是 fail-closed——证据不足时停留在当前层级，不回切。

## ✦ 本版更新

### 1. urltest：观测按（成员, 网络）分账

- **问题**：preview.1 只把 `currentTier` / `tierGate` 拆开，**一轮探测的观测仍按 tag 记一份**，由双栈成员的两个网络共用。于是 TCP 侧的成功会成为 UDP 侧的回切证据，反过来 TCP 的失败也会清掉 UDP 的连续健康计数——两边的门都读到了不属于自己的证据。
- **改法**：`urlTestRound` 的观测改为 `tcp` / `udp` 两张表，两层恢复门只读自己那份；缓存跳过的轮次对两个网络各记「跳过」，轮次预算到期或调用方取消记「取消」，都不计入健康轮次；嵌套 URLTest 的聚合 summary 按网络各算一份；单协议成员只对它支持的网络产生观测。
- **说服力**：旧实现下「双栈成员 TCP 成功 → UDP 门被推到 3」与「TCP 连续失败清掉 UDP 历史」两条黑盒用例在 preview.1 代码上红，本版绿。

### 2. UDP 探测只认真实往返回显

- **问题**：`ProbeOutboundUDP` 原来只 `ListenPacket` 再 `Close` 就算成功。VMess/VLESS/Trojan/XUDP 都是 early packet conn，会话在首次写入才建立——建得起来既不证明服务端接受会话，也不证明数据报能往返，UDP 恢复门可能凭伪证据提前回切。
- **改法**：发一条探测数据报，**只有收到目标原样回显才 `verified`**；首次写入失败/关联失败/回包不符为 `rejected`（清零并盖失败戳）；无有效响应（静默丢弃、超时、空包）为 `unverified`（中性，既不涨也不清零）；未配置端点或成员不支持 UDP 为 `skipped`（零流量）；取消为 `canceled`。单一成员只发一条、只读一次、不重试，超时缺省 3 秒且不超过整轮 5 秒预算，取消即关闭连接叫醒阻塞读。
- **探测目标**：只来自用户配置 `URLTestOptions.udp_probe`（`server` 必须 `IP:port`、`payload` ≤ 512 B、`timeout` ≤ 5 秒），不默认任何第三方探测服务、不加合成 DNS 查询。
- **已知限制**：应用侧暂时没有配置 `udp_probe` 的入口，默认下 UDP 探测一律 `skipped`、不产生流量，UDP 回切只等真往返证据；低层不再健康时既有兜底仍会把上层选回，因此不会永久锁死。需要可配置端点的话，等于另开一项设置项。

### 3. 降级后旧回切资格立刻作废

- **问题**：一次故障转移把生效层降下去之后，更高层此前攒下的连续健康计数仍在，下一轮就能凭旧资格直接切回刚失败的层——回切门的迟滞被绕过。
- **改法**：`promoteFailover` 在同一把写锁内清空失败层并重盖失败戳、所有更高层的连续健康计数作废、`currentTier` 只下移，且只作用于被拨的那个网络；每次作废递增门代次（`epoch`），轮次开始时快照代次、收尾时若代次已变，该轮的成功观测不再授予回切资格（失败仍如实清零）。

### 4. 组关闭后不再拨备用节点

- **问题**：用户关掉代理组之后，容灾序列仍会继续发起备用拨号，并把迟到的连接交给一个已经关闭的组。
- **改法**：`DialContext` / `ListenPacket` 的整个容灾序列绑定组生命周期（`bindGroupLifecycle`），组一 `Close` 就取消在途尝试（含 3 秒单步超时窗口），备用循环不再发起任何新拨号；迟到的连接与 `PacketConn` 统一经 `wrapConn` / `wrapPacketConn`，组已关闭则关闭释放并返回统一错误。

### 5. 开机自启「下发 → 回读 → 落库」闭环

- **问题**：开关只即时下发、不等确认就落库，失败看起来和成功一样；`BootReceiver` 与模块 `boot.json` 各自为政，回设置页看到的也不一定是模块真正生效的值。
- **改法**：模块 `State` 新增只读 `autoStart`（`boot.json` 生效值），停止态也能下发与回读；App 新增 `AutoStartSync`（纯 Kotlin，可注入 Host）与 `AutoStartSettings` 装配——下发后必须回读确认才写 DataStore 与 `BOOT_COMPLETED` 接收器；失败、超时、结果未知重试一次，仍失败保持原值、回滚开关显示并给中文提示；快速连续切换由最后一次选择落地，旧请求的迟到结果只能 `Superseded`；返回设置页回读比对，不一致显示「待同步」。开关不再让 Preference 框架直接落库，模块未运行时不会被擅自启动。

### 6. `boot.json` 不可用时状态与 `Boot()` 同口径

- **问题**：`boot.json` 损坏时状态页显示「已开启」，`Boot()` 却拒绝启动——两边读法不一致，用户看到的是一个不存在的「已生效」。
- **改法**：新增 `bootSettingsFor`，`Status()` 与 `Boot()` 共用一种读法——文件不存在回落最近快照（`autoStartSource=snapshot`）；格式损坏、空文件、缺字段、字段类型无效为 `boot_settings_invalid`；权限或 I/O 错误为 `boot_settings_unreadable`。后几种 `autoStart` 为 `null` 并附 `autoStartError`，不再拿快照冒充生效值，`Boot()` 用同一错误码拒绝启动且不改写、不删除坏文件。`SetAutoStart` 仍是原子写，并成为唯一修复路径（重写前若读到不可用状态记 `boot_settings_rewritten`）。App 侧把回读改为三态（Value / Unavailable / Unsupported），配置损坏按失败处理不落库，设置页区分「待同步」与「待处理」。

### 7. 版本与文档口径

`nb4a.properties` 预览线为 `3.0.18-preview.2` / `2037`；正式线 `3.0.17 / 2035` 与 sing-box pin 未变。当前版本文档一致性检查已回到「已发布 preview.2 即源码预览」的单态口径。

## ✦ 资产与安装

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.18-preview.2-arm64-v8a-release.apk` | 6,371,663 | `2ecd6d9301b5e8240bd442a018bb4d64ae052fa4ac4ee1549e7af29217b8666b` |
| `wanbox-root-module-arm64-v8a.zip` | 18,149,031 | `b191ff8923f8ad8101337993e63fcd122e5ef002faa7e67d5a70096cfc07be3b` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23,794,995 | `96055b461db23ec094c20ec54b5861b4b694e2ede1a95b503612664da3072295` |
| `SHA256SUMS` | 333 | `b199faa8bde14695dbe3a741a6c1aab773db759b278ea41d7d214c532110d5be` |

APK 与普通 Root 模块合计 `24,520,694` 字节（23.38 MiB），上限 `27,262,976` 字节（26 MiB），余量 `2,742,282` 字节（2.62 MiB）→ `size_budget --enforce-limit` PASS；带管理器合一包 22.69 MiB。瘦管理器无 `lib/` 条目，`versionName=3.0.18-preview.2` / `versionCode=10187`；模块 `version=3.0.18-preview.2` / `versionCode=2037`；证书 SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`（与 v3.0.14 / v3.0.17 同一张，可同包名覆盖安装）。

- **运行要求**：Android 12+（API 31+）、arm64-v8a、Magisk / KernelSU / APatch 等 Root 环境。
- **成套同版**：管理器 APK 与 Root 模块必须来自同一 Release；严禁跨 Release 混用。
- **回退**：换回正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17) 的 APK 与配套模块（模块码 `2035`）成套重装。
- **升级**：APK 由 `10181` 升到 `10187`、模块由 `2036` 升到 `2037`，严格递增且同签名，可直接覆盖安装。

## 🛡️ 验证与边界

- **RED → GREEN**：四条黑盒用例在 preview.1 的线上代码上先红——「UDP 门被 TCP 成功推到 3」「一次关联（无任何回包）被记成 UDP 成功」「降级后立刻回切 T1」「关闭组仍拨备用并交出连接」——修复后全绿，并作为回归用例留在仓库（`udp_probe_evidence_test.go` 12 条、`review_20261120_test.go`、`rootmodule` 的 boot 契约 4 条与 `red_review_boot_test.go`，`AutoStartSyncTest` 增至 12 条）。
- **PR 门禁**：PR #80 的 `Verify Android Build` run `44717598549`（head `58ed0c7`）全绿——`Build LibCore` 内 `go test -race`、`go vet`、Python 工具 125 项、`rootmodule` 28 项与文档门禁通过，`Build Android Debug APK` 的 JVM 单测与插桩通过。升版 PR #81 的 run `44723295827` 首跑红在 plugins.gradle.org 解析 `kotlin-gradle-plugin:2.0.21` 瞬时不可用（失败在发布断言之前，未创建 tag/Release），`gh run rerun --failed` 后全绿。
- **发布门禁**：`Preview Build` run `44728368529` 四个 job 全绿，发布 job 断言 `APPROVED_SHA=e7f1f936` 并回读 tag；`prune_prereleases.py --keep-tag v3.0.18-preview.2` 在 11:52:04Z 删除了 preview.1 的 Release/tag。
- **四件套复核**：本地 `sha256sum -c SHA256SUMS` 三件 OK，两个 ZIP `zipfile -t` 无损坏；合一包内 `manager.apk` 与外部 APK 逐字节相同；`version-control-info.textproto` 的 `revision` 与 tag 对象、`targetCommitish`、`APPROVED_SHA` 四处同为 `e7f1f9366316974d8318e8708dcaaa669bc896a0`。
- **未验证边界**：未执行 Magisk / KernelSU / APatch 真机矩阵（CI 自标 `runtime_regression: not_verified`）；UDP 探测用的是本机可控回显端点，**不是真实代理服务端往返**，真实 VMess/VLESS/Trojan 的 UDP 往返未做端到端验证；应用侧没有 `udp_probe` 配置入口；真机上 `boot.json` 的权限与开机行为未验证。任何自动化结果都不代替真机验证，本版不晋升正式版。

---

## 历史归档

### v3.0.18-preview.1 已发布说明

**状态：已作为稳定性预览版发布（2026-11-19T11:55:25Z）。**

[v3.0.18-preview.1](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.1) 以 main 的 `0a30baaf939a97b1941210609ce377e00d386157`（轻量 tag）发布，`Preview Build` run `44516333684` 四个作业全绿（发布 job 断言 `APPROVED_SHA=0a30baaf`）；预览元数据 `2036`，APK `10181` / 模块 `2036`。内容为 PR #77（`currentTier` / `tierGate` 按 TCP/UDP 拆分，消除跨协议污染）与 PR #78（App UI 对齐模块功能：开机自启开关、重启核心、回滚配置、顶栏模块版本）。已知边界：一次探测的观测仍按 tag 共享、嵌套组聚合 summary 与网络无关，两个新 UI 入口无自动化覆盖。`0a30baa` 是 `preview.yml` 的热修直推提交，没有单独的 `Verify Android Build` 记录，其代码树与已全绿的 `4a8ab9b` 相同。本版 Release 已于 11:52:04Z 在 `v3.0.18-preview.2` 发布时由 `prune_prereleases.py` 清理（现为 404）。

### v3.0.17-preview.4 已发布说明

**状态：已作为稳定性预览版发布；Release/tag 已在 v3.0.18-preview.1 发布后清理。**

基于 main `906a6ea3f48779cc96c7a4116cfd1faeb63aa354`（PR #72，`Verify Android Build` run `44372679147`），`Preview Build` run `44378329740` 于 2026-11-18T19:38:07Z 发布；预览元数据 `2034`，APK `10174` / 模块 `2034`。内容为故障转移不再绕过层级回切门、过期候选保留为最后梯队兜底、direct 失败改 15s/1min/5min 有界退避、区分 `su` 超时与真正的空白名单，另收口 supervisor 吞错、预算到期虚增健康轮与零引用符号。资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。

### v3.0.17-preview.3 已发布说明

[v3.0.17-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.3) 已作为稳定性预览版发布（tag/commit `6adc5ac0bcbd87bf9ff4b2097fe367761a4bc841`，PR #69；`Verify Android Build` run `44295650161`，`Preview Build` run `44306599880` 于 2026-11-18T13:08:01Z 发布）。内容为修复 URLTest 预算到期后层级永不回切、诊断包 Android 12+ 不可读取，并收口八项 P2 与 Room 11 schema；APK `10168` / 模块 `2033`。资产快照见[版本对照](docs/current-version.zh-CN.md)；其 Release/tag 已在 preview.4 发布后按单预览策略清理，下载链接不再可用。

### v3.0.17-preview.2 已发布说明

[v3.0.17-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.2) 已作为稳定性预览版发布（tag/commit `49e966197dd608b687b36eecdaf394274dacfe15`，PR #67/#68；`Preview Build` run `44281162788` 于 2026-11-18T10:18:19Z 发布）。内容为 OwnBox/NetProxy 两路借鉴移植合并后的首个预览：核心稳定性与故障转移、订阅自动更新、智能分流、冷启动秒连、策略组混搭/锁定/优先级、停核可编辑与脱敏诊断包、按网络规则、路由规则指定策略组；APK `10162` / 模块 `2032`。其 Release/tag 已在 preview.3 发布后按单预览策略清理，下载链接不再可用。

### 更早预览

v3.0.17-preview.1、v3.0.16-preview.4、v3.0.16-preview.3、v3.0.16-preview.2、v3.0.16-preview.1、v3.0.15-preview.1–.4 与 v3.0.14-preview.1 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。
