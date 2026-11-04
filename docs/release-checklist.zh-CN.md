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
