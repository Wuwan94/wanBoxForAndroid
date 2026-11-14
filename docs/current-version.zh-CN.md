# 发布、源码与版本对照

核对日期：2026-11-13。发布状态以 GitHub Release、tag 与实际下载资产为准；源码元数据本身不构成已发布资产的核验。

| 对象 | 版本与状态 | 版本码及来源 |
|---|---|---|
| 已发布正式版 | [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14)，非 prerelease；由 3.0.14 预览线晋升 | APK / Root 模块 `10130 / 2026`（源码正式线 `VERSION_NAME=3.0.14`、`VERSION_CODE=2026`）；四件套文件名与发布回读数据见下文 |
| 已发布预览版 | [v3.0.15-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.15-preview.3)，prerelease；tag/commit `fe193f1134a80ff38fb0cb3ed4677eb2b79c1dce` | APK `versionCode=10143` / Root 模块码 `2028`；由正式 `Preview Build` run `43404430362` 发布 |
| 上一正式版 | [v3.0.11](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.11)，非 prerelease，保留；tag `03c6eb70854c797ee1e4069e378145be5ca56b0d` | APK / Root 模块 `10065 / 2013`（历史资产核验记录，归档语境） |
| 发布基线 | [v3.0.15-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.15-preview.3) 的 tag/commit `fe193f1134a80ff38fb0cb3ed4677eb2b79c1dce` | PR #39 合并提交；固定发布基线，不等同于后续 main HEAD |
| 源码预览线 | `3.0.15-preview.3`，与最新已发布预览同线 | `PRE_VERSION_NAME=3.0.15-preview.3`、`PRE_VERSION_CODE=2028`；共享向量计算 APK `versionCode=10143` |

源码元数据正式线保持 `VERSION_NAME=3.0.14`、`VERSION_CODE=2026`；preview.3 是当前已发布预览，`PRE_VERSION_NAME=3.0.15-preview.3`、`PRE_VERSION_CODE=2028`，其 APK `versionCode=10143` 高于 preview.2 的 `10137`，可作为正常覆盖升级包。稳定版未改变。

v3.0.15-preview.3 由 PR #39 修复并晋升，官方 `Preview Build` run `43404430362` 发布；tag 固定指向 `fe193f1134a80ff38fb0cb3ed4677eb2b79c1dce`。preview.2 的 Release/tag 已在新资产校验成功后按单预览策略清理；正式版 v3.0.14 与上一正式版 v3.0.11 保留。

## v3.0.14 正式版四件套（文件名）

正式版 Release 提供以下 4 个资产，必须成套使用。以下为发布回读数据（GitHub Release API 的字节数与 SHA-256 digest），已与发布页逐项核对：

| 文件 | 说明 |
|---|---|
| `wanBoxForAndroid-3.0.14-arm64-v8a.apk` | 瘦管理器（APK `versionCode=10130`） |
| `wanbox-root-module-arm64-v8a.zip` | Root 模块（模块码 `2026`） |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 模块 + 管理器合一变体 |
| `SHA256SUMS` | 上述三项的校验和 |

来源：[GitHub Release v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14)，正式发布 run `43075047180`（候选 run `43071481125`），tag/commit 为 `fa9a9194ff89834e574640a0ec8e0d5d973cc024`。以下 GitHub API SHA-256 digest 与发布资产逐字节匹配；公开 `SHA256SUMS` 清单校验通过，清单文件自身 digest 与发布时回填值一致。

| 文件 | 字节数 | GitHub SHA-256 digest |
|---|---:|---|
| `wanBoxForAndroid-3.0.14-arm64-v8a.apk` | 6192798 | `e27332d6d64cf120ded6bf4afd8152bbe80045aa8a0f5f9c93cd3055bdf2ca3e` |
| `wanbox-root-module-arm64-v8a.zip` | 17744708 | `03f287bb52c1284ec3fc95a17b0e02465e35f8356ae467dd3d3c3a7f98b15f6d` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23247263 | `7f613a97c3f2d5a68aadff0e0b44e95adc417e31c50ef153d657e874a366afcd` |
| `SHA256SUMS` | 315 | `f198d076edef04576a7e0c24bbfc9028754917d641a4d56d6566c6d7ed5e6d0c` |

