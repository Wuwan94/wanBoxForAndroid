## 📌 变更说明 / Description

<!-- 请简要描述本次 PR 解决的问题或引入的新特性，并附带关联的 Issue 编号（如有）。 -->

- 关联 Issue / 讨论：
- 变更范围：[ ] 管理器 UI (app/) / [ ] Root 模块 (rootmodule/) / [ ] 构建与工具 (tools/) / [ ] 文档 (docs/)

---

## 🛡️ 架构与安全自查 / Architecture & Security Checklist

请在提交 PR 前逐项确认以下红线守卫（勾选 `[x]`）：

- [ ] **Fail-Closed 守卫**：未引入任何代理失效时的静默直连回退，未引入可能泄露真实 IP 的旁路；
- [ ] **25 MiB 体积门禁**：未引入冗余三方库，管理器 APK 与模块总大小严守 25 MiB 上限；
- [ ] **瘦管理器约束**：未向管理器 APK 内引入 JNI 动态库或常驻 Go 核心；
- [ ] **成套同版与备份兼容**：配置与导出接口保持跨版本双向备份兼容（导入/导出）；
- [ ] **本地单测通过**：已本地实跑并通过全部测试（`python3 -m unittest discover -s tools -p "test_*.py"`）；
- [ ] **文档一致性通过**：若涉及版本或状态变动，已通过 `python3 tools/check_current_version_docs.py`。

---

## 🔄 并存与回退说明 / Coexistence & Rollback

- **是否支持平滑并存**：[是 / 否]
- **回退机制**：<!-- 一旦发生回归异常，如何安全回滚至上一稳定状态 -->
