# 发布、源码与版本对照

核对日期：2026-11-25。发布状态以 GitHub Release、tag 与实际下载资产为准；源码元数据本身不构成已发布资产的核验。

| 对象 | 版本与状态 | 版本码及来源 |
|---|---|---|
| 已发布正式版 | [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17)，非 prerelease（Latest）；由 3.0.17 预览线晋升，升版 PR #75 合并提交 `d4e7c5d049d877660108ea11f7212a9f04824cd6`，`release.yml` 两阶段发布（候选 run `44474557410` → 正式 run `44477065390`，2026-11-19T07:24:49Z 上架） | APK / Root 模块 `10175 / 2035`（源码正式线 `VERSION_NAME=3.0.17`、`VERSION_CODE=2035`）；四件套字节与 SHA-256 见下文 |
| 已发布预览版 | [v3.0.19-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.19-preview.2)，prerelease，tag/资产提交 `9f4299e50aa88bc6f385daf4663828e07528036d`（升版 PR #95）；`Preview Build` run `45426378108`（#63）于 2026-11-24T15:55:00Z 发布 | APK `versionCode=10207` / Root 模块码 `2041`（`PRE_VERSION_NAME=3.0.19-preview.2`、`PRE_VERSION_CODE=2041`）；四件套字节与 SHA-256 见下文 |
| 上一正式版 | [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14)，非 prerelease，保留；tag `fa9a9194ff89834e574640a0ec8e0d5d973cc024` | APK / Root 模块 `10130 / 2026`（历史资产核验记录，归档语境） |
| 源码预览线 | `3.0.19-preview.2`（与最近已发布预览一致，含 PR #94） | `PRE_VERSION_NAME=3.0.19-preview.2`、`PRE_VERSION_CODE=2041`；APK `versionCode=10207`、Root 模块码 `2041` |

源码元数据正式线为 `VERSION_NAME=3.0.17`、`VERSION_CODE=2035`；已发布预览 `3.0.19-preview.2 / 2041` 由 PR #95 升版，Preview Build run `45426378108`（#63）于 2026-11-24T15:55:00Z 上架，tag 与发布资产提交同为 `9f4299e50aa88bc6f385daf4663828e07528036d`，APK `10207`、模块 `2041`。preview.2 发布后，上一预览 `v3.0.19-preview.1` 的 Release 与 tag-ref API 均返回 404（已清理）；先前的 `v3.0.18-preview.4` 也已在 preview.1 发布时清理。正式版 `v3.0.17`、`v3.0.14`、`v3.0.11` 保留；`SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 未改变。

## v3.0.19-preview.2 已发布预览四件套（GitHub Release API asset digest）

来源：[GitHub Release v3.0.19-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.19-preview.2)，`Preview Build` run `45426378108`（#63）于 2026-11-24T15:55:00Z 发布；tag 与资产提交均为 `9f4299e50aa88bc6f385daf4663828e07528036d`（升版 PR #95）。下表字节数与 SHA-256 来自 GitHub Release API 的 asset digest。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.19-preview.2-arm64-v8a-release.apk` | 6381543 | `c9884dcc06a38fb620ba0f6684ba545730f07987de5b2701306e83373c6cef1e` |
| `wanbox-root-module-arm64-v8a.zip` | 18308156 | `e070088063692787f184068aec32082468c12a317f4b573ad38cc10bf0f059c6` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23965220 | `8fcaa9d3f68186daedb97d9a514fdfc52235d535c6e76a008340129e184c7d48` |
| `SHA256SUMS` | 333 | `52eefd6f5a091193c691fee2187370760681d45bc6284091024542251d85abc2` |

APK 与普通 Root 模块合计 `24689699` 字节（23.55 MiB），低于 `27262976` 字节（26 MiB）上限，余量 `2573277` 字节（2.45 MiB）；合一包 `23965220` 字节（22.86 MiB）。互通复核见 [Xray 记录](testing/xhttp-xray-interop.md)：这是含后续 `stream-one` 客户端修复的本地分支测试，不是已发布 preview.2 资产或真机测试结果。

### 历史发布摘要（此前预览，不代表当前发布状态）

预览线由已发布的 `3.0.18-preview.3 / 2038`（2026-11-21T08:13:01Z）前移到 `3.0.18-preview.4 / 2039` 并已完成两阶段之外的预览发布（轻量 tag，发布 job 断言 `APPROVED_SHA=a8cec2fa` 并回读 tag），派生 APK `10199`、模块 `2039`。本版收口 preview.3 之后 `main` 依次合入的三批复审修复：PR #84（合并提交 `00cfeedddc07249cccd8a8344eb1bf7df97d75dc`，head `c6c6f038`，`Verify Android Build` run `44898283085` 全绿，2026-11-21T12:16:42Z）、PR #86（合并提交 `2add76212e54c54b6d4dda02b1077ab373352e24`，head `d6426d10`，run `45152559766` 全绿，2026-11-23T10:33:34Z）、PR #87（合并提交 `f0f80b92d597a334440c25171648b19b76573b59`，head `0c1cbba`，run `45165265102` 全绿，2026-11-23T11:57:32Z），升版 PR #88 的 run `45170090295`（head `a14713f`）全绿。

预览线已由已发布的 `3.0.18-preview.4 / 2039` 前移到 `3.0.19-preview.1 / 2040` 并完成预览发布（3.0.18 线已满每线 preview.4 的上限，按版本规则开新线；轻量 tag，发布 job 断言 `APPROVED_SHA=94a9c9b` 并回读 tag），派生 APK `10201`、模块 `2040`。preview.4 之后 `main` 的内容变更为 PR #90（合并提交 `f3d8a67a443b2c450ba404683d97ef51f8b26d9f`，head `35c9256a`，`Verify Android Build` run `45251550225` 全绿，2026-11-23T20:12:24Z）新增 VLESS XHTTP 服务端入站——libcore 在入站注册表新增 VLESS 注册项，非 XHTTP 传输委托官方实现，XHTTP 服务端支持四种模式、HTTP/1.1 与 HTTP/2/h2c、TLS 与资源上限，经现有节点自定义 JSON 配置路径启用；官方 sing-box 内核与 `SINGBOX_VERSION` 未改，顺带修复 XHTTP 客户端请求头顺序与上传字节计数；服务端入站的 OpenSpec 变更已由 PR #92 补齐（`support-xhttp-inbound`，合并提交 `12f657478ef3a6caf082211d415e215f23fe1d31`）。四件套：APK `6381543` B / `e91b4a81…196507`、普通 Root ZIP `18295938` B / `95a2ec5f…b920ef`、合一 ZIP `23954760` B / `0b8dd917…88fd47`、`SHA256SUMS` `333` B，APK + 普通模块 `24677481` B（23.53 MiB）→ PASS。

本版内容（v3.0.18-preview.4）：UDP 回显探测的回包按来源校验（来源不可核对记 `unverified`，来源不一致或内容不符记 `rejected`），截止判定抽成 `udpProbeContextOutcome`——父上下文取消优先 `canceled`、单探针超时 `unverified`，并在关联/发送/读取后各复核一次（PR #86）；门代次轮内变化时仍删除已 `rejected` 成员的 UDP 证据，「导出/一次性测速排除探测端点」判定抽成可测函数并补四种组合 JVM 回归（PR #84）；开机自启结果改为 `MutableStateFlow` 发布 `Idle`/`Pending`/`Finished`、`AutoStartUiGate.accepts(...)` 纯函数门补 8 条用例、`consume(id)` 呈现一次即清、失败在页面有效时 Toast 一次，锁只保护 `sequence` 与内存态（Room 读写与 IPC 移到锁外）、`settle()` 落库异常兜底与 `CoroutineExceptionHandler`（PR #86 + #87）；`udpProbeContextOutcome` 未停止返回值改零值 `UDPProbeUnobserved`，补三条 UDP 迟到合法回包行为级用例与 spec 来源效力边界（PR #87）。四件套见下文。