APK 与普通 Root 模块合计 `23937506` 字节（22.83 MiB）；带管理器模块包为 22.17 MiB，均低于 26 MiB。候选构建的跨版本身份检查已核对包名与正式证书（SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`），且 APK `versionCode 10130` 高于 v3.0.11 基线 `10065`。本次发布未执行任何清理：`v3.0.11` 正式版与 `v3.0.14-preview.1` 预览版均保留。

## v3.0.15-preview.3 已发布资产快照

来源：[GitHub Release v3.0.15-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.15-preview.3)，正式发布 run `43404430362`，tag/commit 为 `fe193f1134a80ff38fb0cb3ed4677eb2b79c1dce`。以下 GitHub API SHA-256 digest 与下载资产逐字节匹配；下载的公开 `SHA256SUMS` 清单校验通过。

| 文件 | 字节数 | GitHub SHA-256 digest |
|---|---:|---|
| `wanBoxForAndroid-3.0.15-preview.3-arm64-v8a-release.apk` | 6200158 | `cef80e017631f2cc07f50b74a9ecf125357df32c910fc55b149f6e8a4b121da4` |
| `wanbox-root-module-arm64-v8a.zip` | 17932526 | `32f5adad036259bd01d6a2dcd51ca745a53ed110008c8017381ad50bbb9b9c32` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23441379 | `9ed2015ca6adfe3f5736a0d7a726f979b223454fe6cfb6bfb5cb8a79a1ae8bb5` |
| `SHA256SUMS` | 333 | `03ff5cfc775bd10acee52c4190c0a42eb5d71037f0529454cb915b01ccf559fd` |

APK 与普通 Root 模块合计 `24132684` 字节；带管理器模块包为 `23441379` 字节，均低于 26 MiB。

## 历史归档：v3.0.15-preview.2 资产快照（Release/tag 已清理）

> preview.2 的 Release/tag 已在 preview.3 资产发布并验证后按单预览保留策略删除。下列为当时发布资产的 API 索引快照，不是当前下载入口。

来源：正式 `Preview Build` run `43393988473`；原 tag/commit 为 `2a57973623db59cb635a869b5d552dd971f8235c`。

| 文件 | 字节数 | GitHub SHA-256 digest |
|---|---:|---|
| `wanBoxForAndroid-3.0.15-preview.2-arm64-v8a-release.apk` | 6200162 | `092c162b28078bc5cff329a7fa15dd39dd648ca99badb27eb1bee5df0c6b28c1` |
| `wanbox-root-module-arm64-v8a.zip` | 17932424 | `c59971a5709e5eeb69568daafb5e0a20b8929a6d45debd00bfb673340d049bf4` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23441376 | `1b4dd3cffe51959162d1cdddb1d7e70e3946b489a76f64b2ae440c66a46a2b01` |
| `SHA256SUMS` | 333 | `9e5c20272fbee8bbf34f646dabb22ab7e69244803a020be73c849eac31b0118b` |

APK 与普通 Root 模块合计 `24132586` 字节；带管理器模块包为 `23441376` 字节，均低于 26 MiB。

## 历史归档：v3.0.15-preview.1 发布资产快照（Release/tag 已清理）

preview.1 的 Release/tag 已在 preview.2 发布后按单预览策略清理。来源为正式 `Preview Build` run `43203156796` 的历史资产快照。以下 GitHub API SHA-256 digest 与当时下载文件逐字节匹配；公开 `SHA256SUMS` 清单校验通过。

| 文件 | 字节数 | GitHub SHA-256 digest |
|---|---:|---|
| `wanBoxForAndroid-3.0.15-preview.1-arm64-v8a-release.apk` | 6196814 | `5bf494dda9106e10aecb88a62141f6ef3b8be59b4572b2e1ce02f828d003743d` |
| `wanbox-root-module-arm64-v8a.zip` | 17749902 | `c67332683ce7676db9d03faa5c328dafb0be1515fe5341b531ab839b58f323e6` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23255747 | `f4fc899763e6dce076cd2069f43a0b425a441b6992836423ec70e84ffd25555a` |
| `SHA256SUMS` | 333 | `be1130aa8f36e315a21544956d9f2f3bd0863055f93ca751e8282a6b1e8db6b9` |

APK 与普通 Root 模块合计 `23946716` 字节（22.84 MiB）；带管理器模块包为 22.18 MiB，均低于 26 MiB。

## 历史归档：v3.0.14-preview.1 发布资产快照

> 该预览的 Release 与 tag 已在 v3.0.15-preview.1 发布后按单预览保留策略删除。下表为当时 GitHub Release API 返回的索引快照，**不是当前下载入口**；其六项复查修复已由 v3.0.14 正式版全量承接。

来源：[GitHub Release v3.0.14-preview.1](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14-preview.1)，正式发布 run `43003022425`。以下 GitHub API SHA-256 digest 与下载文件逐字节匹配；公开 `SHA256SUMS` 清单校验通过。

| 文件 | 字节数 | GitHub SHA-256 digest |
|---|---:|---|
| `wanBoxForAndroid-3.0.14-preview.1-arm64-v8a-release.apk` | 6194042 | `380926f8e824255e4d22bf12d79a1cc2fdd34faf9802982f276afa11981fae45` |
| `wanbox-root-module-arm64-v8a.zip` | 17744745 | `9027e571d5cb3b4efcc0ce3d076dff9691537a86debf033f5e3d85d5afc299d0` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23247910 | `7b1c0e5fa1a25ddb5fb9d50ddd17a2d517c02719e3e814988963ca7a09e2c7fa` |
| `SHA256SUMS` | 333 | `8f50ab75f40a672c2dd3d2725bb7023157f4ed9c9cba007be8edd1523b1fa4a2` |

APK 与普通 Root 模块合计 `23938787` 字节（22.83 MiB）；带管理器模块包为 22.17 MiB，均低于 26 MiB。两个 ZIP 完整性通过；带管理器 ZIP 内 APK 与独立 APK 字节完全一致。`apksigner` 验证 APK 签名通过，证书 SHA-256 为 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`，匹配仓库固定的正式发布证书。

