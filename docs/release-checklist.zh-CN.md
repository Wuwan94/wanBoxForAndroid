# v3.0.15-preview.3 分阶段发布清单

本版 **v3.0.15-preview.3 已作为稳定性预览版发布**，tag 指向 PR #39 合并提交 `fe193f1134a80ff38fb0cb3ed4677eb2b79c1dce`，由官方 `Preview Build` run `43404430362` 发布；已发布正式版仍为 v3.0.14（正式版发布清单见本文件下文专节）。preview.2 的 Release/tag 已在 preview.3 发布并验证后按单预览保留策略删除。Root 真机矩阵仍待签核。

## 当前基线与不可变约束

- Android 12+ / minSdk 31 / arm64-v8a / targetSdk 35 / JDK 17 / Build Tools 35.0.1。
- Gradle 8.13 / AGP 8.11.1 / Kotlin 2.2.21 / KSP 2.2.21-2.0.4 / compileSdk 36 / Room 2.7.2。
- 内核 `reF1nd/sing-box v1.15.0-alpha.13-reF1nd`。内核和 targetSdk 阶段只评估，升级须另获确认。
- 模块拥有代理生命周期；管理器为瘦 APK，不引入多内核、不恢复 VPN、不重做 UI。
- 不删除或改名设置键、不改默认值；TLS 默认验证证书；日志不得含备份内容、节点凭据、订阅原文。
- preview.2 的历史版本码为 Root 模块 `2027` / APK `10137`；当前已发布 preview.3 为 `2028 / 10143`，可从 preview.2 覆盖安装；正式线元数据保持 `3.0.14 / 2026` 不变。

## 阶段与验收

下表保留分阶段验收项作为过程记录；preview.3 实际发布结果见下方勾选门禁。任何发布前门禁失败都应停止、报告并保留现场，不绕过门禁。

| 阶段 | 范围 | 验收与输出 |
|---|---|---|
| 1 文档一致性 | README、徽章、版本对照与本清单 | **README 快照已更新**：正式版 / 已发布预览分别标注；链接和工具链一致；不把历史证据当新结果 |
| 2 URLTest 回归 | 异步首轮 5 秒预算、全员不可用回退、取消不写失败结果、关闭停止探测 | 补测试并通过；节点选择读写仍共用同一把锁；真机结果单列待签核 |
| 3 更新器异常路径 | 断网、磁盘满、SHA256 不符、资产不全；普通及 with-manager 完整文件名匹配 | 可复现证据；临时文件及本次包清理、不弹安装、错误可读；校验后才由用户安装 |
| 4 P1 清尾 | baseline-prof.txt、>1000 节点 DiffUtil 与已定位主线程读取、图标、工具页；跨进程失效只设计 | 每项独立提交可回滚；基线与性能数据真实记录，不伪造设备结果，不实现设置缓存 |
| 5 内核与 targetSdk 评估 | 上游 / fork 新预览、TUN 栈 / stack 弃用、Android auto_redirect、targetSdk 36 行为影响 | 两份评估文档；不擅自升级，升级动作另等确认 |
| 6 版本与发布材料 | 顺延版本码；发行说明按更新 / 安装版本 / 自动化及边界三部分 | README、元数据和发行说明一致；无真机签核时保留“未做真机回归”；正式版须另获明确授权后按下文正式版发布清单发布 |

## 预览发布前硬门槛

- [x] README 快照、版本对照与中文发行说明已对齐已发布 v3.0.15-preview.3；对应下载入口指向有效 Release。
- [x] 原生核心、Android/Root 模块构建、单元与工具测试、签名构建、安全检查及新增回归门禁通过；PR #39 的必需构建检查通过，正式 `Preview Build` run `43404430362` 全部成功。
- [x] 四件套齐全：瘦 APK、`wanbox-root-module-arm64-v8a.zip`、`wanbox-root-module-with-manager-arm64-v8a.zip`、`SHA256SUMS`。
- [x] ABI、版本码、包名、签名构建、ZIP 完整性、GitHub API digest 与公开 SHA256SUMS 已核对；下载四件套后 `sha256sum --check SHA256SUMS` 全部通过。
- [x] 发行说明未宣称跨版本备份互通或 Root 真机通过；官方 run 完成 Android 模拟器冷启动测量，结果不代替真机矩阵签核。
- [x] Release/tag 指向 PR #39 合并提交 `fe193f1134a80ff38fb0cb3ed4677eb2b79c1dce`，未覆盖已有 tag、未强推。
- [x] 中文发行说明如实区分自动化与真机边界；只发布 prerelease，不宣称正式版可用。
- [x] 发布后回读四件套名称、数量、大小、SHA256 与 tag；preview.2 Release/tag 已按单预览保留策略清理，稳定版 v3.0.14 与 v3.0.11 保留。

