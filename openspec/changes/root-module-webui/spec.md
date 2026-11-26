# 变更规范：Root 模块静态 WebUI

## ADDED Requirements

### Requirement: Root 模块提供本地静态 WebUI

模块 MUST 携带固定的 `webroot/index.html`、`app.mjs`、`controller.mjs` 和 `style.css`。页面 MUST 通过严格 CSP 限制为本地资源，并 MUST 在 KernelSU WebUI API 可用时才执行操作。

#### Scenario: 管理器 API 缺失

- **GIVEN** 页面运行环境未提供兼容的 `kernelsu.exec`
- **WHEN** WebUI 初始化
- **THEN** 页面显示不支持提示且不执行 Root 命令

### Requirement: Root 命令必须是固定白名单且状态输出有界

页面 MUST 仅允许固定 `wanboxctl` 子命令 `status`、`dashboard`、`logs`、`start`、`stop`、`restart`；MUST 拒绝任意命令/路径输入，MUST 隐去 shell stderr，并且事件只显示和导出最近 40 条。成功响应 MUST 有正整数 `schemaVersion`、`ok: true` 和对象 `state`，其 `phase` 必须是非空字符串。

#### Scenario: 恶意或畸形操作

- **GIVEN** 页面动作未知、命令失败或 JSON/状态结构不正确
- **WHEN** 页面尝试执行或渲染结果
- **THEN** 未知动作不会调用 bridge，其他错误折叠为稳定提示
- **AND** 不读取原始日志或输出未知私有字段

### Requirement: WebUI 必须在安装包白名单和文件权限中闭环

新打包器、合包器与 Go 安装器 MUST 允许固定 WebUI 资源并拒绝未知文件；新生成模块包 MUST 携带完整四项。Go manifest 若有任何 WebUI 项 MUST 包含完整资源集，但安装器 MAY 接受完全不含 WebUI 项的旧 manifest。首次安装与热更新完成后目录权限 MUST 为 module root/webroot 0755、四个 WebUI 文件 0644，其他文件继续遵守原权限策略。

#### Scenario: 安装白名单资源

- **GIVEN** 模块包包含完整四项 WebUI 文件
- **WHEN** 初次安装或 Go 热更新安装完成
- **THEN** 管理器可读取静态资源
- **AND** 未知或部分资源集在替换模块前失败

### Requirement: PR 与 Preview 执行 WebUI 体积门禁

源 WebUI 与模块 ZIP 内 WebUI 未压缩 payload MUST 各不超过 1 MiB；PR 验证和 Preview 候选验证 MUST 执行该检查，同时保留当前 APK + 模块 ZIP 下载预算。

#### Scenario: 体积超限

- **GIVEN** 任一源或 ZIP WebUI payload 大于 1 MiB
- **WHEN** PR 或 Preview 候选门禁执行
- **THEN** 构建失败并指出超限对象
