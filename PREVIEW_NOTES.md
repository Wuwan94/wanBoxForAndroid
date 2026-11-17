# v3.0.17-preview.1 发行说明（候选）

**状态：未发布的稳定性预览版候选。**

本版修复上一版遗留的一处登录阻断：存在悬空出站引用（某条启用规则指向已删除的节点）时，「放弃未完成编辑」会反复失败，用户被锁在恢复标记上。正式稳定版 `v3.0.14`（`VERSION_CODE=2026`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 均未改变；预览元数据码升为 `PRE_VERSION_CODE=2031`，派生 APK `versionCode=10156`、Root 模块码 `2031`，均高于 preview.4 的 `10154` / `2030`，已安装 preview.4 的用户可同时收到 APK 与模块更新。

## ✦ 与上一版对比

| 项目 | v3.0.16-preview.4 | v3.0.17-preview.1（候选） |
|---|---|---|
| 内容 | 收口修复：组成员单一真相、订阅撞名不再断连、选中节点显式回落、恢复失败逃生口 | 修复悬空出站引用下的放弃编辑死循环；其余行为不变 |
| APK `versionCode` | 10154 | 10156 |
| Root 模块码 | 2030 | 2031 |

URLTest 稳定性约束（检测间隔 ≥ 10 秒、空闲超时 ≥ 30 分钟、切换时不中断现有连接）、`default` 保真、候选应用的版本 CAS 与恢复校验链本版均未放宽。

## ✦ 本版更新

### 1. 悬空出站引用不再锁死「放弃未完成编辑」

当一条启用规则指向已删除的节点时，连接前的出站告警原本是正确的保护；但「放弃未完成编辑」在锁内重建模块时也会撞上同一条告警，异常被包成「需要恢复」并保留恢复标记，于是每次放弃都死在同一步——用户既连不上，也进不了组编辑。

现在放弃编辑用**同一次构建快照**计算出的告警替代这次重建的确认：放弃重建的正是编辑前那套本来就在跑的配置，同一条告警在这一步不再构成新的决定；构建与确认仍是同一次快照，跳过确认的告警不可能描述另一套配置。正常启动、重连与重载仍然照旧要求用户确认——放弃成功后再次连接依旧会弹出该告警。

失败方向不变：重建失败、状态未知/超时、revision 不符仍然保留恢复标记与恢复入口，本版没有放宽这条提交点。

### 2. 版本与文档口径

`nb4a.properties` 预览线升为 `3.0.17-preview.1` / `2031`；正式线 `3.0.14 / 2026` 与 sing-box pin 未变。当前版本文档一致性检查同步改为「已发布 preview.4 + 未发布 preview.1 候选」的双态口径。

## 📦 版本、资产与安装

- **运行要求**：Android 12+（API 31+）、arm64-v8a、Magisk / KernelSU / APatch 等 Root 环境。
- **版本对齐**：APK `versionName=3.0.17-preview.1` / `versionCode=10156`；Root 模块码 `2031`。瘦管理器和 Root 模块必须来自同一预览 Release、同版成套使用。
- **同版资产**：`wanBoxForAndroid-3.0.17-preview.1-arm64-v8a-release.apk`、`wanbox-root-module-arm64-v8a.zip`、`wanbox-root-module-with-manager-arm64-v8a.zip`、`SHA256SUMS`。
- **回退**：使用正式稳定版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 与配套 Root 模块（模块码 `2026`）；不要混用不同 Release 的 APK 和模块。

## 🛡️ 验证边界

本候选基于 main `d0061b0c125fe5b5af70f789e88bcafaf33517e0`（含 preview.4 发布后的 PR #64 `8627cf3` 与文档回填 PR #63），在 preview.4（`9283ccb`）之后新增「悬空出站引用不锁死放弃编辑」一个行为修复与本次升版，不含其它待发布改动。本环境无 Android SDK 与 JDK，**只跑了不依赖构建链的静态校验**：`python3 -m unittest discover -s tools -p 'test_*.py'` 124 项、`version_code.py --channel preview`（`3.0.17-preview.1` / `2031` / `10156` / `2031`）与 `--enforce`（基线 preview.4 的 `10154` / `2030`）、`--check-kotlin --check-app-tag`、`check_current_version_docs.py`、`git diff --check`。JVM 单测、instrumented 测试与真实构建结果以 PR 的 `Verify Android Build` 与本候选的 `Preview Build` 实际结果为准；旧实现必红的证据（同一用例在修复前失败）记录在 PR 正文与其 CI run 中，发布后由文档同步回填到[版本对照](docs/current-version.zh-CN.md)。

新增仪器化用例 `GroupDraftAbandonPersistenceTest.danglingOutboundRuleDoesNotDeadlockDiscard` 使用真 Room、真恢复标记与真 `abandonGroupDraft()`，只伪造外部 Root CLI：先断言夹具确实处于被告警状态，再断言放弃成功、恢复标记被清除、重建确实发出 `config apply`，且随后的正常路径**仍然**要求该告警。未执行 Magisk / KernelSU / APatch 真机矩阵，自动化结果不等同于真实 Root 设备验证。

---

## 历史归档

### v3.0.16-preview.4 已发布说明

[v3.0.16-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.16-preview.4) 已作为稳定性预览版发布（tag/commit `9283ccb`，PR #62；`Preview Build` run `44013891166` 于 2026-11-17T04:18:56Z 发布，tag、`targetCommitish`、APK 内 `version-control-info.textproto` 与 `APPROVED_SHA` 四者同为 `9283ccb`）。内容为 preview.3 的收口修复：策略组成员以节点归属为唯一真相、订阅节点与组重名时自动加后缀、选中节点移出主组时显式回落、恢复失败时可放弃未完成编辑、组设置页与编辑器统一锁定「中断现有连接」；APK `10154` / 模块 `2030`。该版发布后暴露的「悬空出站引用下放弃编辑死循环」已由本候选修复。资产快照见[版本对照](docs/current-version.zh-CN.md)。

### v3.0.16-preview.3 已发布说明

[v3.0.16-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.16-preview.3) 已作为稳定性预览版发布（tag `fa6b95b`，首发 `Preview Build` run `43906481811`；资产于 2026-11-17 由 main `000d302` 的候选 run `43939217515` 经受审查流程替换）。内容为四批次汇总：图形化策略组编辑、策略组浏览/选择/测速、统一资源页与重名消歧、备份与迁移规范化（「兼容旧版」导出未开放：尚无经真实旧版读取器验证的写入器，入口禁用并明确说明）、模块退出诊断上屏、网关缓存深拷贝；APK `10148` / 模块 `2029`。未执行 Root 真机矩阵。资产快照见[版本对照](docs/current-version.zh-CN.md)。preview.4 发布后，preview.3 的 Release/tag 已按单预览策略清理，上述 tag 与下载链接不再可用。

### 更早预览

v3.0.14-preview.1、v3.0.15-preview.1–.3、v3.0.15-preview.4 与 v3.0.16-preview.1、v3.0.16-preview.2 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。
