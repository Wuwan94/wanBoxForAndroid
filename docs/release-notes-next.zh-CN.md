# v3.0.18-preview.4 发行说明

**状态：已发布的稳定性预览版。**

本版收口 `v3.0.18-preview.3` 发布后合入的三批复审修复：PR #84（合并提交 `00cfeedddc07249cccd8a8344eb1bf7df97d75dc`，5 个文件 +71/−3）、PR #86（合并提交 `2add76212e54c54b6d4dda02b1077ab373352e24`，12 个文件 +535/−147）、PR #87（合并提交 `f0f80b92d597a334440c25171648b19b76573b59`，11 个文件 +534/−45）。正式线 `3.0.17 / 2035`（APK `10175`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 均未变；预览元数据为 `3.0.18-preview.4 / 2039`，派生 APK `versionCode=10199`、Root 模块码 `2039`（已发布 preview.3 为 `10193 / 2038`）。本版以 `main` 的 `a8cec2fa6676ec0c9a5fb3b7186a9c84e3e44893`（升版 PR #88 合并提交，轻量 tag）发布：`Preview Build` run `45175762344` 四作业全绿、2026-11-23T12:55:32Z 上架（发布 job 断言 `APPROVED_SHA=a8cec2fa` 并回读 tag），12:55:40Z 由 `prune_prereleases.py` 清理 `v3.0.18-preview.3`，正式版 `v3.0.17` / `v3.0.14` / `v3.0.11` 保留。四件套 APK `6381547` / 普通模块 `18151369` / 合一包 `23807498` / `SHA256SUMS` `333` 字节，APK+模块 `24532916` 字节（23.40 MiB）余量 `2730060` → PASS。

## ✦ 与上一版对比

| 项目 | v3.0.18-preview.3 | v3.0.18-preview.4 |
|---|---|---|
| 内容 | UDP 回显端点设置入口；UDP-only 上层成员凭回显进候选；首次应用配置前回读自启设置 | UDP 回包按来源校验、截止判定先认取消；门代次变化仍删失败证据；自启结果可重放且只消费一次、锁外做 Room/IPC；UDP 迟到回包用例 |
| APK `versionCode` | 10193 | 10199 |
| Root 模块码 | 2038 | 2039 |

URLTest 间隔 ≥10 秒、空闲 ≥30 分钟、切换不中断现有连接与「连续 3 轮健康 + 60 秒无失败」回切门均未放宽；证据不足时停在当前层级，不回切。

## ✦ 本版更新

1. **UDP 回包按来源校验**：`ReadFrom` 的来源必须解析成 `IP:port` 且与配置目标一致；来源不可核对记 `unverified`（中性），来源不一致或内容不符记 `rejected`（清零并盖失败戳）。截止判定抽成 `udpProbeContextOutcome`——父上下文取消优先记 `canceled`、单探针超时记 `unverified`，并在 `ListenPacket` / `WriteTo` / `ReadFrom` 后各复核一次，堵住「timer 尚未触发」与「回包与 deadline 同时就绪」两个窗口。新增 `udp_probe_source_test.go` 5 条与 `TestUDPProbeDeadlineClassification`。
2. **门代次轮内变化仍删除失败证据**：代次快照只拦「成功观测授予回切资格」，不拦失败删除；已 `rejected` 的成员在本轮收尾一律清掉其 UDP 证据。
3. **导出/一次性测速排除探测端点可测化**：判定抽成 `UrlTestUdpProbe` 的纯函数，导出配置与一次性测速共用同一份规则；`ConfigBuilderLoadBalanceTest` 补四种组合的 JVM 回归。
4. **自启结果可重放、只呈现一次**：`AutoStartSync` 用 `MutableStateFlow` 发布 `Idle` / `Pending` / `Finished`（不持有 View 或回调引用），设置页以 `repeatOnLifecycle(STARTED)` 收集；`AutoStartUiGate.accepts(...)` 把「能不能呈现」做成纯函数并补 8 条 JVM 用例；`consume(id)` 让结果呈现一次后回 `Idle`，只消费匹配 id 的结果；失败在页面有效时 Toast 提示一次，摘要回到当场回读的真相。
5. **锁粒度收窄与异常兜底**：登记锁只保护 `sequence` 与内存态，Room 读、Room 写与 `setComponentEnabledSetting` 的 IPC 移到锁外，`StateFlow` 用原子更新发布；`settle()` 把落库与结果发布纳入 `try/catch`，落库异常发布可见失败并保持原值；应用级作用域补 `CoroutineExceptionHandler`。
6. **UDP 迟到合法回包与哨兵语义**：`udpProbeContextOutcome` 的「未停止」返回值由 `UDPProbeSkipped` 改为零值 `UDPProbeUnobserved`；新增 `udp_probe_late_reply_test.go` 三条行为级用例（`TestUDPProbeLateValidReplyIsNotVerified`、`TestUDPProbeReplyAfterCallerCancellationIsCanceled`、`TestUDPLateValidReplyNeverAdvancesTheGate`）；spec 给来源校验写明效力边界——early conn / mux / UoT / XUDP / packetaddr 的来源只证明内容回显，不得作身份认证证据。