v3.0.17 晋升内容：3.0.17 预览线（preview.1–.4）的全量变更 —— OwnBox 移植 B1–B4/B7a–c（含 O9a 落地 IP 缓存按实际在跑节点失效）、NetProxy 线 B5/B6/N6/B8、preview.3 的 URLTest 预算到期回切与诊断包 Android 12+ 读取、preview.4 的层级回切门与过期候选兜底，以及 PR #74 的分层向下容灾与回切门新观测计数；逐版记录见 [CHANGELOG.md](../CHANGELOG.md)。

preview.1 内容：修复 preview.4 复审发现的 P1 回归——存在悬空出站引用时「放弃未完成编辑」在锁内重建时反复撞上出站告警，恢复标记永不释放；现由同一次构建快照算出的告警替代该次重建确认，正常启动/重连/重载仍要求确认，提交点（确认成功才清标记）未放宽。同批修复同一状态下的第二层缺陷：配置构建在 IO 线程创建 Toast 抛 `NullPointerException`，使包括用户确认后的重建整条路径失败，两处规则告警改为主线程分发。

preview.3 内容（v3.0.18 线）：收口 preview.2 复审提出的四项缺陷（PR #83，合并提交 `8881dd1c59481b3d23ab3766fb21e3b651fa1d65`，25 个文件 +332/−78，`Verify Android Build` run `44879210932` 在 head `dbdbf0b` 全绿）——「设置 → 连接与核心 → UDP 回显端点」可填自管数字 IP 与端口（默认留空即零流量，fail-closed）、各优先级层子组独立验证；UDP-only 上层成员凭回显证据进候选、不再借用 TCP 记录；首次应用配置前回读开机自启设置；自启连点按最后一次选择落地。四件套见下文。

preview.2 内容（v3.0.18 线）：收口 preview.1 源码复审与代码复审的六项缺陷（PR #80，合并提交 `ad032285b3ff181677ed6c0ea9c88a7bb4e482a6`，22 个文件 +2887/−167，`Verify Android Build` run `44717598549` 全绿）——URLTest 观测按（成员, 网络）分账、UDP 探测只认真实往返回显、降级即作废旧回切资格、组关闭停拨备用、开机自启「下发 → 回读 → 落库」闭环、`boot.json` 不可用时状态与 `Boot()` 同口径。四件套见下文。

preview.2 内容（v3.0.17 线）：两路借鉴移植批次合入 main（PR #67 port OwnBox 线：B1 核心稳定性与故障转移、B2 订阅自动更新、B3 智能分流与规则集校验、B4 冷启动秒连、B7a/B7b/B7c 策略组混搭/手动锁定/优先级调度；PR #68 feat NetProxy 线：B5 停核可编辑与脱敏诊断包、B6 按网络规则与共享/分应用、N6 路由规则指定策略组、B8 热更新契约测试与 pref 9001 守卫泄漏修复）后的首个预览。O8 自选统一引擎与 N8 eBPF 入站本轮均未实施；O5 主进程调度迁移待用户拍板，未做。

preview.4 由 PR #62（合并提交/tag `9283ccb0886805bddbd6856835d23a548f20b9d6`，2026-11-17T03:58:26Z）发布：PR 验证 `Verify Android Build` run `44011037428` 在 PR head `1ce7d7f` 通过，main `9283ccb` 上的 `Preview Build` run `44013891166`（2026-11-17T03:58:28Z → 04:19:07Z）四个 job 全部成功并在 04:18:56Z 发布 Release；tag、`targetCommitish`、APK 内 `version-control-info.textproto` 与发布 job 的 `APPROVED_SHA` 四者同为 `9283ccb`，上一版「tag 指向旧代码、二进制来自另一提交」的溯源断裂已消除。该 Release/tag 已在 preview.1 发布后由 `prune_prereleases.py --keep-tag v3.0.17-preview.1` 删除。

preview.3 由 PR #60（tag `fa6b95b9a39d2d441280021968d4b914eb5b20d3`，`verify-android` run `43897997343`）首发，`Preview Build` run `43906481811` 发布。随后 PR #61（合并提交 `000d3023571153d975d2868eeb7083da57b1a9e4`，`verify-android` run `43934149499`）修复策略组编辑与备份恢复，并以受审查流程重发同名资产：`Preview Build` run `43939217515` 在 `000d302` 产出候选，`Replace Existing Preview Assets` prepare run `43942306964` 与 execute run `43942569933` 替换 Release 资产。tag 未移动，因此 preview.3 的二进制来自 `000d302` 而 tag 指向 `fa6b95b`；preview.4 从 main 重新出版，恢复了源码 tag 与二进制同源。preview.2 及更早预览的 Release/tag 已按单预览策略清理，其历史证据见下文归档。正式版 v3.0.14 与上一正式版 v3.0.11 保留。

## v3.0.17 正式版四件套（文件名与发布回读数据）

正式版 Release 提供以下 4 个资产，必须成套使用。字节数与 SHA-256 取自 GitHub Release API 的 asset digest，本地下载后 `sha256sum -c SHA256SUMS` 三件均 OK：

| 文件 | 说明 |
|---|---|
| `wanBoxForAndroid-3.0.17-arm64-v8a.apk` | 瘦管理器（APK `versionCode=10175`） |
| `wanbox-root-module-arm64-v8a.zip` | Root 模块（模块码 `2035`） |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 模块 + 管理器合一变体 |
| `SHA256SUMS` | 上述三项的校验和 |

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.17-arm64-v8a.apk` | 6363231 | `5e21c0bfd9853b5a639964327d4362079a8bb280f33751d72477359fd3b76dba` |
| `wanbox-root-module-arm64-v8a.zip` | 18133473 | `8e4d9d1607c4b3606480188e5421f35ed912179aec85acc857f50a6599493ef4` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23776260 | `2a7ea8b16a7b031752c888218ff22880ad036bc495a8636a09ffbcd591b50b29` |
| `SHA256SUMS` | 315 | `c513a2c5ee823bec096e5b8413cefc6270c2c853c83d59c604d039065c66d1ca` |

APK 与普通 Root 模块合计 `24496704` 字节（23.36 MiB），上限 `27262976` 字节（26 MiB），余量 `2766272` 字节（2.64 MiB）→ PASS；带管理器合一包 22.67 MiB，同样低于 26 MiB。

发布方式：`release.yml` 两阶段。阶段一候选 run `44474557410`（`publish_release=false`，`Publish Release` skipped）产出 `release-candidate-assets`，`SHA256SUMS` 文件自身 SHA-256 为 `c513a2c5…066d1ca`；阶段二正式 run `44477065390` 以 `candidate_run_id=44474557410` 与 `expected_checksums_sha256=c513a2c5…066d1ca` 上架（该 run 首次因 plugins.gradle.org 解析 `kotlin-gradle-plugin:2.0.21` 瞬时不可用红在 `Build OSS APK`，未创建任何 Release/tag，`gh run rerun --failed` 后四个作业全绿）。

发布后回读结果：`prerelease=false`、`draft=false`，`make_latest` 生效；资产恰为 4 个，名称、大小与 digest 与本地 `SHA256SUMS` 逐项一致；APK 二进制 manifest 解析为 `versionCode=10175`、`versionName=3.0.17`、包名 `com.lit008834.pixel.wanboxforandroid`，无 `lib/` 原生库；模块 `module.prop` 为 `3.0.17 / 2035`；合一包内 `manager.apk` 与外部 APK 同为 `6363231` 字节；APK 内 `META-INF/version-control-info.textproto` 的 `revision` 为 `d4e7c5d049d877660108ea11f7212a9f04824cd6`，与 tag、`targetCommitish`、发布 job 的 `APPROVED_SHA` 四处同源；证书 SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6` 由 CI 跨版本比对（G7）以同一批字节断言（本机无 `apksigner`，未独立复验签名，字节 digest 与候选一致）。本次未执行 Magisk / KernelSU / APatch 真机矩阵，不以自动化结果代替真机签核。