## 历史归档：v3.0.12-preview.2 发布资产快照

> 该旧预览的 Release 与 tag 已在预览渠道清理中移除。下表只是当时 GitHub Release API 返回的历史索引快照，**不是当前下载入口**，也不代替本轮下载、解包、签名与 SHA256SUMS 校验。历史数值保持原样，不改名成本轮结果。

| 文件 | 字节数 | GitHub SHA-256 digest |
|---|---:|---|
| `wanBoxForAndroid-3.0.12-preview.2-arm64-v8a-release.apk` | 6173441 | `f0339ae504cc5383e982360b60cd7dac945c25ed7985f6cf80053dacadb8672c` |
| `wanbox-root-module-arm64-v8a.zip` | 17743049 | `63d12d604d60f2fcb2fe28b06c090c1295664cf782dac6536bbdee8d6f16067f` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23228024 | `a8e148a905ffde1ad3d398fa6fb15cdc6fc13de56dddfe23d55e4eae5a1a7337` |
| `SHA256SUMS` | 333 | `655708bebff47efc67d1f438688d86def448dec195d7743df4e458ff117a4a9c` |

## 资产与兼容信息

历史机器可读记录 [preview3-version-evidence.json](preview3-version-evidence.json) 对应 v3.0.11 正式版与 v3.0.11-preview.2，**不是本轮 v3.0.15-preview.3 的核验结果**。其中包括 Release digest、SHA256SUMS、ZIP 清单、带管理器模块内 APK 与外部 APK 的字节一致性、`aapt` 身份与 `apksigner` 验证。

历史共同包名为 `com.lit008834.pixel.wanboxforandroid`，证书 SHA-256 为 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`；这是历史记录，不代表本轮 preview.1 资产已验签。

Android 12+（minSdk 31）、arm64-v8a、Root；瘦管理器和 Root 模块必须使用同一发布页的同版资产。下载通过同一 Release 的 SHA256SUMS 后才可提示安装，禁止静默安装；资产不全时明确提示并引导至发布页，不拼凑不同 Release 的文件。

## 发布来源与边界

当前已发布预览以本文表格中的 v3.0.15-preview.3 为准；`PREVIEW_NOTES.md` 与[说明快照](release-notes-next.zh-CN.md)记录同一已发布版本。preview.2 与更早预览的说明和资产摘要均作为历史记录保留，不作为当前下载入口或本轮新测试结果。

本版由 PR #39 的合并提交触发正式 `Preview Build`；run `43404430362` 核验了精确获批 SHA、候选来源、Release/tag 与资产。发布后下载四件套，逐项比对 GitHub API digest 并通过公开 `SHA256SUMS` 校验。

[发布清单](release-checklist.zh-CN.md) 保留分阶段门槛与正式版发布清单。当前 preview.3 未做 Magisk / KernelSU / APatch 真机回归；稳定版 v3.0.14 保持不变，真机矩阵仍待签核，任何自动化结果都不代替真机验证。

上一正式版 v3.0.11 与稳定版 v3.0.14 的 Release 与 tag 均保留，正式发布不执行 prune；当前预览渠道只保留最新的 prerelease，即 v3.0.15-preview.3。preview.2 已在新资产核验成功后由官方流程清理。后续发布仍须使用获批精确提交及同次核验的四件套资产，并在清理旧预览前完成回读验证。

正式版说明见 [RELEASE_NOTES.md](../RELEASE_NOTES.md)（v3.0.14；原 v3.0.11 说明正文已被替换，保留在其 Release 页与 git 历史中）。
