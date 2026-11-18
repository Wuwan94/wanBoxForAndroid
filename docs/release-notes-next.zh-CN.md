# v3.0.17-preview.4 发行说明

**状态：已发布的稳定性预览版。**

本版基于 main `906a6ea3f48779cc96c7a4116cfd1faeb63aa354`（PR #72，`Verify Android Build` run `44372679147` 全绿后合并，2026-11-18T19:13:48Z），`Preview Build` run `44378329740` 于 2026-11-18T19:38:07Z 发布，发布 job 断言 `APPROVED_SHA=906a6ea3`。正式稳定版 `v3.0.14` / `2026` 与 sing-box pin `v1.15.0-alpha.13-reF1nd` 未变；预览元数据 `3.0.17-preview.4` / `2034`，派生 APK `versionCode=10174`、Root 模块码 `2034`（preview.3 为 `10168` / `2033`）。

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

同一 Release 的瘦管理器 APK 与 Root 模块必须成套安装。回退使用正式版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 与配套模块（模块码 `2026`）。PR #72 的 `Verify Android Build` run `44372679147` 全绿（插桩 debug/release 各 81 项 + 2 项定向复跑，0 失败）；发布 `Preview Build` run `44378329740` 四个 job 全部成功，四件套 `sha256sum -c` 与 digest 回读一致（APK `6363791` / 模块 `18131042` / 合一 `23774566` / `SHA256SUMS` `333` 字节，APK+模块 `24494833` 字节，余量 `2768143`，PASS）。未执行 Root 真机矩阵，自动化结果不等同于真实设备验证。

---

## 历史归档

v3.0.17-preview.3 的已发布说明：tag/commit `6adc5ac0`，PR #69，`Verify Android Build` run `44295650161`，`Preview Build` run `44306599880` 于 2026-11-18T13:08:01Z 发布；APK `10168` / 模块 `2033`，Release/tag 已在 preview.4 发布后（19:38:15Z）按单预览策略清理。

v3.0.17-preview.2 的已发布说明：tag/commit `49e96619`，`Preview Build` run `44281162788` 于 2026-11-18T10:18:19Z 发布；APK `10162` / 模块 `2032`，Release/tag 已在 preview.3 发布后按单预览策略清理。

v3.0.17-preview.1 的已发布说明：tag/commit `33391d1`，`Preview Build` run `44076008956` 于 2026-11-17T12:01:45Z 发布；内容为悬空出站引用下放弃编辑不再死循环与配置构建 Toast 改主线程分发，APK `10156` / 模块 `2031`；其 Release/tag 已在 preview.2 发布后按单预览策略清理。v3.0.16-preview.4 的已发布说明：tag/commit `9283ccb`，`Preview Build` run `44013891166` 于 2026-11-17T04:18:56Z 发布；内容为组成员单一真相、订阅撞名避让、选中节点显式回落、恢复失败逃生口与设置口径统一，APK `10154` / 模块 `2030`。v3.0.16-preview.3 的已发布说明：tag `fa6b95b`，首发 `Preview Build` run `43906481811`，资产由 main `000d302` 经受审查流程替换。v3.0.14-preview.1、v3.0.15-preview.1–.3、v3.0.15-preview.4 与 v3.0.16-preview.1、v3.0.16-preview.2 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](../CHANGELOG.md)。
