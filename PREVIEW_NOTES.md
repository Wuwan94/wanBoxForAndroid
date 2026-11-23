# v3.0.18-preview.4 发行说明（候选）

**状态：未发布的稳定性预览版候选。**

本版收口 `v3.0.18-preview.3` 发布后合入 `main` 的三批复审修复：UDP 回显探测改为按来源校验回包并在截止判定上区分「取消」与「超时」（PR #86，合并提交 `2add76212e54c54b6d4dda02b1077ab373352e24`，12 个文件 +535/−147）、门代次在轮内变化时仍删除已明确失败成员的 UDP 证据并给「导出/一次性测速排除探测端点」补回归（PR #84，合并提交 `00cfeedddc07249cccd8a8344eb1bf7df97d75dc`，5 个文件 +71/−3）、开机自启结果改为可重放且只呈现一次、锁粒度收窄与异常兜底、UDP 迟到合法回包补行为级用例（PR #87，合并提交 `f0f80b92d597a334440c25171648b19b76573b59`，11 个文件 +534/−45）。正式线 `3.0.17 / 2035`（APK `10175`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 均未变；预览元数据升为 `3.0.18-preview.4 / 2039`，派生 APK `versionCode=10199`、Root 模块码 `2039`，均高于已发布 preview.3 的 `10193 / 2038`，已装 preview.3 的用户可同时收到 APK 与模块更新。本条目在候选阶段写定；发布后由文档同步 PR 回填 run 号、四件套实测字节与 SHA-256，并把状态翻为「已发布」。

## ✦ 与上一版对比

| 项目 | v3.0.18-preview.3 | v3.0.18-preview.4（候选） |
|---|---|---|
| 内容 | UDP 回显端点设置入口；UDP-only 上层成员凭回显进候选；首次应用配置前回读自启设置 | UDP 回包按来源校验、截止判定先认取消；门代次变化仍删失败证据；自启结果可重放且只消费一次、锁外做 Room/IPC；UDP 迟到回包用例 |
| APK `versionCode` | 10193 | 10199 |
| Root 模块码 | 2038 | 2039 |

URLTest 稳定性约束（检测间隔 ≥ 10 秒、空闲超时 ≥ 30 分钟、切换时不中断现有连接）、`default` 保真、层级回切门（连续 3 轮健康 + 60 秒无失败）本版均未放宽；失败方向仍是 fail-closed——证据不足时停留在当前层级，不回切。

## ✦ 本版更新

### 1. UDP 回显探测：回包要按来源校验，超时不再冒充成功（PR #86）

- **问题**：preview.3 的判定只比对回包内容与探测报文是否逐字节相同。直连 UDP 下同一局域网的另一个端点、或任何能把同内容数据报送到本机 socket 的路径，都能伪造「目标回显」；反过来，回包与探针 deadline 同时就绪时 `select` 不保证先看 deadline，迟到的回包会被记成成功。另外 `err != nil` 时只看 `ctx` 与「静默」分类，`probeCtx` 已到期但取消回调尚未关闭连接的窗口会把结果归错类。
- **改法**：`ReadFrom` 的来源必须能解析成 `IP:port` 且与配置目标一致，否则：来源不可核对（`nil`、只有 IP 没有端口、域名）记 `unverified`（中性），来源不一致或内容不符记 `rejected`（清零并盖失败戳）。截止判定抽成 `udpProbeContextOutcome`——父上下文取消优先记 `canceled`，单探针超时记 `unverified`，并在 `ListenPacket`、`WriteTo`、`ReadFrom` 之后各复核一次，覆盖「timer 还没触发」的窗口；`udpProbeContextStopped` 同时看 `Err()` 与 `Deadline()`。
- **证据**：新增 `udp_probe_source_test.go` 5 条（同内容错误来源被拒、来源不可核对为未知、来源与内容都匹配仍成功、错误来源永不推进 UDP 回切门、v4/v6 地址族比对）与 `udp_probe_deadline_test.go` 的 `TestUDPProbeDeadlineClassification`；`udp_probe_evidence_test.go` 增至 14 条。

### 2. 门代次轮内变化时仍删除已失败成员的 UDP 证据（PR #84）

