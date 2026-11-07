# 发布、源码与版本对照

发布状态以 GitHub Release、tag 和实际下载资产为准。稳定版与 preview.2 的实测依据保留如下；preview.1（v3.0.12）的资产文件、SHA256 清单及实际版本信息以其发布页为准。

| 对象 | 源码/版本 | APK / 模块版本信息 |
|---|---|---|
| 已发布正式版 | v3.0.11，tag `03c6eb70854c797ee1e4069e378145be5ca56b0d` | 实际 APK / 模块版本码：10065 / 2013 |
| 上一预览版 | v3.0.11-preview.2，tag `2fbe17f662897f44bf903c957fd9723e4a4977f9` | 实际 APK / 模块版本码：10077 / 2015 |
| 当前预览版 | v3.0.12-preview.1；元数据码 `2017`，预览 APK / 模块版本码 `10086` / `2017` | 以发布页实际 APK、模块及 SHA256SUMS 为准 |

## 资产与兼容信息

正式版与 preview.2 的资产检查记录包含 Release digest、SHA256SUMS、ZIP 清单、模块内管理器 APK 与外部 APK 的字节一致性；APK 身份由 `aapt` 读取，签名由 `apksigner` 验证。对应机器可读基线见 [版本证据](preview3-version-evidence.json)。

共同包名：`com.lit008834.pixel.wanboxforandroid`。共同证书 SHA-256：`80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`。

正式版与 preview.2 的实际版本码分别高于其先前版本。v3.0.12-preview.1 的发布构建会使用仓库版本码脚本检查正式线和预览线基线，并从实际 APK、Root 模块和发布页清单核验最终资产；源码元数据本身不代替发布附件证据。

## 发布来源

v3.0.12-preview.1 以 v3.0.11-preview.3 为代码基线，保留现有界面与交互。其公开发布由 main 上的人工工作流完成，工作流要求 `approved_sha` 与精确构建提交一致；Release 直接使用同一次通过候选校验的构件与 SHA256 清单。上传后先核验 Release、tag 与资产，再清理旧预览；正式版 Release 与 tag 始终保留。

正式版历史说明见 [RELEASE_NOTES.md](../RELEASE_NOTES.md)。
