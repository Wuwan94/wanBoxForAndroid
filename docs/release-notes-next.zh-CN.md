# v3.0.17-preview.3 发行说明

**状态：未发布的稳定性预览版候选。**

本候选基于 main `c0ecbf78a9211ef1198ac2df8c95db1708578b8f`（preview.2 发布后仅文档同步）。正式稳定版 `v3.0.14` / `2026` 与 sing-box pin `v1.15.0-alpha.13-reF1nd` 未变；预览元数据 `3.0.17-preview.3` / `2033`，派生 APK `versionCode=10168`、Root 模块码 `2033`（preview.2 为 `10162` / `2032`）。

## 与上一版对比

| 项目 | v3.0.17-preview.2 | v3.0.17-preview.3（候选） |
|---|---|---|
| 内容 | 两路借鉴移植合集首个预览 | URLTest 预算到期回切、诊断包 0644/属主/媒体扫描、八项 P2 与 Room 11 schema |
| APK `versionCode` | 10162 | 10168 |
| Root 模块码 | 2032 | 2033 |

URLTest 间隔 ≥10 秒、空闲 ≥30 分钟、切换不中断连接等硬约束未放宽；`default` 保真与候选版本 CAS/恢复校验链不变。

## 本版更新

1. **URLTest 层级回切**：5 秒整轮预算到期不再跳过轮次收尾；本轮有结果或新鲜历史时仍推进恢复门，连续 3 轮 + 60 秒后可回切高层。
2. **诊断包可读取**：最终文件 0644，按 Download 目录属主 `Chown`，App 侧导出后调用媒体扫描；脱敏与 Fail-Closed 校验不变。
3. **P2 收口**：补 Room 11 schema；非法网络规则记录限频事件；direct 失败只在状态变化时重试；`su` 5 秒超时强制结束；空白名单显式报错；Root 测速会话互斥；故障转移按新鲜历史排序并提升选择；订阅日志仅记 ID；清理重复的 Go 死代码并对齐注释。

## 升级与验证边界

同一 Release 的瘦管理器 APK 与 Root 模块必须成套安装。回退使用正式版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 与配套模块（模块码 `2026`）。RED/GREEN 证据、`go test`、Python 工具与 `Verify Android Build` 结果以 PR 和候选 `Preview Build` 实际结果为准；未执行 Root 真机矩阵，自动化结果不等同于真实设备验证。

---

## 历史归档

v3.0.17-preview.1 的已发布说明：tag/commit `33391d1`，`Preview Build` run `44076008956` 于 2026-11-17T12:01:45Z 发布；内容为悬空出站引用下放弃编辑不再死循环与配置构建 Toast 改主线程分发，APK `10156` / 模块 `2031`；其 Release/tag 已在 preview.2 发布后按单预览策略清理。v3.0.16-preview.4 的已发布说明：tag/commit `9283ccb`，`Preview Build` run `44013891166` 于 2026-11-17T04:18:56Z 发布；内容为组成员单一真相、订阅撞名避让、选中节点显式回落、恢复失败逃生口与设置口径统一，APK `10154` / 模块 `2030`。v3.0.16-preview.3 的已发布说明：tag `fa6b95b`，首发 `Preview Build` run `43906481811`，资产由 main `000d302` 经受审查流程替换。v3.0.14-preview.1、v3.0.15-preview.1–.3、v3.0.15-preview.4 与 v3.0.16-preview.1、v3.0.16-preview.2 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](../CHANGELOG.md)。
