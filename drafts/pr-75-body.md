## 范围

把正式线从 `3.0.14 / 2026` 晋升到 **`3.0.17 / 2035`**（APK `versionCode=10175`、Root 模块码 `2035`），并为 `release.yml` 两阶段正式发布准备发布文档与检查器。内容来源：3.0.17 预览线（preview.1–.4）全量变更 + PR #74（合并提交 `6a20a0ca7113decbe6155500dd9e70c79650bb5a`，`Verify Android Build` run `44465876305` 全绿）。不改代码、不改 `SINGBOX_VERSION`（`v1.15.0-alpha.13-reF1nd`）、不改 `main` 上的既有 tag/Release。

## 版本元数据（`nb4a.properties`）

| 字段 | 新值 | 说明 |
|---|---|---|
| `VERSION_NAME` | `3.0.17` | tag 必须严格等于 `v3.0.17` |
| `VERSION_CODE` | `2035` | APK `10175`、模块 `2035`；严格大于 v3.0.14（`10130 / 2026`）、preview.4（`10174 / 2034`）、v3.0.11（`10065 / 2013`） |
| `PRE_VERSION_NAME` | `3.0.18-preview.1` | 下一预览占位，满足 `PRE > FORMAL` |
| `PRE_VERSION_CODE` | `2036` | 预览 APK `10181` |

## 文档同步（11 个文件）

- `README.md`：Stable 徽章与快照句改为 `v3.0.17`（保留 `0F172A` 深色徽章与 PR #70 的排版），下载表版本 / 版本码 / APK 文件名 / 成套同版模块码改为 `10175` / `2035`；上一正式版 `v3.0.14`、`v3.0.11` 标注保留；preview.4 折叠区补一句「正式版已承接其全部修复」。正式版体积行标注「发布回读后回填」——不写估算值。
- `CHANGELOG.md`：新增 `### v3.0.17（正式版）` 条目（晋升范围、版本映射、两阶段发布与不 prune、回读待回填、真机矩阵未执行）。
- `RELEASE_NOTES.md`：改写为 `v3.0.17` 正式版发布正文（中文，由 CI 复制到 Release 页）：版本与产物、运行要求、按主题汇总 3.0.14 → 3.0.17 的更新（分层调度与故障转移、订阅/分流/策略组、停核诊断与网络规则、模块热更新与卸载、界面与数据、未做事项）、升级/回滚、校验和。
- `docs/current-version.zh-CN.md`：表头四行改为 已发布正式版 `v3.0.17`、已发布预览版 `v3.0.17-preview.4`（保留）、上一正式版 `v3.0.14`、源码预览线 `3.0.18-preview.1`（未发布候选占位）；新增「v3.0.17 正式版四件套（文件名）」与发布后回读项清单；v3.0.14 的回读数据移入历史归档；修正文件尾部把 preview.3 当作当前预览的陈旧表述。
- `docs/release-checklist.zh-CN.md`：改为 `# v3.0.17 正式版发布清单`（基线、版本规则、G1–G13、两阶段 S1–S6、本次发布证据、真机待签核矩阵、停止条件）；预览线的分阶段验收与 preview.4 门禁记录移入「历史归档」。
- `PREVIEW_NOTES.md` / `docs/release-notes-next.zh-CN.md`：当前区段前移到 `3.0.18-preview.1` 候选占位（明示「尚未发布、不含本版内容」），preview.4 的已发布说明移入历史归档。
- `docs/README.md`：索引描述由 v3.0.14 改为 v3.0.17。

## 检查器

`tools/diagnostics/check_current_version_docs.py`（`tools/check_current_version_docs.py` 为其符号链接）常量改为 `STABLE_TAG=v3.0.17`、`STABLE_CODE=2035`、`STABLE_APK_CODE=10175`、`SOURCE_PREVIEW=v3.0.18-preview.1`、`SOURCE_PREVIEW_CODE=2036`；新增断言：README 下载表必须给出正式版 APK 名与 `10175`/`2035`、不得仍把 `v3.0.14` 写成当前正式版、发布清单标题必须是 `# v3.0.17 正式版` 且首段声明两阶段、CHANGELOG 必须有 `### v3.0.17（正式版）` 条目。`tools/test_check_current_version_docs.py` 同步锚点并新增「旧正式版标签」漂移用例。

## 本地验证

- `python3 -m unittest discover -s tools -p 'test_*.py'` → 125 项 OK
- `python3 tools/check_current_version_docs.py` → 通过（离线）
- `python3 tools/version_code.py --properties nb4a.properties --channel formal --baseline-apk-code 10130 --baseline-module-code 2026 --enforce` → `10175 / 2035`，upgrade feasible
- `python3 tools/version_code.py --check-kotlin` / `--check-app-tag` → 一致
- `python3 tools/check_core_gate.py`、`tools/diagnostics/roo_check_repo_governance.py` → OK
- `git diff --check` → 干净

## 合并后动作（不在本 PR 内）

合并本 PR 后，以合并提交为 `approved_sha` 触发 `release.yml`：阶段一 `publish_release=false` 候选构建 → 取 `SHA256SUMS` 哈希与 `candidate_run_id` → 阶段二 `publish_release=true` 正式上架。候选/正式 run ID 与四件套实测字节、SHA-256 由收尾 PR 回填；正式发布不执行 `prune_prereleases.py`，`v3.0.14`、`v3.0.11` 与 `v3.0.17-preview.4` 均保留。

Root 真机矩阵（Magisk / KernelSU / APatch）未执行，本次文档与发行说明均如实标注，不宣称真机通过。
