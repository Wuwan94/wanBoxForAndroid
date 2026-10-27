# 基线核对：工作词 vs 当前仓库

日期：2026-10-27
对照文档：《wanBoxForAndroid 完全架构重建工作词（基线 v3.0.10-preview.23）》

## 1. 现状断言核对（全部一致）

| 文档断言 | 仓库实测 | 结论 |
| --- | --- | --- |
| `settings.gradle.kts` 只有 `include(":app")` | 是（`rootProject.name = "NB4A"`） | 一致 |
| `database/DataStore.kt:27` 为 `object DataStore` | 第 27 行，类型 `OnPreferenceDataStoreChangeListener` | 一致 |
| `ui/ConfigurationFragment.kt` 4,376 行 / 200,862 字节 | 4,376 行 | 一致 |
| 全仓 `!!` 254 处 | 254 处 | 一致 |
| 非 UI 包直接 import Android 的文件 96 个 | 96 个 | 一致 |
| `app/libs` 在 tag 树内不存在 | 不存在（需 `buildScript/lib/core/build.sh` 产出 aar） | 一致 |
| `openspec/changes/` 5 个进行中提案 | 5 个 + `archive/` | 一致 |
| `openspec/specs/` 4 份主规范 | 4 份 | 一致 |

## 2. 与文档基线的差异

| 项 | 文档假设 | 当前仓库 | 影响 |
| --- | --- | --- | --- |
| 提交历史 | `v3.0.10-preview.23`（`44c359d`，与 main 同提交） | 单提交仓库（`790f33e` 起始 + 版本/CI 修复），内容等于 `fix/security-perf-main` 工作树 | 行号与路径仍对得上；引用提交号时需改写 |
| 版本元数据 | `PRE_VERSION_NAME=3.0.10-preview.23` / `PRE_VERSION_CODE=2001` | `3.0.10-preview.24` / `2002`（构建时 ×5 = 10010） | 用例与验收不受影响 |
| 签名 | 工作词要求「不得改变签名与发行链路」 | preview.24 按用户要求更换为新签名（旧 secrets 随旧仓库删除而丢失，历史 keystore 指纹不匹配） | 与工作词约束冲突，属用户决策；重建不回滚 |
| `arch/` | 文档未提及 | 仓库内含上一轮实验性 `arch/core-proof`（5 个 JVM 模块，91 测试） | 与本次 `core/*` 并存，待 P2 后评估删除 |
| `.gitattributes` | 文档未提及 | 已加 `*.sh eol=lf` 等规则 | 无影响（且修复了新仓库可执行位丢失导致的 CI 126） |

## 3. 处置

1. 以工作词第 3.2 节的 module 命名为准（`:core-contracts` / `:core-domain` / `:core-testing` / `:adapter-android` / `:app`），不复用 `arch/core-proof` 的 `:core:contracts` 命名。
2. 重建不回滚 preview.24 的签名变更；工作词中「签名不可动」一条按现状解释为「不再改动」。
3. `arch/` 暂保留作为参考实现，P2 完成后决定删除或归档，避免两套 core 结构长期并存。