## ✦ 升级与验证边界

同一 Release 的瘦管理器 APK 与 Root 模块必须成套安装；回退用正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17) 的 APK 与配套模块（模块码 `2035`）。升级方向：APK `10193 → 10199`、模块 `2038 → 2039`，严格递增且同签名，可直接覆盖安装。

PR 门禁：PR #84 run `44898283085`（head `c6c6f038`）、PR #86 run `45152559766`（head `d6426d10`）、PR #87 run `45165265102`（head `0c1cbba`）、升版 PR #88 run `45170090295`（head `a14713f`）全部全绿；PR #87 首跑 `45162179175` 红在阻塞写库用例返回非 Unit 的 `InvalidTestClassError`，已用 `runBlocking<Unit>` 修掉。发布门禁：`Preview Build` run `45175762344` 四作业全绿，发布 job 断言 `APPROVED_SHA=a8cec2fa` 并回读 tag；发布后本地重下四件套 `sha256sum -c` 三件 OK、三个 ZIP/APK `testzip` 无损坏，`version-control-info.textproto` 的 `revision` 与 tag 对象、`targetCommitish`、`APPROVED_SHA` 四处同为 `a8cec2fa6676ec0c9a5fb3b7186a9c84e3e44893`；签名证书仍为 `80ecee1c…21f6`。新增回归：`udp_probe_source_test.go` 5 条、`udp_probe_deadline_test.go` 1 条、`udp_probe_late_reply_test.go` 3 条、`udp_probe_evidence_test.go` 14 条；`AutoStartSyncTest` 20 条、`AutoStartSyncUiGateTest` 8 条。

未执行 Root 真机矩阵（CI 自标 `runtime_regression: not_verified`）；UDP 探测为本机可控回显端点，非真实代理服务端往返；真机 `boot.json` 权限与开机行为未验证。自动化结果不等同于真机验证，本版不晋升正式版。

## 历史归档

### v3.0.18-preview.3 已发布说明

[v3.0.18-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.3) 已作为稳定性预览版发布：main `8881dd1c59481b3d23ab3766fb21e3b651fa1d65`（PR #83 合并提交，轻量 tag），`Preview Build` run `44882104257` 四作业全绿、2026-11-21T08:13:01Z 上架，08:13:09Z 清理 `v3.0.18-preview.2`；预览元数据 `2038`，APK `10193` / 模块 `2038`。内容为「设置 → 连接与核心 → UDP 回显端点」自管端点落地、各层子组独立 UDP 回显验证、UDP-only 上层成员凭回显进候选、首次应用配置前回读自启设置、自启连点按最后一次选择落地。四件套：APK `6374839` / 模块 `18149655` / 合一包 `23797772` / `SHA256SUMS` `333` 字节，APK+模块 `24524494` 字节（23.39 MiB）→ PASS。其 Release/tag 已在 preview.4 发布后（2026-11-23T12:55:40Z）按单预览策略清理。

