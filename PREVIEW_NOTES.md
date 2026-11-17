# v3.0.17-preview.1 发行说明

**状态：已发布的稳定性预览版。**

本版修复上一版遗留的一处登录阻断：存在悬空出站引用（某条启用规则指向已删除的节点）时，「放弃未完成编辑」会反复失败，用户被锁在恢复标记上。正式稳定版 `v3.0.14`（`VERSION_CODE=2026`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 均未改变；预览元数据码升为 `PRE_VERSION_CODE=2031`，派生 APK `versionCode=10156`、Root 模块码 `2031`，均高于 preview.4 的 `10154` / `2030`。

## ✦ 与上一版对比

| 项目 | v3.0.16-preview.4 | v3.0.17-preview.1 |
|---|---|---|
| 内容 | 收口修复：组成员单一真相、订阅撞名不再断连、选中节点显式回落、恢复失败逃生口 | 修复悬空出站引用下的放弃编辑死循环，并修掉配置构建在非主线程创建 Toast 的失败 |
| APK `versionCode` | 10154 | 10156 |
| Root 模块码 | 2030 | 2031 |
| 源码与二进制 | tag、`targetCommitish`、APK 内 `version-control-info.textproto` 与 CI `APPROVED_SHA` 同为 `9283ccb` | 四者同为 `33391d1` |

URLTest 稳定性约束（检测间隔 ≥ 10 秒、空闲超时 ≥ 30 分钟、切换时不中断现有连接）、`default` 保真、候选应用的版本 CAS 与恢复校验链本版均未放宽。

## ✦ 本版更新

### 1. 悬空出站引用不再锁死「放弃未完成编辑」

当一条启用规则指向已删除的节点时，连接前的出站告警原本是正确的保护；但「放弃未完成编辑」在锁内重建模块时也会撞上同一条告警，异常被包成「需要恢复」并保留恢复标记，于是每次放弃都死在同一步——用户既连不上，也进不了组编辑。

现在放弃编辑用**同一次构建快照**计算出的告警替代这次重建的确认：放弃重建的正是编辑前那套本来就在跑的配置，同一条告警在这一步不再构成新的决定；构建与确认仍是同一次快照，跳过确认的告警不可能描述另一套配置。正常启动、重连与重载仍然照旧要求用户确认——放弃成功后再次连接依旧会弹出该告警。

失败方向不变：重建失败、状态未知/超时、revision 不符仍然保留恢复标记与恢复入口，本版没有放宽这条提交点。

### 2. 规则告警不再中断配置构建

同一状态下还压着第二层缺陷：构建配置时「指向不存在出站」的规则会被丢弃并弹出提示，而这个 Toast 是在配置构建所在的 IO 线程上创建的——Android 会直接抛 `NullPointerException: Can't toast on a thread that has not called Looper.prepare()`，把整个重建（包括用户点了「继续连接」的那次）打回失败。两处规则告警现在都改用主线程分发，提示文案与「丢弃该规则」的判定不变。

### 3. 版本与文档口径

`nb4a.properties` 预览线为 `3.0.17-preview.1` / `2031`；正式线 `3.0.14 / 2026` 与 sing-box pin 未变。当前版本文档一致性检查同步翻到「preview.1 已发布」。

## 📦 版本、资产与安装

- **运行要求**：Android 12+（API 31+）、arm64-v8a、Magisk / KernelSU / APatch 等 Root 环境。
- **版本对齐**：APK `versionName=3.0.17-preview.1` / `versionCode=10156`；Root 模块码 `2031`。瘦管理器和 Root 模块必须来自同一预览 Release、同版成套使用。
- **同版资产**：`wanBoxForAndroid-3.0.17-preview.1-arm64-v8a-release.apk`、`wanbox-root-module-arm64-v8a.zip`、`wanbox-root-module-with-manager-arm64-v8a.zip`、`SHA256SUMS`。
- **回退**：使用正式稳定版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 与配套 Root 模块（模块码 `2026`）；不要混用不同 Release 的 APK 和模块。

## 🛡️ 验证边界

