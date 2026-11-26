## 1. VLESS + XHTTP 手动新建入口

- [x] 1.1 先写菜单/路由与 Bean 默认值回归测试；缺少 `action_new_xhttp` 时定向测试失败，补齐入口后继续推进。
- [x] 1.2 复用既有 VMess/VLESS Activity，以 `vless` 与 `xhttp` 标记创建 `alterId=-1`、`type=xhttp` 的实体；普通 VLESS 仍为 TCP，VMess 保持原路径。
- [x] 1.3 为菜单标题增加默认语言和简体中文字符串资源；定向 `XhttpManualCreateContractTest`、`VlessProfilePresetTest` 均通过（OSS Debug JVM）。
- [x] 1.4 新增中文功能说明与本 OpenSpec delta，记录本地、CI 和 Root 真机验证边界。
- [x] 1.5 本地完整验证通过：`:app:testOssDebugUnitTest`、`:app:testPreviewDebugUnitTest`、`:core-domain:test`、OSS/Preview x86_64 Debug 构建、Android 安全检查、132 项 `tools/` Python 单测、`tools/pre-push-check.py`、默认 `tools/check_core_gate.py`（345 个源文件）、版本文档检查、保留项源码检查与 `git diff --check`。保留项检查提示本地未落盘 `assets/sing-box/*.db.xz`，未作带规则库的产物级检查。Go `-race` 本地不适用：本变更不改 Go/libcore；核心 race 留给 PR CI。
- [x] 1.6 已提交并推送独立分支、创建 PR #110；GitHub Actions `Verify Android Build` attempt 2 全绿。Build LibCore、Android Debug/Release、数据库迁移/安全、产物预算与 Magisk 模块步骤通过，Xray v26.11.9 XHTTP 互通 **12/12**。首轮 Android UI instrumentation 有 3 项窗口/可访问性断言失败，重跑失败 job 后全通过；`Module Size Lab` 与 baseline/跨版本备份证据 job 被工作流跳过。不得合并或发布。
- [ ] 1.7 Root 真机矩阵待实测：在 Magisk、KernelSU、APatch 设备分别验证菜单可达、复用表单、XHTTP 初始值、保存/重开配置及实际 XHTTP 连接；CI 和 JVM 测试不替代该矩阵。

OpenSpec CLI 当前环境不可用；本 delta 按仓库 `spec-driven` 结构人工核对。PR 创建前应复核所有本地门禁状态与对应提交头。
