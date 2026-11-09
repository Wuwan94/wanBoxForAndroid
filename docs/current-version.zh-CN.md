# 发布、源码与版本对照

核对日期：2026-11-09。已发布状态以 GitHub Release、tag 和实际下载资产为准；未发布候选不等于已发布版本。

| 对象 | 版本与状态 | 版本码及来源 |
|---|---|---|
| 已发布正式版 | [v3.0.11](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.11)，非 prerelease；tag `03c6eb70854c797ee1e4069e378145be5ca56b0d` | APK / Root 模块 `10065 / 2013`（历史资产核验记录，归档语境） |
| 已发布预览版 | [v3.0.13-preview.1](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.1)，prerelease；tag/commit `7b21b3d87e4f080b05fd1a109bd19b71c66fad23` | Release 四件套已发布；APK `10106` / Root 模块 `2021`，按 `metadataCode*5+previewSuffix` |
| 未发布候选 | `v3.0.13-preview.2`，本地候选，**未发布** | `PRE_VERSION_NAME=3.0.13-preview.2`、`PRE_VERSION_CODE=2022`；按公式预期 APK `10112`，高于 2021/10106；尚无发布资产 |
| 当前源码基线 | main @ `7b21b3d87e4f080b05fd1a109bd19b71c66fad23` | `VERSION_NAME=3.0.11`、`VERSION_CODE=2017`；正式线元数据不随预览候选改动 |

## 历史归档：v3.0.12-preview.2 发布资产快照

> 该旧预览的 Release 与 tag 已在预览渠道清理中移除。下表只是当时 GitHub Release API 返回的历史索引快照，**不是当前下载入口**，也不代替本轮下载、解包、签名与 SHA256SUMS 校验。历史数值保持原样，不改名成本轮结果。

| 文件 | 字节数 | GitHub SHA-256 digest |
|---|---:|---|
| `wanBoxForAndroid-3.0.12-preview.2-arm64-v8a-release.apk` | 6173441 | `f0339ae504cc5383e982360b60cd7dac945c25ed7985f6cf80053dacadb8672c` |
| `wanbox-root-module-arm64-v8a.zip` | 17743049 | `63d12d604d60f2fcb2fe28b06c090c1295664cf782dac6536bbdee8d6f16067f` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23228024 | `a8e148a905ffde1ad3d398fa6fb15cdc6fc13de56dddfe23d55e4eae5a1a7337` |
| `SHA256SUMS` | 333 | `655708bebff47efc67d1f438688d86def448dec195d7743df4e458ff117a4a9c` |

## 资产与兼容信息

历史机器可读记录 [preview3-version-evidence.json](preview3-version-evidence.json) 对应 v3.0.11 正式版与 v3.0.11-preview.2，**不是 v3.0.13-preview.1 或本轮 preview.2 的核验结果**。其中包括 Release digest、SHA256SUMS、ZIP 清单、带管理器模块内 APK 与外部 APK 的字节一致性、`aapt` 身份与 `apksigner` 验证。

历史共同包名为 `com.lit008834.pixel.wanboxforandroid`，证书 SHA-256 为 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`；本轮候选资产必须重新与上一已发布版本核对，不能把历史记录当作新资产已验签。

Android 12+（minSdk 31）、arm64-v8a、Root；瘦管理器和 Root 模块必须使用同一发布页的同版资产。下载通过同一 Release 的 SHA256SUMS 后才可提示安装，禁止静默安装；资产不全时明确提示并引导至发布页，不拼凑不同 Release 的文件。

## 发布来源与边界

已发布的 v3.0.13-preview.1 以 [其 Release](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.1) 与 tag/commit `7b21b3d87e4f080b05fd1a109bd19b71c66fad23` 为准。[PREVIEW_NOTES.md](../PREVIEW_NOTES.md) 与 [下一版说明](release-notes-next.zh-CN.md) 开头是 **v3.0.13-preview.2 未发布候选**稿，preview.1 的已发布说明保留在两文件的历史归档段落。已清理的旧预览 tag 只作为历史来源记录，不写成当前下载链接。

发布前后分别更新状态：发布前只写「未发布候选」，只有 Release、tag 与四件套资产实际核验成功后才改写为「已发布」。

[发布清单](release-checklist.zh-CN.md) 沿用分阶段门槛。当前未做 Magisk / KernelSU / APatch 真机回归；只有真实 Root 设备矩阵签核后才能考虑正式版。本轮未获发布授权，不推送、不合并、不打 tag、不发布，也不清理任何远端 Release。

公开发布须使用获批精确提交及同次核验的四件套资产；先回读 Release、tag 和资产验证，再清理旧预览，正式版 Release 与 tag 始终保留。

正式版历史说明见 [RELEASE_NOTES.md](../RELEASE_NOTES.md)。
