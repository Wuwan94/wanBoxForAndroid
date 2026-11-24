# v3.0.17 正式版发布清单

本版 **v3.0.17 为正式版（非预览）**：正式线元数据由 `3.0.14 / 2026` 晋升为 `3.0.17 / 2035`（APK `versionCode=10175`、Root 模块码 `2035`），内容承接 3.0.17 预览线（preview.1–.4）与 PR #74 的全部修复。用户已明确指令“我新的提交通过了，你帮我合并发布正式版”（2026-11-19），本次按 `release.yml` **两阶段**流程执行：阶段一候选构建（`publish_release=false`）→ 核对 `SHA256SUMS` → 阶段二正式上架（`publish_release=true`，带 `candidate_run_id` 与 `expected_checksums_sha256`）。本版已于 2026-11-19T07:24:49Z 上架（候选 run `44474557410` → 正式 run `44477065390`），四件套字节与 SHA-256 已回读入本清单与[版本对照](current-version.zh-CN.md)。**Root 真机矩阵仍未执行**，不以自动化结果代替真机签核。

## 当前基线与不可变约束

- Android 12+ / minSdk 31 / arm64-v8a / targetSdk 35 / JDK 17 / Build Tools 35.0.1。
- Gradle 8.13 / AGP 8.11.1 / Kotlin 2.2.21 / KSP 2.2.21-2.0.4 / compileSdk 36 / Room 2.7.2。
- 内核 `reF1nd/sing-box v1.15.0-alpha.13-reF1nd`。内核和 targetSdk 阶段只评估，升级须另获确认。
- 模块拥有代理生命周期；管理器为瘦 APK，不引入多内核、不恢复 VPN、不重做 UI。
- 不删除或改名设置键、不改默认值；TLS 默认验证证书；日志不得含备份内容、节点凭据、订阅原文。
- 本版元数据：正式线 `3.0.17 / 2035`（APK `10175`、模块 `2035`）；预览线 `3.0.18-preview.4 / 2039` 已于 2026-11-23T12:55:32Z 作为稳定性预览发布，现已前移到 `3.0.19-preview.1 / 2040`（未发布候选；3.0.18 线已满 preview.4 上限而开新线，见下方「预览线候选」）；`SINGBOX_VERSION` 不变。
- 正式发布时保留的预览 `v3.0.17-preview.4`（`10174 / 2034`，源码 `906a6ea3`）当时仍在 Releases 中（正式发布不执行 `prune_prereleases.py`）；它已在 `v3.0.18-preview.1` 发布后按单预览策略清理，现为 404。

## 版本规则（`nb4a.properties`）

| 字段 | 本版值 | 说明 |
|---|---|---|
| `VERSION_NAME` | `3.0.17` | tag 必须严格等于 `v$VERSION_NAME` → `v3.0.17` |
| `VERSION_CODE` | `2035` | APK `versionCode = 2035 × 5 = 10175`；Root 模块码 `2035` |
| `PRE_VERSION_NAME` | `3.0.19-preview.1` | 预览线元数据，未发布候选（已发布预览为 `3.0.18-preview.4` / `a8cec2fa`） |
| `PRE_VERSION_CODE` | `2040` | 必须 `> VERSION_CODE`（`preview.yml` 硬断言）；预览 APK `10201`（已发布 preview.4 的 `2039` / `10199`） |
| `SINGBOX_VERSION` | `v1.15.0-alpha.13-reF1nd` | 与 v3.0.14 一致 |

`×5` 换算与渠道判定由 `tools/version_code.py` 统一实现，两条渠道不得各自用 shell 算术推断。升级方向硬门禁：APK `10175 > 10130`（v3.0.14）、`> 10174`（preview.4）、`> 10065`（v3.0.11）；模块 `2035 > 2026 / 2034 / 2013`。

## 发布前门禁清单（G1–G13，沿用正式门禁基线）