- **问题**：preview.3 的「失败删除」整段被门代次快照判断包住。同一轮里若先有成员失败、随后降级使门代次递增（`epoch` 变化），这段删除被整段跳过——失败成员的旧证据留在新代次里，下一次回切仍可能拿它当依据。
- **改法**：代次快照只拦住「成功观测授予回切资格」，不拦失败删除；已明确失败（`rejected`）的成员在本轮收尾一律清掉其 UDP 证据，代次是否变化不影响。
- **证据**：`udp_probe_evidence_test.go` 新增用例覆盖「轮内降级后再败一次」的路径。

### 3. 「导出/一次性测速排除 UDP 探测端点」可测化（PR #84）

- **改法**：`ConfigBuilder` 里对 UDP 探测端点（`url-test` 组外的探测目标）的排除判定抽成 `UrlTestUdpProbe` 里的纯函数，导出配置与一次性测速共用同一份规则，不再各自内联。
- **证据**：`ConfigBuilderLoadBalanceTest` 补四种组合的 JVM 回归（有无探测配置 × 是否导出/测速），避免「测速把探测端点当成节点」与「导出配置把端点写进节点列表」两处回退。

### 4. 开机自启结果可重放，页面只呈现一次（PR #86 + PR #87）

- **问题**：preview.3 的自启同步把结果直接回调给发起点击的那个页面实例。用户点完立刻离开或转屏重建，结果没有接收者；反过来「切开关失败后离开，第二天再进来仍显示上次的失败摘要」，因为失败状态没有一个「呈现过就清掉」的出口。
- **改法**：`AutoStartSync` 用 `MutableStateFlow` 发布 `Idle` / `Pending` / `Finished`，状态里不持有任何 View 或回调引用；设置页以 `repeatOnLifecycle(STARTED)` 收集，重开页面能看到在途状态与最近结果。PR #87 再把「能不能呈现」抽成纯函数 `AutoStartUiGate.accepts(...)`（已挂载、视图存在、宿主未 finishing、未 destroyed、视图 ≥ STARTED、状态仍是最新发布），Fragment 只做取参转调；`consume(id)` 让结果呈现一次后回到 `Idle`，且只消费匹配 id 的结果；失败在页面有效时用 Toast 提示一次，摘要随后回到当场回读的真相（不一致、配置损坏仍照常显示）。
- **证据**：`AutoStartSyncUiGateTest` 新增 8 条 JVM 用例（分离、视图销毁、Activity finishing、Activity 销毁、视图低于 STARTED、结果过期各一条，另有放行与 pending 重放两条，防止把门写成恒 `false` 也能过关）；`AutoStartSyncTest` 由 12 条增至 20 条。

### 5. 自启落库锁粒度收窄与异常兜底（PR #87）

- **问题**：登记锁原来包住 `host.confirmed()` 的 Room 读、`persist()` 的 Room 写和 `setComponentEnabledSetting` 的 IPC。Room 写事务被卡住时，用户下一次点开关只能排在写事务后面；落库抛异常还会让界面永久停在 Pending。
- **改法**：锁只保护 `sequence` 与内存态，Room 读写与 IPC 全部移到锁外，`StateFlow` 改用原子更新发布；`settle()` 把落库与结果发布纳入 `try/catch`，落库异常时发布可见失败并保持原值；应用级作用域补 `CoroutineExceptionHandler` 兜底。
- **证据**：新增 4 条单测，其中「把写库卡住、另起线程点开关」在旧实现下必红（点击排在写事务后面）。

### 6. UDP 迟到合法回包与哨兵语义（PR #87）

- **问题**：上下文结束时「未停止」的返回值借用 `UDPProbeSkipped`，与「未配置端点 / 成员不支持 UDP」撞名，调用方容易把「本轮没结论」读成「本成员不参与」；另外「上下文结束后才到的合法回包」只有一个静态断言，没有行为级用例。
- **改法**：`udpProbeContextOutcome` 的「未停止」返回值改为零值 `UDPProbeUnobserved`，调用方仍须先看 `stopped`；新增 `udp_probe_late_reply_test.go` 三条行为级用例（fixture 让 `ReadFrom` 阻塞到上下文结束，再交出逐字节相同、来源合法的回包）：`TestUDPProbeLateValidReplyIsNotVerified`、`TestUDPProbeReplyAfterCallerCancellationIsCanceled`、`TestUDPLateValidReplyNeverAdvancesTheGate`。
- **口径**：`openspec/specs/libcore-integration/spec.md` 给来源校验写明效力边界——early conn / mux / UoT / XUDP / packetaddr 返回的是配置目标或服务端看到的源，只能证明内容回显，不得被引用为身份认证证据。

