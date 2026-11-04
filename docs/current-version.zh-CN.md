# 发布与源码版本对照

核对时间：**2026-11-04（正式版 3.0.10 准备阶段，S0 只读复核）**。本文区分「已发布的 Release」「默认分支源码」「待发布候选」；`nb4a.properties` 的字段、PR 描述或文档本身都**不能证明** Release 已公开。

## 核对时的 Release 与源码

| 对象 | 核对结果 | 边界 |
|---|---|---|
| 已发布正式版 Release | **不存在**。Releases 列表总数 1，非 prerelease 为 0；9 个 tag 全部是 preview 系列 | `VERSION_NAME=3.0.9` 只是旧源码元数据，不代表曾发布过稳定版 |
| 最新已发布预览 | [v3.0.10-preview.32](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.10-preview.32)，`prerelease=true`，发布于 2026-11-03T11:10:19Z，4 个资产 | 唯一可下载的公开版本；tag 指向 `de59193` |
| `.32` 发布源码 | `de59193`，与 `main` 的代码树内容一致（compare：`files=0`、`behind_by=1`） | `main` 是把 `beta1-1.15` 合入的合并提交，tag 指向合并前的第二父提交 |
| 核对时 `main` | `2aa9239`（merge commit） | S0 基线；本轮改动在独立分支 `release/3.0.10-stabilization`，**不直接改 main** |
| 正式版候选 | `3.0.10` / APK `versionCode 10055` / Root 模块码 `2011` | 需 PR 获批合并 → 构建核验 → 明确的正式 Release 批准后才发布 |
| 下一预览候选 | `3.0.10-preview.33` / `2012` | **只是元数据预置**，为满足「预览码 > 正式码」断言；**从未构建、从未发布**，无 tag 与资产 |
| 分支 / PR | 7 个分支全部未保护；S0 时 0 个 open PR | 无强制评审机制，靠流程约束 |

## 源码元数据与已发布产物的区别

| 项 | `.32` 已发布产物（实测） | 正式版候选（源码） |
|---|---|---|
| `versionName` | `3.0.10-preview.32` | `3.0.10` |
| APK `versionCode` | **10050** | **10055**（`2011 × 5`） |
| Root 模块码 | 2010 | 2011 |
| 包名 | `com.lit008834.pixel.wanboxforandroid` | 相同 |
| 签名证书 SHA-256 | `80ecee…21f6`（v2，RSA 3072） | 相同（发布 CI 硬断言） |
| `lib/` 条目 | 0（瘦管理器） | 0（正式 CI 现在带 `-PwanboxThin=true`） |

`.32` 的这些值来自**实际 APK**：用三段 Range 下载合成并核对 SHA-256（`4ec372…0016`），再用 `aapt dump badging` 与 `apksigner verify --print-certs` 读取，不是从发行说明推断。

## 升级口径

包名相同、签名相同、版本码严格递增 → 满足覆盖安装的**静态前提**。但覆盖升级后的数据保留、Root 模块状态、TUN 重启均**未在任何真机验证**（`adb devices` 为空）。因此当前文档统一采用「备份 → 卸载 → 安装 → 导入」路径，**不写「可直接覆盖升级并保留数据」**。完整步骤与回滚见 [RELEASE_NOTES.md](../RELEASE_NOTES.md)。

## 旧预览版边界

更早的预览 tag 可能来自不同源码布局或签名阶段（`.16` 曾更换签名密钥），不能用它们的安装步骤、版本代码或兼容结论替代目标版本说明。安装和升级前必须核对**同一发布页**中的 APK 与模块 ZIP，并用该页 `SHA256SUMS` 校验。
