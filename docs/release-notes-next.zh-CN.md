# v3.0.18-preview.1 发行说明

**状态：已发布的稳定性预览版。**

[v3.0.18-preview.1](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.1) 以 `main` 的 `0a30baaf939a97b1941210609ce377e00d386157`（轻量 tag）发布，`Preview Build` run `44516333684` 四个作业全绿、2026-11-19T11:55:25Z 上架（发布 job 断言 `APPROVED_SHA=0a30baaf`）。正式线 `3.0.17 / 2035` 与 sing-box pin `v1.15.0-alpha.13-reF1nd` 未变；预览元数据 `3.0.18-preview.1 / 2036`，派生 APK `versionCode=10181`、Root 模块码 `2036`（preview.4 为 `10174 / 2034`）。发布后 11:55:35Z 由 `prune_prereleases.py` 清理 `v3.0.17-preview.4` 的 Release/tag，正式版 `v3.0.17` / `v3.0.14` / `v3.0.11` 保留。

## 与上一版对比

| 项目 | v3.0.17-preview.4 | v3.0.18-preview.1 |
|---|---|---|
| 内容 | 故障转移回切门、过期候选兜底、direct 有界重试、空白名单与 su 超时分离 | 层级状态按 TCP/UDP 拆分；App UI 对齐模块功能（自启/重启核心/配置回滚/模块版本） |
| APK `versionCode` | 10174 | 10181 |
| Root 模块码 | 2034 | 2036 |

URLTest 间隔 ≥10 秒、空闲 ≥30 分钟、切换不中断现有连接与「连续 3 轮健康 + 60 秒无失败」回切门均未放宽。

## 本版更新

1. **urltest 层级状态按网络拆分（PR #77，合并提交 `31b3b073`）**：`currentTier` / `tierGate` 拆成 `tierTCP` / `tierUDP`，各带独立生效层与独立 map 门；`effectiveTierLocked` / `canSwitchBackTo` 按网络取状态，未知网络不回切；`updateTierGateLocked` 按成员支持网络分别推进；`promoteFailover` 只动被拨网络；轮次收尾两侧独立同步。旧代码上「TCP 成员死亡把健康 UDP 拖到 T2」可复现，本版已修复，另有 11 条隔离回归。
2. **App UI 对齐模块功能（PR #78，合并提交 `6215b450`）**：恢复开机自启开关（写回 DataStore 并即时 `setAutoStart`）；新增「重启核心」（`restart`）与「回滚配置」（白名单补 `config rollback`）两行带确认弹窗；顶栏副标题跟随 `moduleVersionFlow`；模块侧零改动，文案中英双份。

## 升级与验证边界

同一 Release 的瘦管理器 APK 与 Root 模块必须成套安装；回退用正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17) 的 APK 与配套模块（模块码 `2035`）。PR #77 的 `Verify Android Build` run `44486772559`（含 `-race`）、PR #78 的 run `44506930043`（Python 125 项、`rootmodule` 28 项、文档门禁、插桩 81 项）全绿；首个 `Preview Build` run `44511891425` 红在正式版硬编码 `3.0.14` 断言，`0a30baa` 改为读 `nb4a.properties` 后发布成功（该提交为直推 main 的热修，未单独跑 `Verify Android Build`）。四件套：APK `6366243` / 模块 `18134172` / 合一 `23778516` / `SHA256SUMS` `333` 字节，APK+模块 `24500415` 字节（23.37 MiB）余量 `2762561` → PASS，`sha256sum -c` 三件 OK。已知边界：观测层仍按 tag 共享，嵌套组聚合 summary 与网络无关。未执行 Root 真机矩阵，自动化结果不等同于真机验证。

---

## 历史归档

### v3.0.17-preview.4 已发布说明

**状态：已作为稳定性预览版发布；Release/tag 已在 v3.0.18-preview.1 发布后清理。**

本版基于 main `906a6ea3f48779cc96c7a4116cfd1faeb63aa354`（PR #72，`Verify Android Build` run `44372679147` 全绿后合并，2026-11-18T19:13:48Z），`Preview Build` run `44378329740` 于 2026-11-18T19:38:07Z 发布，发布 job 断言 `APPROVED_SHA=906a6ea3`。正式稳定版 `v3.0.14` / `2026` 与 sing-box pin `v1.15.0-alpha.13-reF1nd` 未变；预览元数据 `3.0.17-preview.4` / `2034`，派生 APK `versionCode=10174`、Root 模块码 `2034`（preview.3 为 `10168` / `2033`）。Release/tag 与资产已在 `v3.0.18-preview.1` 发布后清理（run `44516333684`，11:55:35Z），下载链接现为 404。