任何一项发布前门禁失败都应停止发布，保留构件、日志和失败现场。preview.3 已按上述门禁发布并回读验证；正式版发布已由用户另行明确授权，门禁按下文「v3.0.14 正式版发布清单」执行。

## 真机待签核矩阵（不能用模拟器代替）

| 维度 | 最低覆盖 | 待验证内容 |
|---|---|---|
| Root | Magisk / KernelSU / APatch 各至少 1 台 | 安装、启停、禁用、卸载后路由与规则清理，无残留断网 |
| 系统 | Android 12 与主流高版本各至少 1 台 | 覆盖安装数据保留、TUN 建立 / 重建 / 关闭 |
| 稳定性 | 杀核心、节点全失效、网络频繁切换 | 看门狗最多 5 次，退避 1/2/4/8/16 秒；五类断线原因落盘，不无限重启 |
| 启动测速 | 冷启动首连、单节点及批量 | URLTest 5 秒总预算，超时 1–60 秒生效，取消不污染结果 |
| 更新 | 正常、断网、校验失败 | 校验通过才提示安装；失败清理，旧版仍可用 |
| 数据 | 新旧版本双向备份导入 | 设置键、节点、订阅完整 |

全部保持“待签核”。正式发布已另获用户明确授权（2026-11-11），按下文正式版发布清单的两阶段流程执行；真机矩阵不因正式发布而视为已签核，发行说明如实标注未做真机回归。

---

## v3.0.14 正式版发布清单

> 适用范围：`main` 上的 v3.0.14 正式版（非预览）发布。本次正式发布已由用户明确授权（2026-11-11）；仍须在获批 PR 合并、构建与资产核验完成后按两阶段流程执行。
> **本次流程明确要求不删除旧版、不覆盖已有 tag、不强推**：正式版不触发 `prune_prereleases.py`，`v3.0.11` 正式版与 `v3.0.14-preview.1` 预览版均保留。

### 核心规则

| 项 | 本版规则 |
|---|---|
| 旧 Release / tag | **不删除**。正式版不触发 `prune_prereleases.py`；预览线的既有 prune 只作用于 prerelease，且不在正式发布流程中执行 |
| 覆盖升级声明 | 同签名 + 版本码严格递增即可覆盖安装；数据保留以备份双向兼容口径为准，真机 D1–D10 未验证项如实标注，不宣称真机签核 |
| 版本码 | 必须**严格大于已发布 v3.0.11**：APK `> 10065`、模块 `> 2013`；本版为 APK `10130`、模块 `2026` |
| 触发方式 | 两阶段 dispatch（见下文流程顺序）；dispatch 必须提供 `approved_sha`，且 `github.sha` 等于获批 PR 合并提交；tag 已存在则拒绝 |
| 测试 | Go / Kotlin / Android / Python 全量测试 + 13 项发布门禁（G1–G13） |

### 版本规则（`nb4a.properties`）

| 字段 | 当前值 | 说明 |
|---|---|---|
| `VERSION_NAME` | `3.0.14` | tag 必须严格等于 `v$VERSION_NAME` → `v3.0.14` |
| `VERSION_CODE` | `2026` | APK `versionCode = 2026 × 5 = 10130`；Root 模块码 `2026` |
| `PRE_VERSION_NAME` | `3.0.15-preview.1` | **未发布候选**，只为满足 `PRE > FORMAL` 断言 |
| `PRE_VERSION_CODE` | `2027` | 必须 `> VERSION_CODE`（`preview.yml` 硬断言）；预览 APK `10136` |
| `SINGBOX_VERSION` | `v1.15.0-alpha.13-reF1nd` | 与 v3.0.11 一致 |