### v3.0.18-preview.2 已发布说明

[v3.0.18-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.2) 已作为稳定性预览版发布：main `e7f1f9366316974d8318e8708dcaaa669bc896a0`（升版 PR #81 合并提交），`Preview Build` run `44728368529` 四作业全绿、2026-11-20T11:51:54Z 上架；预览元数据 `2037`，APK `10187` / 模块 `2037`。内容为收口 preview.1 六项缺陷（PR #80）：观测按（成员, 网络）分账、UDP 只认真实回显、降级作废旧回切资格、组关闭停拨备用、自启下发→回读→落库闭环、`boot.json` 三态。其 Release/tag 已在 preview.3 发布后（2026-11-21T08:13:09Z）清理。

### v3.0.18-preview.1 已发布说明

[v3.0.18-preview.1](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.1) 已作为稳定性预览版发布：main `0a30baaf939a97b1941210609ce377e00d386157`（轻量 tag），`Preview Build` run `44516333684` 四作业全绿、2026-11-19T11:55:25Z 上架；预览元数据 `2036`，APK `10181` / 模块 `2036`。内容为 PR #77（层级状态按 TCP/UDP 拆分）与 PR #78（UI 对齐模块功能）。已知边界：一次探测的观测仍按 tag 共享、嵌套组 summary 与网络无关。本版 Release 已于 11:55:35Z 在 preview.2 发布时由 `prune_prereleases.py` 清理（现为 404）。

### v3.0.17-preview.4 已发布说明

基于 main `906a6ea3f48779cc96c7a4116cfd1faeb63aa354`（PR #72，`Verify Android Build` run `44372679147`），`Preview Build` run `44378329740` 于 2026-11-18T19:38:07Z 发布；APK `10174` / 模块 `2034`。内容为故障转移限定生效层、过期候选兜底、direct 有界重试、`su` 超时与空白名单分离，另收口三项。Release/tag 已在 `v3.0.18-preview.1` 发布后清理（11:55:35Z），下载链接现为 404。

v3.0.17-preview.3 的已发布说明：tag/commit `6adc5ac0`，PR #69，`Verify Android Build` run `44295650161`，`Preview Build` run `44306599880` 于 2026-11-18T13:08:01Z 发布；APK `10168` / 模块 `2033`，Release/tag 已在 preview.4 发布后（19:38:15Z）按单预览策略清理。

v3.0.17-preview.2 的已发布说明：tag/commit `49e96619`，`Preview Build` run `44281162788` 于 2026-11-18T10:18:19Z 发布；APK `10162` / 模块 `2032`，Release/tag 已在 preview.3 发布后按单预览策略清理。

v3.0.17-preview.1 的已发布说明：tag/commit `33391d1`，`Preview Build` run `44076008956` 于 2026-11-17T12:01:45Z 发布；内容为悬空出站引用下放弃编辑不再死循环与配置构建 Toast 改主线程分发，APK `10156` / 模块 `2031`；其 Release/tag 已在 preview.2 发布后按单预览策略清理。v3.0.16-preview.4 的已发布说明：tag/commit `9283ccb`，`Preview Build` run `44013891166` 于 2026-11-17T04:18:56Z 发布；内容为组成员单一真相、订阅撞名避让、选中节点显式回落、恢复失败逃生口与设置口径统一，APK `10154` / 模块 `2030`。v3.0.16-preview.3 的已发布说明：tag `fa6b95b`，首发 `Preview Build` run `43906481811`，资产由 main `000d302` 经受审查流程替换。v3.0.14-preview.1、v3.0.15-preview.1–.4 与 v3.0.16-preview.1、v3.0.16-preview.2 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](../CHANGELOG.md)。
