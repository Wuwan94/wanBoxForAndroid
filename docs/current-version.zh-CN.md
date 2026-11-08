# 发布、源码与版本对照

核对日期：2026-11-08。已发布状态以 GitHub Release、tag 和实际下载资产为准；下一版本目标不等于已发布版本。

| 对象 | 版本与状态 | 版本码及来源 |
|---|---|---|
| 已发布正式版 | [v3.0.11](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.11)，非 prerelease；tag `03c6eb70854c797ee1e4069e378145be5ca56b0d` | APK / Root 模块 `10065 / 2013`；tag 的 `nb4a.properties` 与历史资产核验记录一致 |
| 已发布预览版 | [v3.0.12-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.12-preview.3)，prerelease；tag `28f797d23394336694e7648f84b531826654c95b` | Release 说明和 tag 元数据：APK / Root 模块 `10098 / 2019` |
| 当前源码基线 | release/preview4-candidate 复审修复源码（基于 main） | `VERSION_NAME=3.0.11`、`VERSION_CODE=2017`；`PRE_VERSION_NAME=3.0.12-preview.4`、`PRE_VERSION_CODE=2020`（APK10104）。当前源码正式线元数据不是已发布 v3.0.11 资产的版本码 |
| 本轮目标 | `v3.0.12-preview.4` 复审修复版，**未发布** | 已按既有规律顺延 2020/10104；候选资产仍待 CI 完整验证和签名/哈希验收 |

## preview.2 发布资产快照

以下为 GitHub Release API 返回的资产名称、字节数与 digest；此处是发布索引快照，**不代替本轮下载、解包、签名与 SHA256SUMS 校验**。

| 文件 | 字节数 | GitHub SHA-256 digest |
|---|---:|---|
| `wanBoxForAndroid-3.0.12-preview.2-arm64-v8a-release.apk` | 6173441 | `f0339ae504cc5383e982360b60cd7dac945c25ed7985f6cf80053dacadb8672c` |
| `wanbox-root-module-arm64-v8a.zip` | 17743049 | `63d12d604d60f2fcb2fe28b06c090c1295664cf782dac6536bbdee8d6f16067f` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23228024 | `a8e148a905ffde1ad3d398fa6fb15cdc6fc13de56dddfe23d55e4eae5a1a7337` |
| `SHA256SUMS` | 333 | `655708bebff47efc67d1f438688d86def448dec195d7743df4e458ff117a4a9c` |

## 资产与兼容信息

历史机器可读记录 [preview3-version-evidence.json](preview3-version-evidence.json) 对应 v3.0.11 正式版与 v3.0.11-preview.2，**不是 v3.0.12-preview.2 或本轮 preview.3 的核验结果**。其中包括 Release digest、SHA256SUMS、ZIP 清单、带管理器模块内 APK 与外部 APK 的字节一致性、`aapt` 身份与 `apksigner` 验证。

历史共同包名为 `com.lit008834.pixel.wanboxforandroid`，证书 SHA-256 为 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`；本轮候选资产必须重新与上一已发布版本核对，不能把历史记录当作新资产已验签。

Android 12+（minSdk 31）、arm64-v8a、Root；瘦管理器和 Root 模块必须使用同一发布页的同版资产。下载通过同一 Release 的 SHA256SUMS 后才可提示安装，禁止静默安装；资产不全时拒绝更新并引导至发布页。

## 发布来源与边界

preview.3已发布说明以其GitHub Release为准；PREVIEW_NOTES.md与release-notes-next.zh-CN.md现为preview.4候选稿，不代表已经发布。

preview.4 沿用 [发布清单](release-checklist.zh-CN.md) 的分阶段门槛。当前未做真机回归；只有真实 Root 设备矩阵签核后才能考虑正式版。本轮授权仅覆盖预览版，不授权发布 v3.0.12 正式版。

公开发布须使用获批精确提交及同次核验的四件套资产；先回读 Release、tag 和资产验证，再清理旧预览，正式版 Release 与 tag 保留。

正式版历史说明见 [RELEASE_NOTES.md](../RELEASE_NOTES.md)。
