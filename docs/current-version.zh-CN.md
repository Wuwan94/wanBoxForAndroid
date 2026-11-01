# 发布与源码版本对照

核对时间：2026-11-01（preview.31 发布准备阶段）。本文区分当时已发布的 Release、默认分支源码和待发布候选；版本字段或 PR 中的说明本身都不能证明 Release 已公开。

## 核对时的 Release 与源码

| 对象 | 核对结果 | 边界 |
|---|---|---|
| 稳定版 Release | GitHub Releases 列表中没有已发布的稳定版；旧 README 中的 `v3.0.7` Release 与 tag 当前均不存在 | 不把 `VERSION_NAME=3.0.9` 或历史 changelog 条目当作已发布稳定版 |
| 最新已发布预览 | [v3.0.10-preview.30](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.10-preview.30)，`prerelease=true`，发布于 2026-11-01 | 这是核对时实际可下载的最新预览，不是正式版 |
| preview.30 发布源码 | tag 指向 `9b9cb9b57cbabe667fc6855b411c6b79f998eccd` | tag 与后来合入 UI 改动的 `main` 不完全相同 |
| 核对时的 `main` | `024bd8cb6151a2e6eaa19ef11d96e4819ccf60bf`，已包含 PR #5 首页改版 | 当时 `nb4a.properties` 仍为 preview.30 元数据；源码合入不等于发布 |
| preview.31 候选 | 独立发布分支 `chore/preview31-release`，拟用 `PRE_VERSION_NAME=3.0.10-preview.31`、`PRE_VERSION_CODE=2009` | 在用户批准并由官方 Preview Build 成功发布之前，不是已发布 Release |

## preview.31 候选的兼容信息

本次改动只重排 Android 管理器首页与主题资源，不改数据库、备份格式、Root 模块协议或代理配置。候选发行说明计划复用 preview.30 的包名和官方签名；构建工作流会校验签名，preview.30 用户按同一签名版本升级。发布时必须以 preview.31 目标 tag 的实际 Release 正文、APK、Root 模块和校验和文件为准。

## 旧预览版边界

更早的预览 tag 可能来自不同源码布局或签名阶段，不能用它们的安装步骤、版本代码或兼容结论替代目标版本说明。尤其是旧预览的管理器签名迁移警告不应套用到当前 `main` 上基于 preview.30 的候选；安装和升级前请核对同一发布页中的 APK 与模块。
