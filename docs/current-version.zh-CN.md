# 发布、源码与版本对照

核对日期：2026-11-11。发布状态以 GitHub Release、tag 与实际下载资产为准；源码元数据本身不构成已发布资产的核验。

| 对象 | 版本与状态 | 版本码及来源 |
|---|---|---|
| 已发布正式版 | [v3.0.11](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.11)，非 prerelease；tag `03c6eb70854c797ee1e4069e378145be5ca56b0d` | APK / Root 模块 `10065 / 2013`（历史资产核验记录，归档语境） |
| 已发布预览版 | [v3.0.13-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.3)，prerelease；tag/commit `77e291a150819a70d87c0af8458f1c603088e432` | 版本映射为 APK `10118` / Root 模块码 `2023`；本轮未重新核验该 Release 的二进制资产 |
| 发布基线 | [v3.0.13-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.3) 的 tag/commit `77e291a150819a70d87c0af8458f1c603088e432` | 工作分支从该固定 tag 建立；后续源码改动不改变已发布基线 |

源码元数据正式线为 `VERSION_NAME=3.0.11`、`VERSION_CODE=2017`，不随预览版本改动；当前预览线为 `PRE_VERSION_NAME=3.0.13-preview.4`、`PRE_VERSION_CODE=2024`。共享向量计算 APK `versionCode=10124`。

源码正在准备 v3.0.13-preview.4；它尚未发布。本地 PreviewRelease 测量 APK（临时测试签名）与 Root 模块 ZIP 已通过 26 MiB 体积门禁；正式发布签名、SHA256SUMS 和 GitHub Release 资产仍未生成或核验。本轮仅在独立分支实施并供复审，不执行合并、发布或 tag/Release 清理。

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

已发布的 v3.0.13-preview.3 以 [其 Release/tag](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.3) 与 tag/commit `77e291a150819a70d87c0af8458f1c603088e432` 为准。[PREVIEW_NOTES.md](../PREVIEW_NOTES.md) 与 [下一版说明](release-notes-next.zh-CN.md) 的当前区段描述 preview.4 候选；preview.3 与更早版本的历史说明保留在归档段落中，不作为本轮测试结果。

发布前后分别记录状态：当前 preview.4 仍是未发布候选。只有经复审、批准的精确提交完成构建、资产与签名核验后，才可另行评估发布；本任务没有获得发布、合并或清理旧 Release/tag 的授权。

[发布清单](release-checklist.zh-CN.md) 保留分阶段门槛。当前未做 Magisk / KernelSU / APatch 真机回归；只有真实 Root 设备矩阵签核后才能考虑正式版。任何自动化结果都不代替真机验证。

公开发布须使用获批精确提交及同次核验的四件套资产；先回读 Release、tag 和资产验证，再按获批流程处理旧预览，正式版 Release 与 tag 始终保留。

正式版历史说明见 [RELEASE_NOTES.md](../RELEASE_NOTES.md)。
