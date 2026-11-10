# 发布、源码与版本对照

核对日期：2026-11-10。已发布状态以 GitHub Release、tag 和实际下载资产为准；源码元数据本身不构成已发布证据。

| 对象 | 版本与状态 | 版本码及来源 |
|---|---|---|
| 已发布正式版 | [v3.0.11](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.11)，非 prerelease；tag `03c6eb70854c797ee1e4069e378145be5ca56b0d` | APK / Root 模块 `10065 / 2013`（历史资产核验记录，归档语境） |
| 已发布预览版 | [v3.0.13-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.2)，prerelease；tag/commit `320f8173e59576d7891ac824c262db68bccf936c` | Release 四件套已发布；APK `10112` / Root 模块 `2022`，按 `metadataCode*5+previewSuffix` |
| 发布基线 | [v3.0.13-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.2) 的 tag/commit `320f8173e59576d7891ac824c262db68bccf936c` | 发布 tag 固定指向该提交；发布后的文档同步提交不改变已发布的四件套资产 |

源码元数据正式线为 `VERSION_NAME=3.0.11`、`VERSION_CODE=2017`，不随预览版本改动；预览线为 `PRE_VERSION_NAME=3.0.13-preview.3`、`PRE_VERSION_CODE=2023`。

源码预览正准备 v3.0.13-preview.3（APK 10118 / 模块 2023）；尚未发布，已发布资产仍以上表 preview.2 为准。发行说明开头为新版本发布准备稿。

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

历史共同包名为 `com.lit008834.pixel.wanboxforandroid`，证书 SHA-256 为 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`；本版资产已由 `Preview Build` 工作流核验并回读，不能把历史记录当作新资产已验签。

Android 12+（minSdk 31）、arm64-v8a、Root；瘦管理器和 Root 模块必须使用同一发布页的同版资产。下载通过同一 Release 的 SHA256SUMS 后才可提示安装，禁止静默安装；资产不全时明确提示并引导至发布页，不拼凑不同 Release 的文件。

## 发布来源与边界

已发布的 v3.0.13-preview.2 以 [其 Release](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.2) 与 tag/commit `320f8173e59576d7891ac824c262db68bccf936c` 为准。[PREVIEW_NOTES.md](../PREVIEW_NOTES.md) 与 [下一版说明](release-notes-next.zh-CN.md) 开头是 **v3.0.13-preview.2 已发布预览**稿；上一版预览 v3.0.13-preview.1 的 Release 与 tag 已按预览渠道策略清理，其说明保留在两文件的历史归档段落，不再作为当前下载入口。

发布前后分别更新状态：发布前只写「未发布候选」，本版 Release、tag 与四件套资产已实际核验成功，因此状态改写为「已发布」。

[发布清单](release-checklist.zh-CN.md) 沿用分阶段门槛。当前未做 Magisk / KernelSU / APatch 真机回归；只有真实 Root 设备矩阵签核后才能考虑正式版。本版已由 `Preview Build` 工作流从 main 上的经审查提交发布，旧预览由工作流清理，正式版 Release 与 tag 始终保留。

公开发布须使用获批精确提交及同次核验的四件套资产；先回读 Release、tag 和资产验证，再清理旧预览，正式版 Release 与 tag 始终保留。

正式版历史说明见 [RELEASE_NOTES.md](../RELEASE_NOTES.md)。
