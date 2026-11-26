## 1. 协议标签与通知

- [x] 1.1 按 TDD 为 VLESS+XHTTP 标签增加失败用例，再实现最小显示分支
- [x] 1.2 按 TDD 为 Reality、XHTTP+Reality 组合增加独立失败用例并实现标签
- [x] 1.3 增加普通 VLESS 与 VMess 基线断言
- [x] 1.4 扩展通知纯函数用例，覆盖固定节点、活动策略组成员、三种特殊标签及其他协议文案不变
- [x] 1.5 将标签接入通知初始标题、选中节点、活动成员映射和回退路径

## 2. 文档与本地验证

- [x] 2.1 更新中文说明、文档索引、Android OpenSpec 主规范与本 change delta
- [x] 2.2 记录策略组用户目标为 2 秒/单候选拨号上限，同时说明当前代码和现有 OpenSpec 仍为 3 秒；本变更不改策略逻辑
- [x] 2.3 本地运行 `app:testOssDebugUnitTest` 与 `app:testPreviewDebugUnitTest` 全量 JVM 测试套件
- [x] 2.4 本地适用的治理门禁及 `tools/` 测试：`tools/` 132 项通过；版本文档、核心治理、Android 安全检查及 `git diff --check` 通过。Go `-race` 对本 PR 不适用（无 Go/libcore 代码改动）；CI 仍将运行其核心门禁。
- [ ] 2.5 GitHub Actions `Verify Android Build`（PR 创建后待运行；Xray 12 项互通以该工作流结果为准）
- [ ] 2.6 Magisk / KernelSU / APatch Root 真机矩阵（未执行，明确保留为实测项）

本地 Gradle 单测使用忽略的临时 AAR 符号链接作为 app 编译依赖；它不是当前分支原生核心构建产物。完整核心原生构建未在本地验证；OpenSpec CLI 未安装。PR 合入前应依据最新 CI 结果更新本文件状态；本任务不授权合并或发布。