`×5` 换算与渠道判定由 `tools/version_code.py` 统一实现，两条渠道不得各自用 shell 算术推断。

### 发布前门禁清单（G1–G13，沿用正式门禁基线）

| ID | 门禁 | 失败处理 |
|---|---|---|
| G1 | 4 个 `release.yml` 作业固定 `ubuntu-24.04`；Go `1.26.10`、JDK 17、NDK `25.0.8775105`；其余工作流同步固定 | 停止 |
| G2 | `libcore` 与 `rootmodule` 的 Go 测试（含 `-race`） | 停止 |
| G3 | `app:testOssDebugUnitTest`、`app:compileOssDebugAndroidTestKotlin`、`:core-domain:test`、Python 全量单测 | 停止 |
| G4 | tag == `v$VERSION_NAME`；tag 不存在；dispatch 的 `approved_sha` == `github.sha` | 停止 |
| G5 | `version_code.py --enforce`：APK `> 10065`、模块 `> 2013` | 停止 |
| G6 | `aapt`：包名 / `versionName` / `versionCode` / `minSdk=31` / `targetSdk=35`；`verify_apk_abi.py --thin`；`lib/` 条目为 0 | 停止 |
| G7 | `apksigner`：v2 验签 + 证书 `80ecee…21f6`；**下载 v3.0.11 实际 APK 做跨版本包名 / 签名 / 版本码比对** | 停止 |
| G8 | 正式模块 `--channel formal`；`module.prop` 为 `3.0.14` / `2026`；ZIP 完整性 | 停止 |
| G9 | 普通模块 ZIP 与带管理器 ZIP 均通过 `python3 -m zipfile -t` | 停止 |
| G10 | 资产白名单恰为 `{APK, 普通模块 ZIP, 带管理器 ZIP, SHA256SUMS}` | 停止 |
| G11 | 上传前 `sha256sum --check`；发布后回读资产并比对大小 / SHA256 | 停止 |
| G12 | `verify_upgrade` 静态契约（同包名、同签名、版本码严格递增）；`publish needs: [build, verify_upgrade]` | 停止 |
| G13 | 发布后 Release 的 `prerelease=false`、资产数量 / 名称 / 大小 / digest 与本地一致；**正式版不得触发 prune** | 停止 |

CI 层**不执行**覆盖安装；`verify_upgrade` 只保留纯静态契约硬门禁，工作流输出明确标注「CI 层不执行覆盖安装，真机 D1–D10 未验证」。真机验证矩阵（D1–D10）仍全部保持“未验证”，不得以 CI 模拟器结果或静态推断替代；本次发布由用户明确授权，发行说明已如实标注未做真机回归。

### 流程顺序（两阶段，不可跳步）

```
S1 release/v3.0.14 分支提交晋升改动 → 开 PR
S2 Verify CI 全绿（LibCore 编译、Debug APK 构建、单测、Manifest 校验、体积测量）→ Squash 合入 main
S3 阶段一（候选构建）：在 main 上触发 release.yml，tag=v3.0.14、publish_release=false、approved_sha=<合并提交 SHA>；产出候选构件并核对 SHA256SUMS
S4 阶段二（正式上架）：二次触发 release.yml，tag=v3.0.14、publish_release=true，填入阶段一的 candidate_run_id 与 expected_checksums_sha256
S5 发布后回读 Release API：prerelease=false、四件套名称/大小/SHA256 吻合、旧正式版 v3.0.11 完好保留
```

> Release 正文由 CI 从 `RELEASE_NOTES.md` 复制生成；发布后若需勘误，只编辑 Release 正文，不覆盖 tag、不重发同名 Release。

---

# 历史归档：v3.0.10 正式版发布清单

> 下文保留 v3.0.10 正式版当时的发布流程和数值，仅作历史归档，不适用于当前 v3.0.14-preview.1 预览发布记录。旧 `.32`、`.33`、`2011` 等均是历史基线，不作为当前版本或门槛；当前规则以上文为准。

# wanBox 正式版发布清单

> 适用范围：`main` 上的正式版（非预览）发布。**本文不授权发布**：正式 Release 必须在获批 PR 合并、构建与资产核验完成后，再取得明确批准。
> **本次流程明确要求不删除旧版、不覆盖已有 tag、不强推**；历史文档中「每次发布覆盖上一次」的策略已废止，见第一节。