本次发布未执行任何清理：`v3.0.14`、`v3.0.11` 与 `v3.0.17-preview.4` 均保留。

## 历史归档：v3.0.14 正式版四件套与发布回读数据

以下为 v3.0.14 发布时的回读数据（GitHub Release API 的字节数与 SHA-256 digest），已与发布页逐项核对：

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

## v3.0.18-preview.4 已发布预览四件套（文件名与发布回读数据）

来源：[GitHub Release v3.0.18-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.4)，轻量 tag/commit `a8cec2fa6676ec0c9a5fb3b7186a9c84e3e44893`（`git/ref/tags` 的 object.type 为 commit），`Preview Build` run `45175762344` 的四个作业全部成功，于 2026-11-23T12:55:32Z 发布（prerelease，`targetCommitish=a8cec2fa`）。字节数与 SHA-256 取自 GitHub Release API 的 asset digest；发布后重新下载四件套，本地 `sha256sum -c SHA256SUMS` 三件均 OK。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.18-preview.4-arm64-v8a-release.apk` | 6381547 | `2856c050fd6bcc9d3f48f242797d7f4c1b7f6d202a4e973ea88a91a2441424d5` |
| `wanbox-root-module-arm64-v8a.zip` | 18151369 | `d5a549c3d38e8082cca843d4f3274453d717998b66d0875424c9ba05f1f4705b` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23807498 | `5bc8c2fbcb40151c28d10286fc0611d668d6cbc3bd82f77e65a91a8b9db39239` |
| `SHA256SUMS` | 333 | `cd10c43433502610b80e524024e1bf54d31ffd20d9c02f226f8deaf7ee4f0115` |

APK 与普通 Root 模块合计 `24532916` 字节（23.40 MiB），`tools/size_budget.py --enforce-limit` 上限 `27262976` 字节（26 MiB），余量 `2730060` 字节（2.60 MiB）→ PASS；带管理器合一包 `23807498` 字节（22.70 MiB）。三个 ZIP/APK 通过 `zipfile.testzip()` 无损坏；`module.prop` 为 `version=3.0.18-preview.4`、`versionCode=2039`、`author=雾晚`。本地解析 APK（二进制 `AndroidManifest.xml`）：包名 `com.lit008834.pixel.wanboxforandroid`、`versionName=3.0.18-preview.4`、`versionCode=10199`、`minSdk=31`、`targetSdk=35`、`lib/` 条目 0 个（瘦管理器）；`META-INF/version-control-info.textproto` 的 `revision` 为 `a8cec2fa6676ec0c9a5fb3b7186a9c84e3e44893`，与 tag 对象、`targetCommitish`、发布 job 的 `APPROVED_SHA` 四处同源。合一包与普通包唯一差异是多了 `manager.apk` 与 `package-manifest.json` 的 `withManager` 标志，`manager.apk` 与外部 APK 逐字节相同。证书 SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`，与 v3.0.14 / v3.0.17 同一张（本机无 `apksigner`，签名由 CI 断言，未独立复验）。发布后 12:55:40Z `prune_prereleases.py` 删除 `v3.0.18-preview.3` 的 Release/tag；正式版 `v3.0.17` / `v3.0.14` / `v3.0.11` 保留。CI 自标 `runtime_regression: not_verified`，本版未执行 Magisk / KernelSU / APatch 真机矩阵。

已知边界：UDP 探测用的是本机可控回显端点，不是真实代理服务端往返；真机上 `boot.json` 的权限与开机行为未验证。

## 历史归档：v3.0.18-preview.3 已发布资产快照（Release/tag 已在 v3.0.18-preview.4 发布后清理）

来源：[GitHub Release v3.0.18-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.3)，轻量 tag/commit `8881dd1c59481b3d23ab3766fb21e3b651fa1d65`（`git/ref/tags` 的 object.type 为 commit），`Preview Build` run `44882104257` 的四个作业全部成功，于 2026-11-21T08:13:01Z 发布（prerelease，`targetCommitish=8881dd1c`）。字节数与 SHA-256 取自 GitHub Release API 的 asset digest；发布后重新下载四件套，本地 `sha256sum -c SHA256SUMS` 三件均 OK。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.18-preview.3-arm64-v8a-release.apk` | 6374839 | `e78327338864e49a4048e9a58a64e78a002db322cef9be67e3383a86af893507` |
| `wanbox-root-module-arm64-v8a.zip` | 18149655 | `1fbfc62ab3751644fafc5f57cd511c3413b8f6b40e3ee621d755ae0fc1bdc280` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23797772 | `2fe93950bfd952f1f70ee5102129a36815ce141d1e8a477dea0ff0e7c888c80d` |
| `SHA256SUMS` | 333 | `2a6b50f34602ab0ac418cec445280dfded44bc09263ee7dd1df9656e74296343` |

APK 与普通 Root 模块合计 `24524494` 字节（23.39 MiB），`tools/size_budget.py --enforce-limit` 上限 `27262976` 字节（26 MiB），余量 `2738482` 字节（2.61 MiB）→ PASS；带管理器合一包 `23797772` 字节（22.70 MiB）。两个 ZIP 通过 `zipfile.testzip()` 无损坏；`module.prop` 为 `version=3.0.18-preview.3`、`versionCode=2038`、`author=雾晚`。本地解析 APK（二进制 `AndroidManifest.xml`）：包名 `com.lit008834.pixel.wanboxforandroid`、`versionName=3.0.18-preview.3`、`versionCode=10193`、`minSdk=31`、`targetSdk=35`、`lib/` 条目 0 个（瘦管理器）；`META-INF/version-control-info.textproto` 的 `revision` 为 `8881dd1c59481b3d23ab3766fb21e3b651fa1d65`，与 tag 对象、`targetCommitish`、发布 job 的 `APPROVED_SHA` 四处同源。合一包与普通包唯一差异是多了 `manager.apk` 与 `package-manifest.json` 的 `withManager` 标志，`manager.apk` 与外部 APK 逐字节相同。证书 SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`，与 v3.0.14 / v3.0.17 同一张（本机无 `apksigner`，签名由 CI G7 断言，未独立复验）。发布后 08:13:09Z `prune_prereleases.py` 删除 `v3.0.18-preview.2` 的 Release/tag；正式版 `v3.0.17` / `v3.0.14` / `v3.0.11` 保留。CI 自标 `runtime_regression: not_verified`，本版未执行 Magisk / KernelSU / APatch 真机矩阵。

已知边界：UDP 探测用的是本机可控回显端点，不是真实代理服务端往返；真机上 `boot.json` 的权限与开机行为未验证。发布后 `main` 另合入 PR #84 的两项复审修复（`00cfeed`），不在本版二进制中。

## 历史归档：v3.0.18-preview.2 已发布资产快照（Release/tag 已在 v3.0.18-preview.3 发布后清理）

