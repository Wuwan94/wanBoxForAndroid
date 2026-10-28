# 全量重构工作词 —— 基线与现状差异核对

核对时间：2026-10-28
核对对象：《wanBoxForAndroid-全量重构工作词.md》（基线标注 `v3.0.10-preview.23` / `44c359d`）
本地仓库：`repo-v4`，`main` = `f28e187`（预览版 v3.0.10-preview.25）

本文按工作词 13.1 第 (1) 步要求编写：凡与文档不符处，以源码实况为准，并在此记录差异。

## 一、基线失效项（必须先读）

| 项 | 工作词假设 | 实况 | 影响 |
| --- | --- | --- | --- |
| 基线提交 | `44c359d`（preview.23，与 main 同提交） | **该提交在本仓库不存在**。旧仓库于本轮工作前被删除重建，历史未继承；preview.23 的树被冻结为初始提交 `790f33e` | 文档中所有「基线 `44c359d`」的表述按 `790f33e` 理解；无法从该提交开分支 |
| 当前版本 | preview.23 / code 2001 | **preview.25 / code 2003**（`VERSION_CODE=362` 未变） | 体积与功能基线应取 preview.25，不是 preview.23 |
| 签名 | 「不得改变签名与发行链路」（8 处） | **已于 preview.24 更换新证书**（`80ECEE1C…21F6`），旧 secrets 随旧仓库删除而消失 | 该项约束在 preview.23→preview.24 之间已被打破，本次不再回滚；后续版本沿用新证书 |
| release / issue / PR | 「不动既有 issue / PR / release」 | 旧 release 与 issue 已随旧仓库删除；现存 1 个 release（preview.25）、1 个已合并 PR #1 | 语义变为「不动现存 release 与 PR」 |
| 模块结构 | 五个 module 全部待建 | **`:core-contracts`、`:core-domain`、`:core-testing` 已建**（PR #1 合入 `2025f2d`），`:adapter-android` 未建 | P1 已完成 3/4，P2 部分完成 |
| 额外目录 | 未提及 | 仓库内存在 `arch/core-proof/`（上一轮实验工程，5 个模块、3764 行、91 个测试） | 与 `core/` 命名冲突、职责重复，P1 收尾时应删除或归档 |

## 二、代码事实核对（工作词声称「已核实」的条目）

| 断言 | 工作词 | 实测 | 结论 |
| --- | --- | --- | --- |
| `object DataStore` 位置 | `database/DataStore.kt:27` | 第 27 行 | 一致 |
| 全仓 `!!` 数量 | 254 | 254 | 一致 |
| 非 UI 包直接依赖 Android 的文件数 | 96 | 96 | 一致 |
| `ConfigurationFragment.kt` 行数 | 4,376 | 4,376 | 一致 |
| 同文件字节数 | 200,862 | **205,238** | 差 4,376 字节（preview.25 含后续修复），不影响结论 |
| `app/libs` 是否存在 | 不存在，需先跑 `./run lib core` | 不存在 | 一致 |
| `module.prop` 版本 | `3.0.7-preview.15` / 356 | 一致 | 一致 |
| 既有测试文件 | 315 单测 + 多个 androidTest | `app/src/test` + `app/src/androidTest` 共 90 个 `.kt`，含 `AuditDatabaseTest`、`PortableBackupCompatibilityTest` | 口径一致（数量按文件、非用例） |

结论：工作词的现状诊断在 preview.25 上**仍然成立**，除基线提交号、版本号、签名三项外可直接作为施工依据。

## 三、体积基线实测（工作词 7.1 口径）

取 v3.0.10-preview.25 已发布资产的真实字节数（Release asset `size` 字段，等同 `Content-Length`）：

```
模块 zip  wanbox-root-module-arm64-v8a.zip                19,064,473
App APK   wanBoxForAndroid-…-preview.25-arm64-v8a-release  26,802,141
合计                                                        45,866,614
上限（26 MiB）                                               26,214,400
超出                                                        19,652,214   +75.0%
```

已写入 `docs/rebuild/size-baseline.json`，并由 `tools/size_budget.py` 在 CI 中回读判定。

## 四、26MB 可达性判断（关键结论）