## 与上一版对比

| 项目 | v3.0.17-preview.3 | v3.0.17-preview.4 |
|---|---|---|
| 内容 | 预算到期回切、诊断包 0644/属主/媒体扫描、八项 P2 与 Room 11 schema | 故障转移限定生效层、过期候选兜底、direct 有界重试、空白名单与 su 超时分离 |
| APK `versionCode` | 10168 | 10174 |
| Root 模块码 | 2033 | 2034 |

URLTest 间隔 ≥10 秒、空闲 ≥30 分钟、切换不中断连接等硬约束未放宽；`default` 保真与候选版本 CAS/恢复校验链不变。

## 本版更新

1. **层级语义**：故障转移候选只取当前生效层（全层无健康成员才退回全部）；`promoteFailover` 不再改写 `currentTier`，回切仍只由「连续 3 轮健康 + 60 秒无失败」的门决定。
2. **可用性**：过期候选保留为最后梯队，新鲜候选全败时仍能兜底换节点，不再出现「单节点抖动 = 这次连接直接失败」。
3. **direct 失败**：改为 15s → 1min → 5min 有界退避并长期保持 5 分钟一次，失败事件 5 分钟窗口内只记一条。
4. **分应用名单**：`PackageCache` 记录跨用户查询失败码并 30 秒重试；只有查询成功却无有效 UID 才拦启动，提示改本地化文案；查询失败退回只代理已解析部分。
5. **收口**：supervisor 不再吞 `readUnifiedPolicy` 错误；预算到期零观测轮次不再推进恢复门；`BalancerTierScheduler` 零引用符号标注 `@Deprecated` 并写明 Go 侧为唯一实现。

## 升级与验证边界

同一 Release 的瘦管理器 APK 与 Root 模块必须成套安装。回退使用当前正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17) 的 APK 与配套模块（模块码 `2035`）。PR #72 的 `Verify Android Build` run `44372679147` 全绿（插桩 debug/release 各 81 项 + 2 项定向复跑，0 失败）；发布 `Preview Build` run `44378329740` 四个 job 全部成功，四件套 `sha256sum -c` 与 digest 回读一致（APK `6363791` / 模块 `18131042` / 合一 `23774566` / `SHA256SUMS` `333` 字节，APK+模块 `24494833` 字节，余量 `2768143`，PASS）。未执行 Root 真机矩阵，自动化结果不等同于真实设备验证。

v3.0.17-preview.3 的已发布说明：tag/commit `6adc5ac0`，PR #69，`Verify Android Build` run `44295650161`，`Preview Build` run `44306599880` 于 2026-11-18T13:08:01Z 发布；APK `10168` / 模块 `2033`，Release/tag 已在 preview.4 发布后（19:38:15Z）按单预览策略清理。

v3.0.17-preview.2 的已发布说明：tag/commit `49e96619`，`Preview Build` run `44281162788` 于 2026-11-18T10:18:19Z 发布；APK `10162` / 模块 `2032`，Release/tag 已在 preview.3 发布后按单预览策略清理。

v3.0.17-preview.1 的已发布说明：tag/commit `33391d1`，`Preview Build` run `44076008956` 于 2026-11-17T12:01:45Z 发布；内容为悬空出站引用下放弃编辑不再死循环与配置构建 Toast 改主线程分发，APK `10156` / 模块 `2031`；其 Release/tag 已在 preview.2 发布后按单预览策略清理。v3.0.16-preview.4 的已发布说明：tag/commit `9283ccb`，`Preview Build` run `44013891166` 于 2026-11-17T04:18:56Z 发布；内容为组成员单一真相、订阅撞名避让、选中节点显式回落、恢复失败逃生口与设置口径统一，APK `10154` / 模块 `2030`。v3.0.16-preview.3 的已发布说明：tag `fa6b95b`，首发 `Preview Build` run `43906481811`，资产由 main `000d302` 经受审查流程替换。v3.0.14-preview.1、v3.0.15-preview.1–.3、v3.0.15-preview.4 与 v3.0.16-preview.1、v3.0.16-preview.2 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](../CHANGELOG.md)。