| ID | 门禁 | 失败处理 |
|---|---|---|
| G1 | 4 个 `release.yml` 作业固定 `ubuntu-24.04`；Go `1.26.10`、JDK 17、NDK `25.0.8775105`；其余工作流同步固定 | 停止 |
| G2 | `libcore` 与 `rootmodule` 的 Go 测试（含 `-race`） | 停止 |
| G3 | `app:testOssDebugUnitTest`、`app:compileOssDebugAndroidTestKotlin`、`:core-domain:test`、Python 全量单测 | 停止 |
| G4 | tag == `v$VERSION_NAME`；tag 不存在；dispatch 的 `approved_sha` == `github.sha` | 停止 |
| G5 | `version_code.py --enforce`：APK `> 10065`、模块 `> 2013` | 停止 |
| G6 | `aapt`：包名 / `versionName=3.0.17` / `versionCode=10175` / `minSdk=31` / `targetSdk=35`；`verify_apk_abi.py --thin`；`lib/` 条目为 0 | 停止 |
| G7 | `apksigner`：v2 验签 + 证书 `80ecee…21f6`；**下载 v3.0.11 实际 APK 做跨版本包名 / 签名 / 版本码比对** | 停止 |
| G8 | 正式模块 `--channel formal`；`module.prop` 为 `3.0.17` / `2035`；ZIP 完整性 | 停止 |
| G9 | 普通模块 ZIP 与带管理器 ZIP 均通过 `python3 -m zipfile -t` | 停止 |
| G10 | 资产白名单恰为 `{APK, 普通模块 ZIP, 带管理器 ZIP, SHA256SUMS}` | 停止 |
| G11 | 上传前 `sha256sum --check`；发布后回读资产并比对大小 / SHA256 | 停止 |
| G12 | `verify_upgrade` 静态契约（同包名、同签名、版本码严格递增）；`publish needs: [build, verify_upgrade]` | 停止 |
| G13 | 发布后 Release 的 `prerelease=false`、资产数量 / 名称 / 大小 / digest 与本地一致；**正式版不得触发 prune** | 停止 |

CI 层**不执行**覆盖安装；`verify_upgrade` 只保留纯静态契约硬门禁，工作流输出明确标注「CI 层不执行覆盖安装，真机 D1–D10 未验证」。真机验证矩阵仍全部保持“未验证”，不得以 CI 模拟器结果或静态推断替代。

## 流程顺序（两阶段，不可跳步）

```
S1 从最新 main 建 chore/release-v3.0.17：升版 nb4a.properties、同步七份发布文档与检查器
S2 Verify Android Build 全绿 → --merge 合入 main，记录合并提交 SHA（APPROVED_SHA）
S3 阶段一（候选构建）：在 main 上触发 release.yml，tag=v3.0.17、publish_release=false、approved_sha=<合并提交 SHA>
   产出 release-candidate-assets 并取得 SHA256SUMS 文件自身哈希（expected_checksums_sha256）
S4 阶段二（正式上架）：二次触发 release.yml，tag=v3.0.17、publish_release=true，
   填入阶段一的 candidate_run_id 与 expected_checksums_sha256
S5 发布后回读 Release API：prerelease=false、四件套名称/大小/SHA256 吻合、
   tag / targetCommitish / APPROVED_SHA / APK 内 version-control-info 四处同源、
   v3.0.14、v3.0.11 与 v3.0.17-preview.4 均完好保留
S6 收尾 PR：把候选/正式 run ID、实测字节与 SHA-256 回填到本清单与版本对照（已完成）
```

> Release 正文由 CI 从 `RELEASE_NOTES.md` 复制生成；发布后若需勘误，只编辑 Release 正文（`PATCH /repos/{owner}/{repo}/releases/{id}`），不覆盖 tag、不重发同名 Release。

## 本次发布证据

- PR #74（`fix/urltest-tier-failover-observations`）：`Verify Android Build` run `44465876305` 在 head `1553d0b2` 全绿，2026-11-19T06:07:25Z 以 `--merge` 合入，合并提交 `6a20a0ca7113decbe6155500dd9e70c79650bb5a`；远端分支已删除。
- 升版 PR #75：`Verify Android Build` run `44470604018`（head `b8a29c4`）全绿，2026-11-19T06:45:34Z 以 `--merge` 合入，合并提交 `d4e7c5d049d877660108ea11f7212a9f04824cd6`（即 `APPROVED_SHA`）。本地另跑 `tools` 125 项单测、`check_current_version_docs`、`version_code --enforce` / `--check-kotlin` / `--check-app-tag`、`check_core_gate`、`roo_check_repo_governance`、`git diff --check`，全部通过。
- 阶段一（候选，run `44474557410`，main @ `d4e7c5d0`）：`Native Build (LibCore)`、`Build OSS APK`、`Verify formal update contract`、`Prepare Release Candidate Assets (No Publish)` 全 success，`Publish Release` 按设计 skipped。候选 `SHA256SUMS` 文件自身 SHA-256 `c513a2c5ee823bec096e5b8413cefc6270c2c853c83d59c604d039065c66d1ca`。
- 阶段二（正式，run `44477065390`）：首次在 `Build OSS APK` 的「Run unit and Android tests」红于 plugins.gradle.org 解析 `org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21` / `kotlin-gradle-plugins-bom` 瞬时不可用，失败在发布断言之前，未创建任何 Release/tag；`gh run rerun --failed` 后四作业全绿，2026-11-19T07:24:49Z 发布。
- 发布后回读：`prerelease=false`、资产 4 个、名称/大小/digest 与 `SHA256SUMS` 一致；APK `6363231` B / `5e21c0bf…b76dba`、Root ZIP `18133473` B / `8e4d9d16…493ef4`、合一 ZIP `23776260` B / `2a7ea8b1…b50b29`、`SHA256SUMS` `315` B / `c513a2c5…066d1ca`；APK + 模块 `24496704` B（23.36 MiB）< `27262976` B。本地 `sha256sum -c` 三件 OK；APK manifest `versionCode=10175` / `versionName=3.0.17`，无 `lib/`；模块 `3.0.17 / 2035`；四处同源均为 `d4e7c5d0`；`v3.0.14`、`v3.0.11` 与 `v3.0.17-preview.4` 保留。