## 一、核心规则（与旧清单的差异）

| 项 | 旧清单 | 本版规则 |
|---|---|---|
| 旧 Release / tag | 发布后自动删除同渠道旧版 | **不删除**。正式版不触发 `prune_prereleases.py`；预览线的既有 prune 只作用于 prerelease，且不在正式发布流程中执行 |
| 覆盖升级声明 | 同签名即可声明「覆盖安装不丢数据」 | **禁止**。只有真机 D1–D10 通过后才可改写；当前统一用卸载 / 备份 / 迁移口径 |
| 版本码 | 每次 +1 | 必须**严格大于已发布 `.32`**：APK `> 10050`、模块 `> 2010` |
| 触发方式 | push tag 即发 | dispatch 必须提供 `approved_sha`，且 `github.sha` 等于获批 PR 合并提交；tag 已存在则拒绝 |
| 测试 | 仅构建 + 签名校验 | Go / Kotlin / Android / Python 全量测试 + 13 项发布门禁 |

## 二、版本规则（`nb4a.properties`）

| 字段 | 当前值 | 说明 |
|---|---|---|
| `VERSION_NAME` | `3.0.10` | tag 必须严格等于 `v$VERSION_NAME` → `v3.0.10` |
| `VERSION_CODE` | `2011` | APK `versionCode = 2011 × 5 = 10055`；Root 模块码 `2011` |
| `PRE_VERSION_NAME` | `3.0.10-preview.33` | **未发布候选**，只为满足 `PRE > FORMAL` 断言 |
| `PRE_VERSION_CODE` | `2012` | 必须 `> VERSION_CODE`（`preview.yml` 硬断言） |
| `SINGBOX_VERSION` | `v1.15.0-alpha.13-reF1nd` | 与 `.32` 一致 |

`×5` 换算与渠道判定由 `tools/version_code.py` 统一实现（`python3 -m unittest discover -s tools -p 'test_*.py'` 覆盖 9 个用例），两条渠道不得各自用 shell 算术推断。

## 三、发布前门禁清单（G1–G13）

| ID | 门禁 | 失败处理 |
|---|---|---|
| G1 | 4 个 `release.yml` 作业固定 `ubuntu-24.04`；Go `1.26.10`、JDK 17、NDK `25.0.8775105`；其余工作流同步固定 | 停止 |
| G2 | `libcore` 与 `rootmodule` 的 Go 测试（含 `-race`） | 停止 |
| G3 | `app:testOssDebugUnitTest`、`app:compileOssDebugAndroidTestKotlin`、`:core-domain:test`、Python 全量单测 | 停止 |
| G4 | tag == `v$VERSION_NAME`；tag 不存在；dispatch 的 `approved_sha` == `github.sha` | 停止 |
| G5 | `version_code.py --enforce`：APK `> 10050`、模块 `> 2010` | 停止 |
| G6 | `aapt`：包名 / `versionName` / `versionCode` / `minSdk=31` / `targetSdk=35`；`verify_apk_abi.py --thin`；`lib/` 条目为 0 | 停止 |
| G7 | `apksigner`：v2 验签 + 证书 `80ecee…21f6`；**下载 `.32` 实际 APK 做跨版本包名 / 签名 / 版本码比对** | 停止 |
| G8 | 正式模块 `--channel formal`；`module.prop` 为 `3.0.10` / `2011`；ZIP 完整性 | 停止 |
| G9 | 普通模块 ZIP 与带管理器 ZIP 均通过 `python3 -m zipfile -t` | 停止 |
| G10 | 资产白名单恰为 `{APK, 普通模块 ZIP, 带管理器 ZIP, SHA256SUMS}` | 停止 |
| G11 | 上传前 `sha256sum --check`；发布后回读资产并比对大小 / SHA256 | 停止 |
| G12 | `verify_upgrade` 静态契约（同包名、同签名、版本码严格递增）；`publish needs: [build, verify_upgrade]` | 停止 |
| G13 | 发布后 Release 的 `prerelease=false`、资产数量 / 名称 / 大小 / digest 与本地一致；**正式版不得触发 prune** | 停止 |