来源：[GitHub Release v3.0.18-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.2)，轻量 tag/commit `e7f1f9366316974d8318e8708dcaaa669bc896a0`（`git/ref/tags` 的 object.type 为 commit），`Preview Build` run `44728368529` 的四个作业全部成功，于 2026-11-20T11:51:54Z 发布（prerelease，`targetCommitish=e7f1f936`）。字节数与 SHA-256 取自 GitHub Release API 的 asset digest，本地下载后 `sha256sum -c SHA256SUMS` 三件均 OK。

本版内容：PR #80（`fix(urltest,app): 收口 v3.0.18-preview.1 复审四项`，合并提交 `ad032285b3ff181677ed6c0ea9c88a7bb4e482a6`，2026-11-20T10:53:47Z，22 文件 +2887/−167，`Verify Android Build` run `44717598549` 全绿）——观测按（成员, 网络）分账、UDP 探测只认真实往返回显、降级即作废旧回切资格、组关闭停拨备用、开机自启「下发 → 回读 → 落库」闭环、`boot.json` 三态与 `Boot()` 同口径。升版 PR #81 的 `Verify Android Build` run `44723295827` 首跑红在 plugins.gradle.org 解析 `kotlin-gradle-plugin:2.0.21` 瞬时不可用（未创建 tag/Release），`gh run rerun --failed` 后全绿。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.18-preview.2-arm64-v8a-release.apk` | 6371663 | `2ecd6d9301b5e8240bd442a018bb4d64ae052fa4ac4ee1549e7af29217b8666b` |
| `wanbox-root-module-arm64-v8a.zip` | 18149031 | `b191ff8923f8ad8101337993e63fcd122e5ef002faa7e67d5a70096cfc07be3b` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23794995 | `96055b461db23ec094c20ec54b5861b4b694e2ede1a95b503612664da3072295` |
| `SHA256SUMS` | 333 | `b199faa8bde14695dbe3a741a6c1aab773db759b278ea41d7d214c532110d5be` |

APK 与普通 Root 模块合计 `24520694` 字节（23.38 MiB），`tools/size_budget.py --enforce-limit` 上限 `27262976` 字节（26 MiB），余量 `2742282` 字节（2.62 MiB）→ PASS；带管理器合一包 `23794995` 字节（22.69 MiB）。两个 ZIP 通过 `zipfile.testzip()` 无损坏；`module.prop` 为 `version=3.0.18-preview.2`、`versionCode=2037`、`author=雾晚`。CI 断言 APK 包名 `com.lit008834.pixel.wanboxforandroid`、`versionName=3.0.18-preview.2`、`versionCode=10187`、`lib/` 条目 0 个（瘦管理器）；`META-INF/version-control-info.textproto` 的 `revision` 为 `e7f1f9366316974d8318e8708dcaaa669bc896a0`，与 tag 对象、`targetCommitish`、发布 job 的 `APPROVED_SHA` 四处同源。证书 SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`，与 v3.0.14 / v3.0.17 同一张（本机无 `apksigner`，签名由 CI G7 断言，未独立复验）。合一包与普通包唯一差异是多了 `manager.apk` 与 `package-manifest.json` 的 `withManager` 标志，`manager.apk` 与外部 APK 逐字节相同。发布后 11:52:04Z `prune_prereleases.py` 删除 `v3.0.18-preview.1` 的 Release/tag；正式版 `v3.0.17` / `v3.0.14` / `v3.0.11` 保留。CI 自标 `runtime_regression: not_verified`，本版未执行 Magisk / KernelSU / APatch 真机矩阵。

已知边界：UDP 探测用的是本机可控回显端点，不是真实代理服务端往返；该版 UDP 探测默认 `skipped`、不产生流量，自管端点的入口在 preview.3 才落地（『设置 → 连接与核心 → UDP 回显端点』，默认留空即零流量，fail-closed）；真机上 `boot.json` 的权限与开机行为未验证。本版自身的 Release/tag 已在 `v3.0.18-preview.3` 发布后（2026-11-21T08:13:09Z）由 `prune_prereleases.py` 清理，资产链接现返回 404，下表仅作历史记录。

## 历史归档：v3.0.18-preview.1 已发布资产快照（Release/tag 已在 v3.0.18-preview.2 发布后清理）

来源：[GitHub Release v3.0.18-preview.1](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.1)，轻量 tag/commit `0a30baaf939a97b1941210609ce377e00d386157`（`git/ref/tags` 的 object.type 为 commit，无 annotated tag 对象），`Preview Build` run `44516333684` 的四个作业（`Native Build (LibCore)`、`Build OSS APK`、`Candidate cold-start measurement (emulator only)`、`Publish Preview Release`）全部成功，于 2026-11-19T11:55:25Z 发布（prerelease，`targetCommitish=0a30baaf`）。字节数与 SHA-256 取自 GitHub Release API 的 asset digest，本地下载后 `sha256sum -c SHA256SUMS` 三件均 OK。

本版内容：PR #77（`fix(urltest): 层级状态按 TCP/UDP 拆分，消除跨协议污染`，合并提交 `31b3b07334ba6a8ee6b3a38c087798291ad1b128`，2026-11-19T08:55:54Z，+591/−109，5 文件，`Verify Android Build` run `44486772559` 全绿）把 `currentTier`/`tierGate` 按网络各存一份，`promoteFailover` 只动被拨网络，新增 11 条隔离回归；PR #78（`feat(app): UI 对齐模块功能`，合并提交 `6215b450c3345bccd2ba0ab059939f5b34b8ba52`，2026-11-19T11:07:00Z，+123/−7，8 文件，`Verify Android Build` run `44506930043` 全绿）恢复开机自启开关、新增「重启核心」与「回滚配置」入口（白名单补 `config rollback`）、顶栏副标题跟随模块版本，模块侧零改动。被发布的 `0a30baa` 是 PR #78 合入后直接推 main 的 `preview.yml` 热修（正式版 APK 版本断言改读 `nb4a.properties` 的 `VERSION_NAME`，替代硬编码 `3.0.14`）：该提交只有 `Preview Build` 运行、没有单独的 `Verify Android Build` 记录，但其 Go/Kotlin 树与已过门禁的 `4a8ab9b` 相同。首个 `Preview Build` run `44511891425`（`6215b45`）红在这条硬编码断言，未创建 Release/tag。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.18-preview.1-arm64-v8a-release.apk` | 6366243 | `459e5bee4ee404eccf165b9ca09169ab42ee3ad3839af4525e3058bf7bdacd4d` |
| `wanbox-root-module-arm64-v8a.zip` | 18134172 | `b53e8653de2aa460acb66006d1ba8b6884220a941e46a44b4d9418803b5f398f` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23778516 | `8d219e336b2b63539611b9ee37f9871c5c3a437f56317013fa190e693ebbe794` |
| `SHA256SUMS` | 333 | `5ac21d47eb74fb63b0419dd7279a8470509268169da0efe0924bd0d3062234e5` |