## 预览线候选（v3.0.19-preview.1，2026-11-24）

正式版 `v3.0.17` 仍是唯一正式版本；预览线因 3.0.18 已满每线 preview.4 的上限，按版本规则开新线 `3.0.19-preview.1` 为未发布候选，待按预览门禁（`preview.yml`，不走 `release.yml` 两阶段、不执行深度真机矩阵）发布。

- 内容：PR #90（合并提交 `f3d8a67`）新增 VLESS XHTTP 服务端入站——libcore 入站注册表覆盖、非 XHTTP 委托官方实现、XHTTP 服务端四种模式与资源上限、经自定义 JSON 配置启用；顺带修复 XHTTP 客户端请求头顺序与上传字节计数。官方 sing-box 内核与 `SINGBOX_VERSION` 未改。
- 元数据：`PRE_VERSION_NAME=3.0.19-preview.1`、`PRE_VERSION_CODE=2040`，派生 APK `10201` / 模块 `2040`，严格高于 preview.4 的 `10199 / 2039`。正式线 `3.0.17 / 2035` 不变。
- PR 门禁：PR #90 的 `Verify Android Build` run `45251550225`（head `35c9256a`）全绿。
- 未验证边界：Root 真机矩阵未执行（`runtime_regression: not_verified`）；与真实 Xray 客户端的端到端互通未验证（自动化为本项目客户端↔服务端往返）；服务端入站的 OpenSpec 变更文档待补。

## 预览线现状（v3.0.18-preview.4，2026-11-23）

正式版 `v3.0.17` 仍是唯一正式版本；预览线 `3.0.18-preview.4` 已按预览门禁（`preview.yml`，不走 `release.yml` 两阶段、不执行深度真机矩阵）发布。

- 内容：PR #84（合并提交 `00cfeed`）、PR #86（合并提交 `2add762`）、PR #87（合并提交 `f0f80b9`）三批复审修复——UDP 回包按来源校验与截止判定重写、门代次轮内变化仍删已 `rejected` 成员的 UDP 证据、导出/测速排除探测端点可测化、自启结果 `StateFlow` 可重放 + `AutoStartUiGate` 纯函数门 + `consume(id)` 呈现一次、锁粒度收窄与落库异常兑底、`UDPProbeUnobserved` 哨兵值与三条迟到回包用例。
- 元数据：`PRE_VERSION_NAME=3.0.18-preview.4`、`PRE_VERSION_CODE=2039`，派生 APK `10199` / 模块 `2039`，严格高于 preview.3 的 `10193 / 2038`。正式线 `3.0.17 / 2035` 与 `SINGBOX_VERSION` 不变。
- PR 门禁：PR #84 run `44898283085`（head `c6c6f038`）、PR #86 run `45152559766`（head `d6426d10`）、PR #87 run `45165265102`（head `0c1cbba`）、升版 PR #88 run `45170090295`（head `a14713f`）全绿；PR #87 首跑 `45162179175` 红在测试方法返回非 Unit，已用 `runBlocking<Unit>` 修掉。
- 溯源：轻量 tag / `targetCommitish` / APK `version-control-info.textproto` / `APPROVED_SHA` 四者同为 `a8cec2fa6676ec0c9a5fb3b7186a9c84e3e44893`（升版 PR #88 合并提交）；`Preview Build` run `45175762344` 四作业全绿，2026-11-23T12:55:32Z 上架。
- 资产：APK `6381547` B / `2856c050…1424d5`，Root ZIP `18151369` B / `d5a549c3…f4705b`，合一 ZIP `23807498` B / `5bc8c2fb…b39239`，`SHA256SUMS` `333` B / `cd10c434…4f0115`；APK + 模块 `24532916` B（23.40 MiB）低于 26 MiB 门禁。发布后本地重下四件套复核：`sha256sum -c` 三件 OK，三个 ZIP/APK `testzip` 完整，APK `10199` / `3.0.18-preview.4`、无 `lib/`，模块 `2039`，合一包内 `manager.apk` 与外部 APK 逐字节相同。
- 清理：发布 run 在 12:55:40Z 删除了 `v3.0.18-preview.3` 的 Release/tag；正式版 `v3.0.17` / `v3.0.14` / `v3.0.11` 未动。
- 未验证边界：Root 真机矩阵同前未执行（`runtime_regression: not_verified`）；UDP 探测为本机可控回显端点，非真实代理服务端往返；真机 `boot.json` 权限与开机行为未验。

