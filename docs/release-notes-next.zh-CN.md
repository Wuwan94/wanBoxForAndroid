# v3.0.17-preview.1 发行说明

**状态：已发布的稳定性预览版。**

本版是预览.4 复审遗留 P1 的收口修复，面向已 Root 的 Android 12+ arm64 设备：存在悬空出站引用时「放弃未完成编辑」不再死循环，配置构建也不再因非主线程 Toast 失败。稳定版 `v3.0.14` / 正式元数据 `2026` 与 sing-box pin `v1.15.0-alpha.13-reF1nd` 均未改变；预览名为 `3.0.17-preview.1`，APK `versionCode=10156`，Root 模块码 `2031`（preview.4 为 `10154` / `2030`）。

## 与上一版对比

| 项目 | v3.0.16-preview.4 | v3.0.17-preview.1 |
|---|---|---|
| 内容 | 收口修复：组成员单一真相、订阅撞名不再断连、选中节点显式回落、恢复失败逃生口 | 修复悬空出站引用下的放弃编辑死循环与配置构建 Toast 失败 |
| APK `versionCode` | 10154 | 10156 |
| Root 模块码 | 2030 | 2031 |

URLTest 稳定性约束、`default` 保真、候选应用的版本 CAS 与恢复校验链本版均未放宽。

## 本版更新

1. **悬空出站引用不再锁死放弃编辑**：一条启用规则指向已删除节点时，连接前的出站告警属于正常保护，但「放弃未完成编辑」在锁内重建时也会撞上同一条告警，异常被包成「需要恢复」且标记不清，于是每次放弃都死在同一步。现在放弃编辑以**同一次构建快照**算出的告警替代这次重建的确认——重建的正是编辑前那套在跑的配置，快照与告警一一对应；正常启动、重连与重载仍照旧要求用户确认。
2. **规则告警不再中断构建**：同一状态下「指向不存在出站」的规则会被丢弃并弹提示，而该 Toast 原本在配置构建的 IO 线程上创建，Android 直接抛 `NullPointerException`，把包括用户确认后的重建在内整条路径打回失败；两处规则告警改为主线程分发，文案与判定不变。
3. **提交点未放宽**：重建失败、状态未知或超时、revision 不符依旧保留恢复标记与恢复入口；只有确认成功才清标记。
4. **版本对齐**：预览线为 `PRE_VERSION_NAME=3.0.17-preview.1`、`PRE_VERSION_CODE=2031`，正式线 `3.0.14 / 2026` 与 `SINGBOX_VERSION` 未变。

## 升级与验证边界

同一 Release 的瘦管理器 APK 与 Root 模块必须成套安装。回退时使用正式版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 和配套模块。发布证据：PR #65（合并提交/tag `33391d1dfa851fce7998431eb4ab614407ffbb25`）的 `Verify Android Build` run `44071339331` 全绿（instrumented debug / release 各 79 项 0 失败 2 skip，internal 2 项 0 失败），旧实现必红证据为 run `44064100226`（`419fdc5`）与 run `44067193911`（`624fc8e`，Toast 缺陷）；`Preview Build` run `44076008956` 四个 job 全部成功并发布，tag 对象、`targetCommitish`、APK 内 `version-control-info.textproto` 与 `APPROVED_SHA` 四者同为 `33391d1`。四件套回读数据与体积见[版本对照](current-version.zh-CN.md)。未执行 Magisk / KernelSU / APatch 真机矩阵，自动化结果不等同于真实 Root 设备验证。

---

## 历史归档

v3.0.16-preview.4 的已发布说明：tag/commit `9283ccb`，`Preview Build` run `44013891166` 于 2026-11-17T04:18:56Z 发布；内容为组成员单一真相、订阅撞名避让、选中节点显式回落、恢复失败逃生口与设置口径统一，APK `10154` / 模块 `2030`；其 Release/tag 已在 preview.1 发布后按单预览策略清理。v3.0.16-preview.3 的已发布说明：tag `fa6b95b`，首发 `Preview Build` run `43906481811`，资产由 main `000d302` 经受审查流程替换。v3.0.14-preview.1、v3.0.15-preview.1–.3、v3.0.15-preview.4 与 v3.0.16-preview.1、v3.0.16-preview.2 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](../CHANGELOG.md)。