APK 与普通 Root 模块合计 `24500415` 字节（23.37 MiB），`tools/size_budget.py --enforce-limit` 上限 `27262976` 字节（26 MiB），余量 `2762561` 字节（2.63 MiB）→ PASS；带管理器合一包 `23778516` 字节（22.68 MiB）。三个 ZIP 通过 `zipfile.testzip()`，无损坏；`module.prop` 为 `version=3.0.18-preview.1`、`versionCode=2036`、`author=雾晚`。本地解析 APK：包名 `com.lit008834.pixel.wanboxforandroid`、`versionName=3.0.18-preview.1`、`versionCode=10181`、`lib/` 条目 0 个（瘦管理器）；`META-INF/version-control-info.textproto` 的 `revision` 为 `0a30baaf939a97b1941210609ce377e00d386157`，与 tag、`targetCommitish`、发布 job 的 `APPROVED_SHA` 四处同源。证书 SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`，与 v3.0.14 / v3.0.17 同一张，可同包名覆盖安装。合一包与普通包唯一差异是多了 `manager.apk` 与 `package-manifest.json` 的 `withManager` 标志，`manager.apk` 与外部 APK 逐字节相同（`459e5bee…`），`bin/wanboxctl`、`bin/rootbox` 两包字节相同。模块二进制内可检出本版事件符号 `network_direct_failed` / `network_policy_invalid`，发布二进制即本版代码。CI 自标 `runtime_regression: not_verified`，本版未执行 Magisk / KernelSU / APatch 真机矩阵。

已知边界：跨协议隔离做到 `currentTier` 与 `tierGate` 层，一次探测的观测仍按 tag 记录、由双栈成员的两个网络共享（只有单网络成员被过滤）；嵌套 URLTest 组的聚合 summary 仍与网络无关。按网络记录观测的成本高于本版收益，留待后续。

## 历史归档：v3.0.17-preview.4 已发布资产快照（Release/tag 已在 v3.0.18-preview.1 发布后清理）

preview.4 的 Release/tag 已在 `v3.0.18-preview.1` 发布后由 `prune_prereleases.py` 删除（`Preview Build` run `44516333684` 日志 11:55:35Z：「Deleted older prerelease and tag: v3.0.17-preview.4」），`releases/tag/v3.0.17-preview.4` 及其资产链接现返回 404，下表仅作历史记录。

来源：[GitHub Release v3.0.17-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.4)，tag/commit `906a6ea3f48779cc96c7a4116cfd1faeb63aa354`，`Preview Build` run `44378329740` 于 2026-11-18T19:38:07Z 发布（prerelease，`targetCommitish=906a6ea3`）。字节数与 SHA-256 取自 GitHub Release API 的 asset digest，本地下载后与 `sha256sum -c SHA256SUMS` 逐项一致。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.17-preview.4-arm64-v8a-release.apk` | 6363791 | `7c6786f2708baa0299b7195c950c819570f867ce909ca54482ed5eaa61a0c9c3` |
| `wanbox-root-module-arm64-v8a.zip` | 18131042 | `9b44e0a1226dcf88ef9c1c7c095aa736fcaedb96e83aae4bc28663564da329b6` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23774566 | `f3bb069962634cf1583200cb691335f4347a3bb1d76b4a8d8ad038ff626934c8` |
| `SHA256SUMS` | 333 | `0c0a2d9fddf58160c2fc1df7ce37b9048091a30dca4844fbde13c9624ef36783` |

APK 与普通 Root 模块合计 `24494833` 字节（23.36 MiB），`tools/size_budget.py --enforce-limit` 上限 `27262976` 字节，余量 `2768143` 字节（2.64 MiB）→ PASS。三个 ZIP 通过 `zipfile.testzip()`（12 / 13 项，无损坏）；`module.prop` 为 `version=3.0.17-preview.4`、`versionCode=2034`。带管理器 ZIP 内 `manager.apk` 与外部 APK 逐字节相同（`6363791` 字节）。APK 内 `META-INF/version-control-info.textproto` 的 `revision` 为 `906a6ea3f48779cc96c7a4116cfd1faeb63aa354`，与 tag、`targetCommitish` 与发布 job 的 `APPROVED_SHA` 四者同源。本地解析 AXML 复验：包名 `com.lit008834.pixel.wanboxforandroid`、`versionName=3.0.17-preview.4`、`versionCode=10174`；证书 SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`（与正式版 v3.0.14 同证书，取自本地 v2 签名块解析）。模块 `bin/wanboxctl` 内可检出本版事件名 `network_direct_failed` / `network_policy_invalid`，发布二进制即本版代码。CI 自标 `runtime_regression: not_verified`。

## 历史归档：v3.0.17-preview.3 已发布资产快照（Release/tag 已在 preview.4 发布后清理）

preview.3 的 Release/tag 已在 preview.4 发布后由 `prune_prereleases.py --keep-tag v3.0.17-preview.4` 删除（发布 run `44378329740` 日志 19:38:15Z：「Deleted older prerelease and tag: v3.0.17-preview.3」），下表仅作历史记录。

来源为原 [GitHub Release v3.0.17-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.3)，tag/commit `6adc5ac0bcbd87bf9ff4b2097fe367761a4bc841`，`Preview Build` run `44306599880` 于 2026-11-18T13:08:01Z 发布（prerelease，`targetCommitish=6adc5ac0`）。字节数与 SHA-256 取自 GitHub Release API 的 asset digest；本地下载后 `sha256sum -c SHA256SUMS` 三件均 OK。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.17-preview.3-arm64-v8a-release.apk` | 6363287 | `7ed9b32c7b50d5bdccd72f2344185447b948a60325cd10d3bff9c5aded9f2efa` |
| `wanbox-root-module-arm64-v8a.zip` | 18130079 | `dda833067f1afdadba3253370169d368f23811480032b74845905a737be07312` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23773432 | `028c9a12f2de29aa1cb6ae8e316a020e5b7a2ce3f2d612202112d8fac949ca63` |
| `SHA256SUMS` | 333 | `81ccc4e349d5b8abb8db9006d4c9473e1a5242246da5a6f6225a113c1c3154f9` |

APK 与普通 Root 模块合计 `24493366` 字节（23.36 MiB），`tools/size_budget.py --enforce-limit` 上限 `27262976` 字节，余量 `2769610` 字节（2.64 MiB）→ PASS。两个 ZIP 通过 `zipfile.testzip()`（12 / 13 项，无损坏）；`module.prop` 为 `version=3.0.17-preview.3`、`versionCode=2033`。带管理器 ZIP 内 `manager.apk` 与外部 `wanBoxForAndroid-3.0.17-preview.3-arm64-v8a-release.apk` 均为 `6363287` 字节。APK 内 `META-INF/version-control-info.textproto` 的 `revision` 为 `6adc5ac0bcbd87bf9ff4b2097fe367761a4bc841`，与 tag 同源。本地 `aapt dump badging` 复验：包名 `com.lit008834.pixel.wanboxforandroid`、`versionName=3.0.17-preview.3`、`versionCode=10168`、minSdk 31。证书 SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`（与正式版 v3.0.14 同证书）、`signer_count=1`、`thin=true` 取自 CI `verify_preview_candidate.py` 的 build-tools 实测输出（发布 run `44306599880`，本机未独立复验 apksigner 部分）；同一份 CI 报告显式标注 `runtime_regression: not_verified`。

## 历史归档：v3.0.17-preview.2 已发布资产快照（Release/tag 已在 preview.3 发布后清理）

preview.2 的 Release/tag 已在 preview.3 发布后由 `prune_prereleases.py --keep-tag v3.0.17-preview.3` 删除，下表仅作历史记录。来源：[GitHub Release v3.0.17-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.2)，tag/commit `49e966197dd608b687b36eecdaf394274dacfe15`，`Preview Build` run `44281162788` 于 2026-11-18T10:18:19Z 发布（prerelease，`targetCommitish=49e96619`）。字节数与 SHA-256 取自 GitHub Release API 的 asset digest，并与公开 `SHA256SUMS` 三行逐项一致；本地下载后 `sha256sum -c SHA256SUMS` 三件均 OK。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.17-preview.2-arm64-v8a-release.apk` | 6362163 | `b45b275869310c69e2cf211a2e18e4ceb92e7ad274478323a920ae87d27b3500` |
| `wanbox-root-module-arm64-v8a.zip` | 18128414 | `bfd8f9eac526627aeff6d61527c170885fcb9eb8709448c613d2789a05f7f562` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23769177 | `b849607b9bdb13a3f90091b460bbb8471f70074bd6abdd944c78df50e2a53859` |
| `SHA256SUMS` | 333 | `3f39388320e022bce1b15816446ae9e4633b42391cf3d753277c61b039c61519` |