## 预览线现状（v3.0.18-preview.3，2026-11-21，Release/tag 已在 preview.4 发布后清理）

正式版 `v3.0.17` 仍是唯一正式版本；预览线 `3.0.18-preview.3` 已按预览门禁（`preview.yml`，不走 `release.yml` 两阶段、不执行深度真机矩阵）发布。

- 内容：PR #83 收口 preview.2 复审四项——自管 UDP 回显端点（『设置 → 连接与核心 → UDP 回显端点』，默认留空即零流量，各层子组独立验证）、UDP-only 上层成员凭回显证据进候选、首次应用配置前回读自启设置、自启连点按最后一次选择落地。
- 元数据：`PRE_VERSION_NAME=3.0.18-preview.3`、`PRE_VERSION_CODE=2038`，派生 APK `10193` / 模块 `2038`，严格高于 preview.2 的 `10187 / 2037`。正式线 `3.0.17 / 2035` 与 `SINGBOX_VERSION` 不变。
- 溯源：轻量 tag / `targetCommitish` / APK `version-control-info.textproto` / `APPROVED_SHA` 四者同为 `8881dd1c59481b3d23ab3766fb21e3b651fa1d65`（PR #83 合并提交）；PR 门禁 `Verify Android Build` run `44879210932`（head `dbdbf0b`）全绿；`Preview Build` run `44882104257` 四作业全绿，2026-11-21T08:13:01Z 上架。
- 资产：APK `6374839` B / `e7832733…893507`，Root ZIP `18149655` B / `1fbfc62a…bdc280`，合一 ZIP `23797772` B / `2fe93950…88c80d`，`SHA256SUMS` `333` B / `2a6b50f3…296343`；APK + 模块 `24524494` B（23.39 MiB）低于 26 MiB 门禁。发布后本地重下四件套复核：`sha256sum -c` 三件 OK，两个 ZIP 完整，APK `10193` / `3.0.18-preview.3`、无 `lib/`，模块 `2038`。
- 清理：发布 run 在 08:13:09Z 删除了 `v3.0.18-preview.2` 的 Release/tag；正式版 `v3.0.17` / `v3.0.14` / `v3.0.11` 未动。
- 发布后进制：`main` 另合入 PR #84（合并提交 `00cfeed`，`Verify Android Build` run `44898283085` 全绿）的两项复审修复，**不在本版二进制中**，随下一预览发布。
- 未验证边界：Root 真机矩阵同前未执行；UDP 探测为本机可控回显端点，非真实代理服务端往返；真机 `boot.json` 权限与开机行为未验。

## 预览线现状（v3.0.18-preview.2，2026-11-20，Release/tag 已在 preview.3 发布后清理）

正式版 `v3.0.17` 仍是唯一正式版本；预览线 `3.0.18-preview.2` 已按预览门禁（`preview.yml`，不走 `release.yml` 两阶段、不执行深度真机矩阵）发布。

