# 发布、源码与版本对照

核对日期：2026-11-11。发布状态以 GitHub Release、tag 与实际下载资产为准；源码元数据本身不构成已发布资产的核验。

| 对象 | 版本与状态 | 版本码及来源 |
|---|---|---|
| 已发布正式版 | [v3.0.11](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.11)，非 prerelease；tag `03c6eb70854c797ee1e4069e378145be5ca56b0d` | APK / Root 模块 `10065 / 2013`（历史资产核验记录，归档语境） |
| 已发布预览版 | [v3.0.13-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.4)，prerelease；tag/commit `e7a6411e03c30ec4bcf4a8a00244362664d070a6` | APK `versionCode=10124` / Root 模块码 `2024`；由正式 `Preview Build` run `42962314460` 发布 |
| 发布基线 | [v3.0.13-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.4) 的 tag/commit `e7a6411e03c30ec4bcf4a8a00244362664d070a6` | PR #23 合并提交；固定发布基线，不等同于后续 main HEAD |

源码元数据正式线为 `VERSION_NAME=3.0.11`、`VERSION_CODE=2017`，不随预览版本改动；当前预览线为 `PRE_VERSION_NAME=3.0.13-preview.4`、`PRE_VERSION_CODE=2024`。共享向量计算 APK `versionCode=10124`。

preview.4 已通过 PR #21 与 PR #23 合并并由官方 `Preview Build` 发布。发布 tag 指向 `e7a6411e03c30ec4bcf4a8a00244362664d070a6`；前一预览 `.3` 的 Release/tag 已在新 Release、四件套及哈希核验成功后清理，稳定版 v3.0.11 保留。

## v3.0.13-preview.4 已发布资产快照

来源：[GitHub Release v3.0.13-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.4)，正式发布 run `42962314460`。以下 GitHub API SHA-256 digest 与下载文件逐字节匹配；公开 `SHA256SUMS` 清单校验通过。

| 文件 | 字节数 | GitHub SHA-256 digest |
|---|---:|---|
| `wanBoxForAndroid-3.0.13-preview.4-arm64-v8a-release.apk` | 6193350 | `94595768aadb2bcaa790485c986f4c75c08681cb0729a6094a9a9454282c3cfc` |
| `wanbox-root-module-arm64-v8a.zip` | 17744732 | `fea56ab37e639f76a7825b52f2497a0f5e0a6f09130fd75494dd12061be8e152` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23247408 | `264c09e42beb9c2d60fefc6889b6f55fc42af7fa5cd6051b2a2c61d1381d3f1f` |
| `SHA256SUMS` | 333 | `32f0e0e5ee85f90d6011ab29f0d6ed2a621673391fd47a9c5490efb285409f1d` |

APK 与普通 Root 模块合计 `23938082` 字节（22.83 MiB）；带管理器模块包为 22.17 MiB，均低于 26 MiB。两个 ZIP 完整性通过；带管理器 ZIP 内 APK 与独立 APK 字节完全一致。`apksigner` 验证 APK 签名通过，证书 SHA-256 为 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`，匹配仓库固定的正式发布证书。

## 历史归档：v3.0.12-preview.2 发布资产快照

> 该旧预览的 Release 与 tag 已在预览渠道清理中移除。下表只是当时 GitHub Release API 返回的历史索引快照，**不是当前下载入口**，也不代替本轮下载、解包、签名与 SHA256SUMS 校验。历史数值保持原样，不改名成本轮结果。

| 文件 | 字节数 | GitHub SHA-256 digest |
|---|---:|---|
| `wanBoxForAndroid-3.0.12-preview.2-arm64-v8a-release.apk` | 6173441 | `f0339ae504cc5383e982360b60cd7dac945c25ed7985f6cf80053dacadb8672c` |
| `wanbox-root-module-arm64-v8a.zip` | 17743049 | `63d12d604d60f2fcb2fe28b06c090c1295664cf782dac6536bbdee8d6f16067f` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23228024 | `a8e148a905ffde1ad3d398fa6fb15cdc6fc13de56dddfe23d55e4eae5a1a7337` |
| `SHA256SUMS` | 333 | `655708bebff47efc67d1f438688d86def448dec195d7743df4e458ff117a4a9c` |

## 资产与兼容信息

历史机器可读记录 [preview3-version-evidence.json](preview3-version-evidence.json) 对应 v3.0.11 正式版与 v3.0.11-preview.2，**不是 v3.0.13-preview.3 或本轮 preview.4 的核验结果**。其中包括 Release digest、SHA256SUMS、ZIP 清单、带管理器模块内 APK 与外部 APK 的字节一致性、`aapt` 身份与 `apksigner` 验证。

历史共同包名为 `com.lit008834.pixel.wanboxforandroid`，证书 SHA-256 为 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`；这是历史记录，不代表本轮 preview.4 资产已验签。

Android 12+（minSdk 31）、arm64-v8a、Root；瘦管理器和 Root 模块必须使用同一发布页的同版资产。下载通过同一 Release 的 SHA256SUMS 后才可提示安装，禁止静默安装；资产不全时明确提示并引导至发布页，不拼凑不同 Release 的文件。

## 发布来源与边界

当前唯一已发布预览为 [v3.0.13-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.4)，tag/commit 为 `e7a6411e03c30ec4bcf4a8a00244362664d070a6`；preview.3 的 Release/tag 在 .4 完整核验后清理。 [PREVIEW_NOTES.md](../PREVIEW_NOTES.md) 与[说明快照](release-notes-next.zh-CN.md)描述本版已发布状态；更早版本内容保留在历史归档，不作为本轮测试结果。

本版由 PR #21 与 PR #23 的合并提交触发正式 `Preview Build`；发布 run 在清理旧预览前核验了精确获批 SHA、Release/tag 与资产。发布后又回读下载四件套，逐项比对 GitHub API digest、`SHA256SUMS`、APK 证书和 ZIP 完整性。

[发布清单](release-checklist.zh-CN.md) 保留分阶段门槛。当前未做 Magisk / KernelSU / APatch 真机回归；只有真实 Root 设备矩阵签核后才能考虑正式版。任何自动化结果都不代替真机验证。

稳定版 v3.0.11 的 Release 与 tag 始终保留；本轮 preview.3 已按“只保留一个预览”偏好清理。后续发布仍须使用获批精确提交及同次核验的四件套资产，并在清理旧预览前完成回读验证。

正式版历史说明见 [RELEASE_NOTES.md](../RELEASE_NOTES.md)。