APK 与普通 Root 模块合计 `24490577` 字节（23.36 MiB），`tools/size_budget.py --enforce-limit` 上限 `27262976` 字节，余量 `2772399` 字节（2.64 MiB）→ PASS。两个 ZIP 通过 `zipfile.testzip()`（12 / 13 项，无损坏）；`module.prop` 为 `version=3.0.17-preview.2`、`versionCode=2032`。APK 内 `META-INF/version-control-info.textproto` 的 `revision` 为 `49e966197dd608b687b36eecdaf394274dacfe15`，与 tag 同源。本地 `aapt dump badging` 复验：包名 `com.lit008834.pixel.wanboxforandroid`、`versionName=3.0.17-preview.2`、`versionCode=10162`；证书 SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`（与正式版 v3.0.14 同证书）、`signer_count=1`、`thin=true` 取自 CI `verify_preview_candidate.py` 的实测输出（发布 run `44281162788`，本机无 Java 未独立复验 apksigner 部分）；同一份 CI 报告显式标注 `runtime_regression: not_verified`。

## 历史归档：v3.0.17-preview.1 已发布资产快照（Release/tag 已在 preview.2 发布后清理）

preview.1 的 Release/tag 已在 preview.2 发布后由 `prune_prereleases.py --keep-tag v3.0.17-preview.2` 删除，下载链接现已 404（API 回读 404），下表仅作历史记录。来源：[GitHub Release v3.0.17-preview.1](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.1)，tag/commit `33391d1dfa851fce7998431eb4ab614407ffbb25`，`Preview Build` run `44076008956` 于 2026-11-17T12:01:45Z 发布（prerelease，`targetCommitish=33391d1`）。字节数与 SHA-256 取自 GitHub Release API 的 asset digest，并与公开 `SHA256SUMS` 三行逐项一致；本地下载后 `sha256sum -c SHA256SUMS` 三件均 OK。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.17-preview.1-arm64-v8a-release.apk` | 6301506 | `4d7fe5f5e2b6fd70986757b6792b7198b7740536668b028ef3ad979f67f6cd48` |
| `wanbox-root-module-arm64-v8a.zip` | 17956394 | `82a3e48eaf22da522bd1734265cf69ea89254b67f0c22baacab649bfd4c21f1f` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23549099 | `f16dff3795b703830febd5c531d6824c65933f037e00f1b01220ce06877fdc8e` |
| `SHA256SUMS` | 333 | `c9c4bd337fd6872138e3f01ec080f6df2aac63831a4a5aeae7d84761a6a12ff8` |

APK 与普通 Root 模块合计 `24257900` 字节（23.13 MiB），`tools/size_budget.py --enforce-limit` 上限 `27262976` 字节，余量 `3005076` 字节（2.87 MiB）→ PASS。两个 ZIP 通过 `zipfile.testzip()`（12 / 13 项，无损坏）；`module.prop` 为 `version=3.0.17-preview.1`、`versionCode=2031`。APK 内 `META-INF/version-control-info.textproto` 的 `revision` 为 `33391d1dfa851fce7998431eb4ab614407ffbb25`，与 tag 同源。CI `verify_preview_candidate.py` 的 badging/签名结论（本环境无 `aapt`/`apksigner`，未独立复验）：包名 `com.lit008834.pixel.wanboxforandroid`、`versionName=3.0.17-preview.1`、`versionCode=10156`、证书 SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`（与正式版 v3.0.14 同证书）、`signer_count=1`、`thin=true`；同一份 CI 报告显式标注 `runtime_regression: not_verified`。

## 历史归档：v3.0.16-preview.4 已发布资产快照（Release/tag 已在 preview.1 发布后清理）

preview.4 的 Release/tag 已在 preview.1 发布后由 `prune_prereleases.py --keep-tag v3.0.17-preview.1` 删除（发布 run `44076008956` 日志：「Deleted older prerelease and tag: v3.0.16-preview.4」），下载链接现已 404，下表仅作历史记录。来源：[GitHub Release v3.0.16-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.16-preview.4)，tag/commit `9283ccb0886805bddbd6856835d23a548f20b9d6`，`Preview Build` run `44013891166` 于 2026-11-17T04:18:56Z 发布（prerelease，`targetCommitish=9283ccb`）。字节数与 SHA-256 取自 GitHub Release API 的 asset digest，并与公开 `SHA256SUMS` 三行逐项一致；本地下载后 `sha256sum -c SHA256SUMS` 三件均 OK。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.16-preview.4-arm64-v8a-release.apk` | 6300006 | `ee36f3bae60987a69c24b3280677520144e435923e2d24ce63be47e49d1a244b` |
| `wanbox-root-module-arm64-v8a.zip` | 17956386 | `10ff382fd326ed151fc382b5d1e4bfac7b2a045a1cefd6a1564b87cee3df0def` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23548016 | `f3570d644b56bb539280e8dfd84e66f233724ce61e30d79cc54460e7cf6d2b3a` |
| `SHA256SUMS` | 333 | `1fa845110deba902dca7b01bb2d90a0233799117db33e90c0062ee92eb6acfb8` |

APK 与普通 Root 模块合计 `24256392` 字节（23.13 MiB），`tools/size_budget.py --enforce-limit` 上限 `27262976` 字节，余量 `3006584` 字节（2.87 MiB）→ PASS。两个 ZIP 通过 `zipfile.testzip()`（12 / 13 项，无损坏）；`module.prop` 为 `version=3.0.16-preview.4`、`versionCode=2030`。APK 内 `META-INF/version-control-info.textproto` 的 `revision` 为 `9283ccb0886805bddbd6856835d23a548f20b9d6`，与 tag 同源。CI `verify_preview_candidate.py` 的 badging/签名结论（本环境无 `aapt`/`apksigner`，未独立复验）：包名 `com.lit008834.pixel.wanboxforandroid`、`versionName=3.0.16-preview.4`、`versionCode=10154`、证书 SHA-256 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`（与正式版 v3.0.14 同证书）、`signer_count=1`、`thin=true`；同一份 CI 报告显式标注 `runtime_regression: not_verified`。

## 历史归档：v3.0.16-preview.3 已发布资产快照（Release/tag 已清理）

preview.3 的 Release/tag 已在 preview.4 发布后由 `prune_prereleases.py --keep-tag v3.0.16-preview.4` 删除（发布 run `44013891166` 日志：「Deleted older prerelease and tag: v3.0.16-preview.3」），下载链接现已 404，下表仅作历史记录。来源为原 [GitHub Release v3.0.16-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.16-preview.3)，tag/commit `fa6b95b9a39d2d441280021968d4b914eb5b20d3`；下表为 2026-11-17 替换后的资产（由 main `000d3023571153d975d2868eeb7083da57b1a9e4` 的 `Preview Build` run `43939217515` 产出，`Replace Existing Preview Assets` run `43942569933` 写入）；字节数与 SHA-256 取自当时的 GitHub Release API digest，并与公开 `SHA256SUMS` 三行逐项一致。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.16-preview.3-arm64-v8a-release.apk` | 6294022 | `3ec530b4faffab541e72ac1d66d8bb4cd123d2c8e2cdb1ba56af8abd56695d46` |
| `wanbox-root-module-arm64-v8a.zip` | 17956389 | `e68140980b39b1d1724fab1f9d9778d5b5d16c05ffdfe9b9074145db2439c95f` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23544348 | `57fb8aff3eed6c39fde637bd73356055f8cbbc47ad7786707563c4e2174796f4` |
| `SHA256SUMS` | 333 | `cf9157334b1b27480cc3d878c0d489a4076b0a66d608e2450669395b747ca494` |