- 内容：PR #80（`fix/urltest,app`）收口 preview.1 源码复审与代码复审六项——观测按（成员, 网络）分账、UDP 探测只认真实往返回显、降级即作废旧回切资格、组关闭停拨备用、开机自启「下发→回读→落库」闭环、`boot.json` 三态与 `Boot()` 同口径。
- 元数据：`PRE_VERSION_NAME=3.0.18-preview.2`、`PRE_VERSION_CODE=2037`，派生 APK `10187` / 模块 `2037`，严格高于 preview.1 的 `10181 / 2036`。正式线 `3.0.17 / 2035` 与 `SINGBOX_VERSION` 不变。
- 溯源：轻量 tag / `targetCommitish` / APK `version-control-info.textproto` / `APPROVED_SHA` 四者同为 `e7f1f9366316974d8318e8708dcaaa669bc896a0`（升版 PR #81 合并提交）；`Preview Build` run `44728368529` 四作业全绿，2026-11-20T11:51:54Z 上架。升版 PR 的 `Verify Android Build` run `44723295827` 首跑红在 plugins.gradle.org 瞬时不可用，重跑后全绿。
- 资产：APK `6371663` B / `2ecd6d93…b8666b`，Root ZIP `18149031` B / `b191ff89…07be3b`，合一 ZIP `23794995` B / `96055b46…072295`，`SHA256SUMS` `333` B / `b199faa8…10d5be`；APK + 模块 `24520694` B（23.38 MiB）低于 26 MiB 门禁。
- 清理：发布 run 在 11:52:04Z 删除了 `v3.0.18-preview.1` 的 Release/tag；正式版 `v3.0.17` / `v3.0.14` / `v3.0.11` 未动。本版自身的 Release/tag 已在 preview.3 发布后（2026-11-21T08:13:09Z）清理。
- 未验证边界：Root 真机矩阵同前未执行；UDP 探测为本机可控回显端点，非真实代理服务端往返；该版 UDP 探测默认 `skipped`、不产生流量，自管端点的入口在 preview.3 才落地（『设置 → 连接与核心 → UDP 回显端点』，默认留空即零流量）；真机 `boot.json` 权限与开机行为未验。

## 预览线现状（v3.0.18-preview.1，2026-11-19，Release/tag 已在 preview.2 发布后清理）

正式版 `v3.0.17` 发布后，预览线 `3.0.18-preview.1` 已按预览门禁发布，本条只记录发布事实与遗留；预览本身不走 `release.yml` 两阶段，也不执行深度真机矩阵。

- 内容：PR #77（`urltest` 层级状态按 TCP/UDP 拆分）与 PR #78（App UI 对齐模块功能：开机自启、重启核心、配置回滚、顶栏模块版本）。
- 溯源：轻量 tag / `targetCommitish` / APK `version-control-info.textproto` / `APPROVED_SHA` 四者同为 `0a30baaf939a97b1941210609ce377e00d386157`；`Preview Build` run `44516333684` 四作业全绿，2026-11-19T11:55:25Z 上架。
- 门禁缺口：`0a30baa` 是 `preview.yml` 的热修直推提交，没有单独的 `Verify Android Build` 记录；其代码树与已全绿的 `4a8ab9b`（run `44506930043`）相同。后续 CI 热修也走分支 + PR，以保留门禁记录。
- 资产：APK `6366243` B / `459e5bee…bdacd4d`，Root ZIP `18134172` B / `b53e8653…3b5f398f`，合一 ZIP `23778516` B / `8d219e33…e2b6be794`，`SHA256SUMS` `333` B / `5ac21d47…062234e5`；APK + 模块 `24500415` B（23.37 MiB）低于 26 MiB 门禁。
- 清理：发布 run 在 11:55:35Z 删除了 `v3.0.17-preview.4` 的 Release/tag；正式版 `v3.0.17` / `v3.0.14` / `v3.0.11` 未动。
- 遗留：Root 真机矩阵未执行；两个新 UI 入口无自动化覆盖；观测层仍按 tag 共享（仅 `currentTier`/`tierGate` 按网络隔离）。

## 真机待签核矩阵（不能用模拟器代替）

| 维度 | 最低覆盖 | 待验证内容 |
|---|---|---|
| Root | Magisk / KernelSU / APatch 各至少 1 台 | 安装、启停、禁用、卸载后路由与规则清理，无残留断网 |
| 系统 | Android 12 与主流高版本各至少 1 台 | 覆盖安装数据保留、TUN 建立 / 重建 / 关闭 |
| 稳定性 | 杀核心、节点全失效、网络频繁切换 | 看门狗最多 5 次，退避 1/2/4/8/16 秒；五类断线原因落盘，不无限重启 |
| 启动测速 | 冷启动首连、单节点及批量 | URLTest 5 秒总预算，超时 1–60 秒生效，取消不污染结果 |
| 分层调度 | 层级故障转移与回切 | 同层失败后向低层容灾、`currentTier` 下移、连续 3 轮新成功且 60 秒无失败才回切 |
| 更新 | 正常、断网、校验失败 | 校验通过才提示安装；失败清理，旧版仍可用 |
| 数据 | 新旧版本双向备份导入 | 设置键、节点、订阅完整；BalancerBean v5→v7 记录升级与降级导出 |