工作词 7.4 列出的 S1–S10 **全部作用在 App 侧**（ABI、assets、资源、依赖、符号表），没有一条能压缩 Root 模块 zip。而现状是：

| 方案 | 需要的条件 | 是否可行 |
| --- | --- | --- |
| 模块不变（19.06 MB） | APK 必须 ≤ 7,149,927 字节，即从 26.8 MB 砍掉 73% | 不可行。APK 里 native 库即使压到单 ABI，加上 dex、资源、geo 数据也到不了 7 MB |
| APK 压到工作词预算 11 MB | 模块必须 ≤ 15,214,400 字节，即从 19.06 MB 再减 3.85 MB | 需在 Go 二进制上动手，与「不许改动 `libcore/`、`rootmodule/` 运行时语义」冲突 |
| 按工作词 7.2 分配（模块 14 MB + APK 11 MB = 25 MB） | 模块减 5.06 MB、APK 减 15.8 MB | 模块那 5 MB 无对应手段；APK 那 15.8 MB 需靠 S1+S2+S3+S4+S5+S6 全部做满 |

因此：**按工作词自身的措施清单与不可动区约束，26,214,400 字节不可达。** 缺口不是估算问题，是措施集不覆盖对象的问题——预算的大头（19.06 MB / 45.87 MB = 41.6%）在模块 zip 里，而 S1–S10 一条都不碰它。

可选出路（需维护者裁决，本文不自行采用）：

1. **压模块二进制**：`librootbox` 与 `wanboxctl` 已带 `-ldflags="-s -w"`；进一步只能靠 UPX 之类运行时解压，或按 build tag 裁掉用不到的能力。前者改变启动行为，后者改变运行时语义，两者都触碰工作词第 12 章第 6 条。
2. **改预算口径**：例如把「模块 zip + App APK」改为「只要管理器的用户只下 APK」，或把上限提到实测可达值。这是产品决策，不是工程量。
3. **App 侧继续减重到极限**（S1–S10 做满），拿到 APK 的真实下限，再回头算模块还能剩多少——这也是唯一能在不越界前提下推进的路径。

## 五、阶段定位（P0–P7 相对现状）

| 阶段 | 工作词要求 | 当前状态 |
| --- | --- | --- |
| P0 冻结基线 | 30 条用例落成可跑测试 | 未做。现有 core 测试 15 条，只覆盖 U-03/U-07/U-15/U-16/U-17/U-18 |
| P1 建骨架 | 五个 module + include + `:app` 依赖 | **3/5 完成**（core 三件已建并接入 `:app`）；`:adapter-android` 未建；`arch/` 待清理 |
| P2 端口化 | 7 个端口 + adapter 委托实现 | 端口 7 个已落 `core/contracts`；adapter 实现缺失，卡在 `RootModuleClient` 位于 `:app` 造成的 `app → adapter → app` 环 |
| P3 状态下沉 | core 持会话状态 | 未开始 |
| P4 管理面接入 | HTTPS + 钉扎 + SSE 一等端口 | 未开始；需先确认模块侧是否已有服务端（工作词待确认第 8 条） |
| P5 逐屏换绑 | 从 Group/Main 开始 | 未开始 |
| P6 真值收敛 | Room 降级为缓存 | 未开始 |
| P7 验收收口 | 9 条判据 + 体积判定 | 体积判定脚本已落地；其余未做 |

## 六、本次一并落地的内容

- `tools/size_budget.py`：按 7.1 口径测量两个下载物，打印三行数字；`--enforce-limit` 用于终态判定，`--baseline` 用于回归判定（阈值 512 KiB）。四种分支已本地自测（与基线一致 / 超预算 / 文件缺失 / 回退超阈）。
- `docs/rebuild/size-baseline.json`：以 preview.25 真实字节数为回归基线。
- `verify-android.yml` 新增 `Measure download budget` 步骤：在模块 zip 与 APK 产出后回读，并写入 Job Summary。

为什么门禁判「相对基线不回退」而不是直接判 26MB：当前超预算 75%，若直接按 26MB 判定，每次构建都必然红，红灯不再携带信息。绝对预算留给 P7 用 `--enforce-limit` 一次性判定。