APK 与普通 Root 模块合计 `24250411` 字节（23.13 MiB）；带管理器 ZIP 为 `23544348` 字节，均低于 26 MiB。首发（run `43906481811`，`fa6b95b`）的原始资产已被替换，不再可下载。

## 历史归档：v3.0.16-preview.2 已发布资产快照（Release/tag 已清理）

来源：[GitHub Release v3.0.16-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.16-preview.2)，正式发布 run `43684289336`，tag/commit 为 `c5701a0979ef2a8ee4d28020f513f7f4aac07358`。以下字节数和 SHA-256 对应已下载资产；公开 `SHA256SUMS` 对 APK 与两个 Root ZIP 的校验均通过，清单文件自身 SHA-256 也列于下表。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.16-preview.2-arm64-v8a-release.apk` | 6231814 | `b6fee5df06f8cf212861440a41301d5155fc47cfef1eae03118ce156e446ff4c` |
| `wanbox-root-module-arm64-v8a.zip` | 17956181 | `907a4891634f95a368d5bdfe0847b8fe68c50c454f857c59cac489ee91da9916` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23493445 | `e89dba91718bfabb2a2ac9158ee25ccd9ad50d1c29c8ff12ab6c5f6c4ebb3297` |
| `SHA256SUMS` | 333 | `9d6d33bc2d6ad1ed8116a8ae746e3a0bbbf15a58b48ed5b97c394a8144481fa6` |

APK 与普通 Root 模块合计 `24187995` 字节（23.07 MiB）；带管理器 ZIP 为 `23493445` 字节，均低于 26 MiB。

## 历史归档：v3.0.16-preview.1 已发布资产快照（Release/tag 已清理）

preview.1 的 Release/tag 已在 preview.2 发布后按单预览策略清理；历史证据由正式 `Preview Build` run `43673447000` 与原 tag commit `7630ddd90e6092f9ce5647d34fb7d9a892db5769` 保留。以下 GitHub API SHA-256 digest 与当时下载资产逐字节匹配；当时公开 `SHA256SUMS` 清单校验通过。

| 文件 | 字节数 | GitHub SHA-256 digest |
|---|---:|---|
| `wanBoxForAndroid-3.0.16-preview.1-arm64-v8a-release.apk` | 6231578 | `6ff3035c9d1bfbfb94b26be79b8c0322010434ef1772592851f3f88e458e5d6c` |
| `wanbox-root-module-arm64-v8a.zip` | 17955164 | `644931b214bfb164d09382e73eb141edd0a8d4082d0562fbaa7e929bb379d707` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23492569 | `8ba8298f908952a55f33d8b5ba99e69ca997e52c63170bd97d557026a5c87c67` |
| `SHA256SUMS` | 333 | `083d8c15c5afc10c829be7350172b048bbc429132f3c6328aab2bb84d544514e` |

APK 与普通 Root 模块合计 `24186742` 字节；带管理器模块包为 `23492569` 字节，均低于 26 MiB。

## 历史归档：v3.0.15-preview.4 已发布资产快照（Release/tag 已清理）

preview.4 的 Release/tag 已在 3.0.16-preview.1 发布后按单预览策略清理；历史证据由正式 `Preview Build` run `43628453020` 与原 tag commit `d6914e70d730c63ee3c3cbb2b00fe2e754b6737e` 保留。以下字节数和 SHA-256 对应当时下载资产；当时公开 `SHA256SUMS` 对 APK 与两个 Root ZIP 的校验均通过，清单文件自身 SHA-256 也列于下表。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.15-preview.4-arm64-v8a-release.apk` | 6231390 | `0f2df78719d6d2f0c07f69c58fcc59c1a0224e903de03b0d97a676d54e165e1c` |
| `wanbox-root-module-arm64-v8a.zip` | 17952780 | `ce586659f715043b2cdb4c63ea50c27944f79fe05f3aeeda2799e5eea34cfae7` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23490404 | `578ed5cec2e4f6f912f94ec6b7d0a9ae14c5738c98e22b4098ac20abd5bb2f57` |
| `SHA256SUMS` | 333 | `cbda8b7210ca2f7cd99817f9f022d2fbf183ce1d2dbe5950f5b9db5c487d671d` |

APK 与普通 Root 模块合计 `24184170` 字节（23.06 MiB）；带管理器 ZIP 为 `23490404` 字节，均低于 25 MiB。

## 历史归档：v3.0.15-preview.3 已发布资产快照（Release/tag 已清理）

preview.3 的 Release/tag 已在 preview.4 发布后按单预览策略清理；历史证据由正式 `Preview Build` run `43404430362` 与原 tag commit `fe193f1134a80ff38fb0cb3ed4677eb2b79c1dce` 保留。以下 GitHub API SHA-256 digest 与当时下载资产逐字节匹配；当时公开 `SHA256SUMS` 清单校验通过。

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

历史机器可读记录 [preview3-version-evidence.json](preview3-version-evidence.json) 对应 v3.0.11 正式版与 v3.0.11-preview.2，**不是当前 v3.0.18 预览线的核验结果**。其中包括 Release digest、SHA256SUMS、ZIP 清单、带管理器模块内 APK 与外部 APK 的字节一致性、`aapt` 身份与 `apksigner` 验证。

共同包名为 `com.lit008834.pixel.wanboxforandroid`，证书 SHA-256 为 `80ecee1c42d861d59cdc460447335fc4bfdf80103d6225a17c1bdf83a33521f6`；该历史快照中 v3.0.17-preview.3 的包名、`versionCode=10168`、单签名与证书指纹来自 CI `verify_preview_candidate.py` 的 build-tools 实测输出（发布 run `44306599880`）；本地另以 aapt 复验包名与版本，本机未独立复验 apksigner 部分。

Android 12+（minSdk 31）、arm64-v8a、Root；瘦管理器和 Root 模块必须使用同一发布页的同版资产。下载通过同一 Release 的 SHA256SUMS 后才可提示安装，禁止静默安装；资产不全时明确提示并引导至发布页，不拼凑不同 Release 的文件。

## 发布来源与边界

当前已发布正式版为表格中的 v3.0.17，最近已发布预览为 v3.0.18-preview.3（两者并存）；`PREVIEW_NOTES.md` 与[说明快照](release-notes-next.zh-CN.md)的当前区段即本版真实说明，preview.2 与 preview.1 的已发布说明移入各自的历史归档。preview.2 及更早预览的 Release/tag 均已按单预览策略清理，其 workflow run、commit 与资产快照作为历史记录保留，均不作为当前下载入口或本轮新测试结果。

preview.3 发布证据：PR #83（合并提交 `8881dd1c59481b3d23ab3766fb21e3b651fa1d65`，2026-11-21T07:50:34Z）的 `Verify Android Build` run `44879210932`（head `dbdbf0b`）全绿；main `8881dd1c` 上的 `Preview Build` run `44882104257` 四作业全绿，2026-11-21T08:13:01Z 发布，发布 job 断言 `APPROVED_SHA=8881dd1c` 并回读 tag，08:13:09Z 清理 preview.2。同一 run 的构建作业还删除了两个远古残留（`v3.0.10-preview.1` 与孤立 tag `v3.0.10-preview.24`），与本次预览无关。发布后 main 另合入 PR #84（合并提交 `00cfeedddc07249cccd8a8344eb1bf7df97d75dc`，`Verify Android Build` run `44898283085` 全绿），其修复不在本版二进制中，随下一预览发布。