全部保持“未验证”。本版未执行 Root 真机矩阵；发行说明如实标注未做真机回归。

## 发布停止条件（任一命中即停止，不发布）

1. 版本码、签名、包名任一与 v3.0.11 / v3.0.14 不兼容或为降级。
2. 正式 APK 不是瘦管理器（含任何 `lib/` 条目）或 `--thin` 校验未通过。
3. TLS 默认证书验证被破坏、`globalAllowInsecure` 可在未显式授权下生效、无效证书回归测试失败。
4. 构建环境未固定、Go / Kotlin / Android / Python 测试未通过、资产白名单 / 两个 ZIP 完整性 / SHA256 任一门禁失败。
5. tag 未指向获批 PR 合并后的确切提交，或 tag 已存在。
6. 阶段二缺少同一提交的成功候选 run 或 `SHA256SUMS` 文件哈希与候选不一致。
7. 发布后资产回读与本地 `SHA256SUMS` 不一致，或 `prerelease` 不为 `false`。

命中任一项时：保留已完成产物与证据，报告具体失败项与原因，**不删除旧版、不覆盖 tag、不强推**。

---

# 历史归档：v3.0.17 预览线（preview.1–.4）发布记录

> 以下为 3.0.17 预览线当年的分阶段验收与预览发布门禁记录，保留原始语境；不适用于本次正式发布，数值与 run ID 不得当作本轮结果。

## 阶段与验收

| 阶段 | 范围 | 验收与输出 |
|---|---|---|
| 1 文档一致性 | README、徽章、版本对照与本清单 | README 快照已更新：正式版 / 已发布预览分别标注；链接和工具链一致；不把历史证据当新结果 |
| 2 URLTest 回归 | 异步首轮 5 秒预算、全员不可用回退、取消不写失败结果、关闭停止探测 | 补测试并通过；节点选择读写仍共用同一把锁；真机结果单列待签核 |
| 3 更新器异常路径 | 断网、磁盘满、SHA256 不符、资产不全；普通及 with-manager 完整文件名匹配 | 可复现证据；临时文件及本次包清理、不弹安装、错误可读；校验后才由用户安装 |
| 4 P1 清尾 | baseline-prof.txt、>1000 节点 DiffUtil 与已定位主线程读取、图标、工具页；跨进程失效只设计 | 每项独立提交可回滚；基线与性能数据真实记录，不伪造设备结果，不实现设置缓存 |
| 5 内核与 targetSdk 评估 | 上游 / fork 新预览、TUN 栈 / stack 弃用、Android auto_redirect、targetSdk 36 行为影响 | 两份评估文档；不擅自升级，升级动作另等确认 |
| 6 版本与发布材料 | 顺延版本码；发行说明按更新 / 安装版本 / 自动化及边界三部分 | README、元数据和发行说明一致；无真机签核时保留“未做真机回归” |

## 预览发布前硬门槛（preview.4）

- [x] README 快照、版本对照与中文发行说明已对齐已发布 v3.0.17-preview.4；对应下载入口指向有效 Release。
- [x] `Verify Android Build` run `44372679147`（升版 PR #72 头 `b7b35de`）通过 instrumentation、迁移、manifest 与体积门禁；其 `Build LibCore` 作业同时跑通 `go test -race`、`go vet`、Python 工具 124 项与版本文档一致性检查。
- [x] `Preview Build` run `44378329740` 四个 job 全部成功并在 2026-11-18T19:38:07Z 发布；发布 job 断言 `APPROVED_SHA=906a6ea3`，tag、`targetCommitish`、APK 内 `version-control-info.textproto` 四处同源。
- [x] 四件套齐全且与公开 `SHA256SUMS` 一致：APK `6363791` B、Root ZIP `18131042` B、合一 ZIP `23774566` B、`SHA256SUMS` `333` B；APK + Root ZIP `24494833` B（23.36 MiB）低于 26 MiB。
- [x] 发布后按单预览策略清理 preview.3；稳定版 v3.0.14 与 v3.0.11 保留。
- [x] 未宣称跨版本备份互通或 Root 真机通过；preview.3 未执行 Root 真机矩阵。

任何一项发布前门禁失败都应停止发布，保留构件、日志和失败现场。