### 7. 版本与文档口径

`nb4a.properties` 预览线升为 `3.0.18-preview.4` / `2039`；正式线 `3.0.17 / 2035` 与 sing-box pin 未变。当前版本文档一致性检查改为「已发布 preview.3 + 未发布 preview.4 候选」的双态口径。

## ✦ 资产与安装

- **运行要求**：Android 12+（API 31+）、arm64-v8a、Magisk / KernelSU / APatch 等 Root 环境。
- **候选阶段**：本候选由 `chore/release-v3.0.18-preview.4` 升版后的 `Preview Build` 产出，四件套实测字节与 SHA-256 在发布后由文档同步 PR 回填本节。
- **成套同版**：管理器 APK 与 Root 模块必须来自同一 Release；严禁跨 Release 混用。
- **回退**：换回正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17) 的 APK 与配套模块（模块码 `2035`）成套重装。
- **升级**：APK 由 `10193` 升到 `10199`、模块由 `2038` 升到 `2039`，严格递增且同签名，可直接覆盖安装。

## 🛡️ 验证与边界

- **PR 门禁**：PR #84 的 `Verify Android Build` run `44898283085`（head `c6c6f038`）全绿；PR #86 的 run `45152559766`（head `d6426d10`）全绿；PR #87 的 run `45165265102`（head `0c1cbba`）全绿（其首跑 `45162179175` 红在阻塞写库用例的 `runBlocking { }` 让测试方法返回了非 Unit，已用 `runBlocking<Unit>` 修掉）。三者均含 `go test -race`、`go vet`、Python 工具 125 项、`rootmodule` 测试与文档门禁、JVM 单测与插桩。
- **新增回归**：`udp_probe_source_test.go` 5 条、`udp_probe_deadline_test.go` 1 条、`udp_probe_late_reply_test.go` 3 条、`udp_probe_evidence_test.go` 增至 14 条；`AutoStartSyncTest` 增至 20 条、`AutoStartSyncUiGateTest` 8 条。
- **未验证边界**：未执行 Magisk / KernelSU / APatch 真机矩阵（CI 自标 `runtime_regression: not_verified`）；UDP 探测用的是本机可控回显端点，**不是真实代理服务端往返**，真实 VMess/VLESS/Trojan 的 UDP 往返未做端到端验证；真机上 `boot.json` 的权限与开机行为未验证。任何自动化结果都不代替真机验证，本版不晋升正式版。

---

## 历史归档

### v3.0.18-preview.3 已发布说明

**状态：已发布的稳定性预览版。**

本版收口 `v3.0.18-preview.2` 复审提出的四项缺陷（PR #83，合并提交 `8881dd1c59481b3d23ab3766fb21e3b651fa1d65`，25 个文件 +332/−78，`Verify Android Build` run `44879210932` 在 head `dbdbf0b` 全绿）。预览元数据 `3.0.18-preview.3 / 2038`，派生 APK `versionCode=10193`、Root 模块码 `2038`。`Preview Build` run `44882104257` 四作业全绿，2026-11-21T08:13:01Z 上架（prerelease，发布 job 断言 `APPROVED_SHA=8881dd1c` 并回读 tag）；08:13:09Z 由 `prune_prereleases.py` 清理 `v3.0.18-preview.2`，正式版 `v3.0.17`、`v3.0.14`、`v3.0.11` 保留。内容：「设置 → 连接与核心 → UDP 回显端点」可填自管数字 IP 与端口（默认留空即零流量，fail-closed），各优先级层子组独立执行 UDP 回显验证；仅支持 UDP 的上层成员凭有效回显进入候选，不再依赖 TCP 延迟记录（回切仍要连续三轮新观测 + 60 秒无失败）；首次应用配置前也回读已保存的开机自启设置；自启等待确认期间连点按最后一次选择落地。四件套：APK `6374839` B / `e7832733…893507`、普通 Root ZIP `18149655` B / `1fbfc62a…bdc280`、合一 ZIP `23797772` B / `2fe93950…88c80d`、`SHA256SUMS` `333` B，APK + 普通模块 `24524494` 字节（23.39 MiB）→ `size_budget --enforce-limit` PASS。本版自身的 Release/tag 将在 `v3.0.18-preview.4` 发布时按单预览策略清理。