preview.2 发布证据：PR #80（合并提交 `ad032285b3ff181677ed6c0ea9c88a7bb4e482a6`，`Verify Android Build` run `44717598549` 全绿）合入后，升版 PR #81（合并提交 `e7f1f9366316974d8318e8708dcaaa669bc896a0`）的 `Verify Android Build` run `44723295827` 首跑红在 plugins.gradle.org 解析 `kotlin-gradle-plugin:2.0.21` 瞬时不可用（未创建 tag/Release），重跑后全绿；main `e7f1f936` 上的 `Preview Build` run `44728368529` 四作业全绿，11:51:54Z 发布，发布 job 断言 `APPROVED_SHA=e7f1f936` 并回读 tag，11:52:04Z 清理 preview.1。

preview.1 发布证据：PR #77（`Verify Android Build` run `44486772559`，head `91e64a1`）与 PR #78（`Verify Android Build` run `44506930043`，head `4a8ab9b`；Python 工具 125 项、`rootmodule` 28 项、文档门禁与插桩 81 项全绿）先后合入 main；`Preview Build` run `44511891425`（`6215b45`）首跑红在正式版 APK 硬编码 `3.0.14` 的版本断言，`0a30baa` 改读 `nb4a.properties` 后 run `44516333684` 四作业全绿，11:55:25Z 发布，发布 job 断言 `APPROVED_SHA=0a30baaf` 并回读 tag，11:55:35Z 清理 preview.4。`0a30baa` 为直接推 main 的 workflow 热修，未单独跑 `Verify Android Build`。

preview.3 发布证据：PR #69（合并提交/tag `6adc5ac0bcbd87bf9ff4b2097fe367761a4bc841`，2026-11-18T12:04:41Z）的 `Verify Android Build` run `44295650161` 全绿；main `6adc5ac0` 上的 `Preview Build` run `44306599880` 的 `Native Build (LibCore)`、`Build OSS APK`、`Candidate cold-start measurement (emulator only)` 与 `Publish Preview Release` 四个 job 全部成功并于 2026-11-18T13:08:01Z 发布，发布 job 断言 `APPROVED_SHA=6adc5ac0`、创建 Release 后回读 tag 对象并复核为同一提交，最后由 `prune_prereleases.py --keep-tag v3.0.17-preview.3` 清理 preview.2。上表四件套字节数为发布后 API 回读值，本地 `sha256sum -c` 三件 OK。

preview.2 历史证据：PR #67（合并提交/tag `f333fa21232e4af5c1e95192de33f10a9ae22250`，2026-11-18T08:44:27Z）与 PR #68（合并提交 `570e9ed3e12848c26497501960ba2369c546f3c0`，2026-11-18T08:48:15Z）合入后，bump 提交 `49e966197dd608b687b36eecdaf394274dacfe15`（`PRE_VERSION_NAME=3.0.17-preview.2`、`PRE_VERSION_CODE=2032`）由快照推送上 main。分支验证 port `Verify Android Build` #190（头 `921ca47`）与 feat #194（头 `b157e4d`）全绿，PR 门禁 #195（port 头）与 #196（feat 头）全绿；main 头 `49e96619` 的 `Verify Android Build` #197 第 1 次尝试红在 release 变体 3 项插桩测试（`GroupDraftAbandonPersistenceTest.rebuildFailureSurvivesActivityDestructionAndUserCanRetry`、`OutboundReferenceAlarmTest.failedInstallerRestorationLeavesReviewPendingWithoutWarning`、`SubscriptionGroupOwnershipTest.ordinaryEditorCannotTakeSubscriptionNodesOrPartiallySave`），定位为 UiAutomation 窗口上报超时抖动、未改代码，第 2 次尝试全绿，instrumented 实测 debug 81 项 0 失败 2 skip、release 79 项 0 失败 2 skip。main `49e96619` 上的 `Preview Build` run `44281162788` 的 `Native Build (LibCore)`、`Build OSS APK`、`Candidate cold-start measurement (emulator only)` 与 `Publish Preview Release` 四个 job 全部成功，发布 job 断言 `APPROVED_SHA=49e96619`、创建 Release 后回读 tag 对象并复核为同一提交，最后由 `prune_prereleases.py --keep-tag v3.0.17-preview.2` 清理 preview.1。上表四件套字节数与 SHA-256 为发布后 API 回读值。

preview.1 历史证据：PR #65（合并提交/tag `33391d1dfa851fce7998431eb4ab614407ffbb25`）的 `Verify Android Build` run `44071339331`（PR head `aeb72c5`）全绿，instrumented 实测 debug（thin）与 release（R8 混淆 thin）各 79 项 0 失败 2 skip、internal 2 项 0 失败；旧实现必红证据为 run `44064100226`（纯测试提交 `419fdc5`，该用例抛 `group_editor_recovery_required`）与 run `44067193911`（`624fc8e`，同用例抛 `NullPointerException: Can't toast on a thread that has not called Looper.prepare()`）。main `33391d1` 上的 `Preview Build` run `44076008956` 的 `Native Build (LibCore)`、`Build OSS APK`、`Candidate cold-start measurement (emulator only)` 与 `Publish Preview Release` 四个 job 全部成功，发布 job 断言 `APPROVED_SHA=33391d1`、创建 Release 后回读 tag 对象并复核为同一提交，最后由 `prune_prereleases.py --keep-tag v3.0.17-preview.1` 清理 preview.4；其 Release/tag 已在 preview.2 发布后清理。

preview.4 历史证据：PR #62（合并提交/tag `9283ccb0886805bddbd6856835d23a548f20b9d6`）的 `Verify Android Build` run `44011037428`（PR head `1ce7d7f`）通过；main `9283ccb` 上的 `Preview Build` run `44013891166` 四个 job 全部成功并在 2026-11-17T04:18:56Z 发布，tag 同源；其 Release/tag 已在 preview.1 发布后清理。

preview.3（v3.0.17 线）历史证据：`verify-android` run `43897997343`（PR #60，提交 `70f90c4ef1`）与 `Preview Build` run `43906481811`（tag/commit `fa6b95b9a39d2d441280021968d4b914eb5b20d3`）首发；资产经 `verify-android` run `43934149499`（PR #61，提交 `6a456e420c`）、main `000d3023571153d975d2868eeb7083da57b1a9e4` 上的 `Preview Build` run `43939217515` 与 `Replace Existing Preview Assets` prepare run `43942306964` / execute run `43942569933` 受审查替换。

[发布清单](release-checklist.zh-CN.md) 保留分阶段门槛与正式版发布清单。v3.0.18-preview.3 与早期的 v3.0.17-preview.3 均未执行 Magisk / KernelSU / APatch 真机矩阵，该项仍未验证；稳定版 v3.0.14 保持不变，任何自动化结果都不代替真机验证。

正式发布不执行 prune，因此正式版 v3.0.17、v3.0.14 与 v3.0.11 的 Release 与 tag 均保留；当前预览渠道只保留最新的 prerelease，即 v3.0.18-preview.3（v3.0.18-preview.2 的 Release/tag 已在本版发布时清理）。后续发布仍须使用获批精确提交及同次核验的四件套资产，并在清理旧预览前完成回读验证。

正式版说明见 [RELEASE_NOTES.md](../RELEASE_NOTES.md)（v3.0.17；v3.0.14 与 v3.0.11 的说明正文保留在各自 Release 页与 git 历史中）。