CI 层**不执行**覆盖安装：旧脚本依赖的外部 x86_64 共存 fixture （`lit008834-pixel/wanBoxForAndroid` 的 `v3.0.5-preview.2`）已返回 HTTP 404，且 runner 模拟器启动多次超时。`verify_upgrade` 因此只保留纯静态契约硬门禁，工作流输出明确标注「CI 层不执行覆盖安装，真机 D1–D10 未验证」。

## 四、真机验证（发布前置条件）

在支持的 Root Android 设备上记录设备型号、Android 版本 / API、Root 方案与版本、已装 `.32` 与目标的 SHA256，逐项执行：

| ID | 用例 | 当前状态 |
|---|---|---|
| D1 | 从 `.32` 覆盖升级（`adb install -r`） | 未验证 |
| D2 | 数据保留（节点 / 订阅 / 规则 / 分应用代理 / 偏好） | 未验证 |
| D3 | 安装与冷启动 | 未验证 |
| D4 | TUN / 核心重启、热重载、App 退出后代理继续 | 未验证 |
| D5 | 订阅与节点操作 | 未验证 |
| D6 | 网络异常恢复 | 未验证 |
| D7 | APK 与模块对应（同一发布页、模块码 2011） | 未验证 |
| D8 | 回滚到 `.32` | 未验证 |
| D9 | 卸载迁移路径（备份 → 卸载 → 安装 → 导入） | 未验证 |
| D10 | 无效证书真机侧（默认失败 / 显式开启才成功） | 未验证 |

`adb devices` 为空时，以上一律保持「未验证」，不得以 CI 模拟器结果或静态推断替代。

## 五、发布停止条件（任一命中即停止，不发布）

1. 版本码、签名、包名任一与 `.32` 不兼容或为降级。
2. 正式 APK 不是瘦管理器（含任何 `lib/` 条目）或 `--thin` 校验未通过。
3. TLS 默认证书验证被破坏、`globalAllowInsecure` 可在未显式授权下生效、无效证书回归测试失败。
4. 构建环境未固定、Go / Kotlin / Android / Python 测试未通过、资产白名单 / 两个 ZIP 完整性 / SHA256 任一门禁失败。
5. tag 未指向获批 PR 合并后的确切提交，或 tag 已存在。
6. 从 `.32` 覆盖升级的真机验证未完成，或数据保留、安装启动、TUN / 核心重启、订阅与节点操作、网络异常恢复任一项失败。
7. 发布后资产回读与本地 `SHA256SUMS` 不一致。

命中任一项时：保留已完成产物与证据，报告具体失败项与原因，**不删除旧版、不覆盖 tag、不强推**。

## 六、流程顺序（不可跳步）

```
S0 只读审查报告 / 版本与升级方案 / 测试矩阵        （已完成）
S1 独立分支最小修复                                （本分支）
S2 行为测试 + 全量测试 + 五份文档与发行说明
S3 开 PR（不合并）  → 等待明确审查并批准 PR
S4 合并 → 从合并提交构建 → 核验版本 / 签名 / 资产
S5 等待明确批准正式 Release → 打 tag / 发布 → 回读资产
```

## 七、文档同步清单

| 文件 | 本版更新内容 |
|---|---|
| `PREVIEW_NOTES.md` | 标注 `.33` 为未发布候选，保留 `.32` 历史正文 |
| `RELEASE_NOTES.md` | 正式版说明：版本与产物、环境要求、与 `.32` 差异、备份 / 卸载 / 迁移 / 回滚、未验证清单 |
| `CHANGELOG.md` | 补 `.32` 与 `3.0.10` 两条 |
| `README.md` | 徽章、版本核对段、`main` 与内核版本、Android / Root / ABI 要求 |
| `docs/current-version.zh-CN.md` | 重新核对已发布 Release 与源码候选的边界 |
| `docs/release-checklist.zh-CN.md` | 本文件：删除旧清理策略，补门禁与停止条件 |

> Release 正文由 CI 从 `RELEASE_NOTES.md` 复制生成；发布后若需勘误，只编辑 Release 正文（`PATCH /repos/{owner}/{repo}/releases/{id}`），不覆盖 tag、不重发同名 Release。