源码基于 main `33391d1dfa851fce7998431eb4ab614407ffbb25`（PR #65「fix: v3.0.17-preview.1 discard deadlock on dangling outbound reference」，2026-11-17T11:39:26Z 合并），tag `v3.0.17-preview.1` 与该提交同源：tag 对象、Release `targetCommitish`、APK 内 `version-control-info.textproto` 与发布 job 的 `APPROVED_SHA` 四者同为 `33391d1`。

旧实现必红的证据：PR 分支上纯测试提交 `419fdc5` 的 `Verify Android Build` run `44064100226` 只红一项——`GroupDraftAbandonPersistenceTest.danglingOutboundRuleDoesNotDeadlockDiscard` 抛 `java.io.IOException: group_editor_recovery_required`；只修告警门、未修 Toast 的 `624fc8e`（run `44067193911`）在同一用例上抛 `NullPointerException: Can't toast on a thread that has not called Looper.prepare()`，这正是第二层缺陷的实证。

PR 验证 `Verify Android Build` run `44071339331`（head `aeb72c5`）全绿，instrumented 实测 debug（thin）79 项 0 失败 2 skip、release（R8 混淆 thin）79 项 0 失败 2 skip、internal 变体 2 项 0 失败，较 preview.4 的 78 项多出新增的那一条用例。发布 `Preview Build` run `44076008956` 四个 job 全部成功，发布后回读四件套（本地 `sha256sum -c SHA256SUMS` 三件 OK）：APK `6301506` B / `4d7fe5f5…f6cd48`、普通模块 `17956394` B / `82a3e48e…c21f1f`、合一模块 `23549099` B / `f16dff37…7fdc8e`、`SHA256SUMS` `333` B / `c9c4bd33…6a12ff8`；APK + 普通模块合计 `24257900` B（23.13 MiB），上限 `27262976` B，余量 `3005076` B，`size_budget --enforce-limit` PASS。发布 job 以 `prune_prereleases.py --keep-tag v3.0.17-preview.1` 清理了 preview.4 的 Release/tag。逐项数据见[版本对照](docs/current-version.zh-CN.md)。

本环境无 Android SDK / JDK，本地只跑了不依赖构建链的静态校验：Python 工具 124 项、`version_code.py --check-kotlin --check-app-tag`、版本文档一致性检查与 `git diff --check`；JVM 单测、instrumentation 与构建结果以 CI 为准。未执行 Magisk / KernelSU / APatch 真机矩阵：组编辑应用链、订阅更新后的组成员、放弃编辑与悬空出站告警的真机表现仍未验证，自动化结果不等同于真实 Root 设备验证。

---

## 历史归档

### v3.0.16-preview.4 已发布说明

[v3.0.16-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.16-preview.4) 已作为稳定性预览版发布（tag/commit `9283ccb`，PR #62；`Preview Build` run `44013891166` 于 2026-11-17T04:18:56Z 发布）。内容为 preview.3 的收口修复：策略组成员以节点归属为唯一真相、订阅节点与组重名时自动加后缀、选中节点移出主组时显式回落、恢复失败时可放弃未完成编辑、组设置页与编辑器统一锁定「中断现有连接」；APK `10154` / 模块 `2030`。该版暴露的「悬空出站引用下放弃编辑死循环」已由 preview.1 修复。资产快照见[版本对照](docs/current-version.zh-CN.md)；其 Release/tag 已在 preview.1 发布后按单预览策略清理，下载链接不再可用。

### v3.0.16-preview.3 已发布说明

[v3.0.16-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.16-preview.3) 已作为稳定性预览版发布（tag `fa6b95b`，首发 `Preview Build` run `43906481811`；资产于 2026-11-17 由 main `000d302` 的候选 run `43939217515` 经受审查流程替换）。内容为四批次汇总：图形化策略组编辑、策略组浏览/选择/测速、统一资源页与重名消歧、备份与迁移规范化（「兼容旧版」导出未开放：尚无经真实旧版读取器验证的写入器，入口禁用并明确说明）、模块退出诊断上屏、网关缓存深拷贝；APK `10148` / 模块 `2029`。未执行 Root 真机矩阵。资产快照见[版本对照](docs/current-version.zh-CN.md)；其 Release/tag 已按单预览策略清理。

### 更早预览

v3.0.14-preview.1、v3.0.15-preview.1–.3、v3.0.15-preview.4 与 v3.0.16-preview.1、v3.0.16-preview.2 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。