### v3.0.18-preview.2 已发布说明

[v3.0.18-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.2) 以 main `e7f1f9366316974d8318e8708dcaaa669bc896a0`（升版 PR #81 合并提交，轻量 tag）发布，`Preview Build` run `44728368529` 四作业全绿、2026-11-20T11:51:54Z 上架；预览元数据 `2037`，APK `10187` / 模块 `2037`。内容为收口 preview.1 六项缺陷（PR #80）：URLTest 观测按（成员, 网络）分账、UDP 探测只认真实往返回显、降级即作废旧回切资格、组关闭停拨备用、开机自启「下发 → 回读 → 落库」闭环、`boot.json` 不可用时状态与 `Boot()` 同口径。已知边界：该版 UDP 探测默认零流量，自管端点入口到 preview.3 才落地。其 Release/tag 已在 preview.3 发布后（2026-11-21T08:13:09Z）清理。

### v3.0.18-preview.1 已发布说明

[v3.0.18-preview.1](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.1) 以 main 的 `0a30baaf939a97b1941210609ce377e00d386157`（轻量 tag）发布，`Preview Build` run `44516333684` 四个作业全绿（发布 job 断言 `APPROVED_SHA=0a30baaf`）；预览元数据 `2036`，APK `10181` / 模块 `2036`。内容为 PR #77（`currentTier` / `tierGate` 按 TCP/UDP 拆分，消除跨协议污染）与 PR #78（App UI 对齐模块功能：开机自启开关、重启核心、回滚配置、顶栏模块版本）。已知边界：一次探测的观测仍按 tag 共享、嵌套组聚合 summary 与网络无关，两个新 UI 入口无自动化覆盖。`0a30baa` 是 `preview.yml` 的热修直推提交，没有单独的 `Verify Android Build` 记录，其代码树与已全绿的 `4a8ab9b` 相同。本版 Release 在 `v3.0.18-preview.2` 发布时由 `prune_prereleases.py` 清理。

### v3.0.17-preview.4 已发布说明

**状态：已作为稳定性预览版发布；Release/tag 已在 v3.0.18-preview.1 发布后清理。**

基于 main `906a6ea3f48779cc96c7a4116cfd1faeb63aa354`（PR #72，`Verify Android Build` run `44372679147`），`Preview Build` run `44378329740` 于 2026-11-18T19:38:07Z 发布；预览元数据 `2034`，APK `10174` / 模块 `2034`。内容为故障转移不再绕过层级回切门、过期候选保留为最后梯队兜底、direct 失败改 15s/1min/5min 有界退避、区分 `su` 超时与真正的空白名单，另收口 supervisor 吞错、预算到期虚增健康轮与零引用符号。资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。

### v3.0.17-preview.3 已发布说明

[v3.0.17-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.3) 已作为稳定性预览版发布（tag/commit `6adc5ac0bcbd87bf9ff4b2097fe367761a4bc841`，PR #69；`Verify Android Build` run `44295650161`，`Preview Build` run `44306599880` 于 2026-11-18T13:08:01Z 发布）。内容为修复 URLTest 预算到期后层级永不回切、诊断包 Android 12+ 不可读取，并收口八项 P2 与 Room 11 schema；APK `10168` / 模块 `2033`。资产快照见[版本对照](docs/current-version.zh-CN.md)；其 Release/tag 已在 preview.4 发布后按单预览策略清理，下载链接不再可用。

### v3.0.17-preview.2 已发布说明

[v3.0.17-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.2) 已作为稳定性预览版发布（tag/commit `49e966197dd608b687b36eecdaf394274dacfe15`，PR #67/#68；`Preview Build` run `44281162788` 于 2026-11-18T10:18:19Z 发布）。内容为 OwnBox/NetProxy 两路借鉴移植合并后的首个预览：核心稳定性与故障转移、订阅自动更新、智能分流、冷启动秒连、策略组混搭/锁定/优先级、停核可编辑与脱敏诊断包、按网络规则、路由规则指定策略组；APK `10162` / 模块 `2032`。其 Release/tag 已在 preview.3 发布后按单预览策略清理，下载链接不再可用。

### 更早预览

v3.0.17-preview.1、v3.0.16-preview.4、v3.0.16-preview.3、v3.0.16-preview.2、v3.0.16-preview.1、v3.0.15-preview.1–.4 与 v3.0.14-preview.1 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。
