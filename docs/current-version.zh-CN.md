# 发布、源码与候选版本对照

核对日期：2026-11-06。发布状态以 GitHub Release、tag 和实际下载资产为准。

| 对象 | 源码/版本 | APK / 模块真实版本码 |
|---|---|---|
| 已发布正式版 | v3.0.11，tag `03c6eb70854c797ee1e4069e378145be5ca56b0d` | 10065 / 2013 |
| 已发布预览版 | v3.0.11-preview.2，tag `2fbe17f662897f44bf903c957fd9723e4a4977f9` | 10077 / 2015 |
| 开工时默认分支 | main，`ff26111` | 源码仍写 preview.1，且不含 preview.2；不能直接当作 preview.2 基线 |
| 独立候选分支 | release/v3.0.11-preview.3-stability，已合入 preview.2 并保留 main UI | 元数据双 2016；脚本：正式线 10080、预览线 10083，最终包另验 |

## 真实资产证据

两套发布各下载四资产，核对 Release digest、SHA256SUMS、ZIP 包清单与模块中的同一管理器。aapt 读取实际 APK；apksigner 验证 v2 签名和证书。精确资产哈希见 [机器可读证据](preview3-version-evidence.json)。

共同包名：`com.lit008834.pixel.wanboxforandroid`。共同证书 SHA256：`80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`。

preview.2 tag 中 VERSION_CODE/PRE_VERSION_CODE 都是 2015，但预览规则是「元数据 × 5 + preview 序号」，因此实际 10077 与源码一致。源码正式线可算出 10075，不等于已发布正式 APK；已发布正式版仍是 10065。候选改双码 2016 后，正式线 10080 > 10065，预览线 10083 > 10077，模块 2016 分别大于 2013/2015；正式探针也高于 preview.2，避免后续切回正式线降级。

以上只证明版本码、包名与签名满足静态升级前提，不证明数据无损。未完成真机运行时回归。候选资产与实际命令结果见 [验证记录](preview3-validation.zh-CN.md)。本轮不创建或替换 Release、tag，不清理现有版本；[正式版历史](../RELEASE_NOTES.md)未改写。
