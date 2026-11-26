# Root 模块静态 WebUI 规范

## 目的

定义 Root 模块本地 WebUI 的运行时边界、固定操作面、安装资源权限与构建体积门禁。WebUI 是管理器能力的可选入口，不改变 App Root 管理界面、模块数据 schema 或运行时网络协议。

## Requirements

### Requirement: WebUI 必须是本地静态资源

新生成的模块包 MUST 携带固定的 `webroot/index.html`、`webroot/app.mjs`、`webroot/controller.mjs` 与 `webroot/style.css`。打包器 MUST 拒绝缺失或白名单外资源；Go 安装器若 manifest 列出任何 WebUI 资源 MUST 要求四项齐全，但 MAY 接受完全不含 WebUI 项的旧 manifest 以维持升级兼容。页面 MUST 使用本地脚本和样式，并 MUST 通过 CSP 禁止远程连接、对象内容与表单提交。

#### Scenario: 从模块管理器打开页面

- **GIVEN** 模块包含完整且校验通过的 WebUI 资源
- **WHEN** 用户从提供兼容 WebUI API 的 Root 管理器打开模块页面
- **THEN** 页面从模块本地加载，不访问外部网络
- **AND** 未知或缺失资源不能静默进入打包产物

#### Scenario: 兼容旧 manifest

- **GIVEN** 已有模块 manifest 完全不含 WebUI 项
- **WHEN** Go 安装器解析该旧 manifest
- **THEN** 安装器可按既有白名单规则处理该包，不伪称其含有 WebUI

#### Scenario: 拒绝部分 WebUI manifest

- **GIVEN** manifest 列出一项或多项但少于四项 WebUI 资源
- **WHEN** Go 安装器校验 manifest
- **THEN** 安装在替换当前模块前失败

### Requirement: WebUI 特权操作必须受固定 CLI 白名单约束

WebUI MUST 仅通过管理器提供的 KernelSU WebUI API 执行 `/data/adb/modules/wanbox/bin/wanboxctl` 的固定子命令 `status`、`dashboard`、`logs`、`start`、`stop`、`restart`。页面 MUST NOT 接收用户提供的 shell 命令、路径或任意参数，MUST NOT 增加 HTTP 写接口，并 MUST 折叠底层错误，禁止向页面透传 shell stderr。成功响应 MUST 是对象，包含正整数 `schemaVersion`、`ok: true` 和对象 `state`，其中 `state.phase` 是非空字符串。

#### Scenario: 未知动作或错误响应

- **GIVEN** 页面收到未知动作或 CLI 返回非预期响应
- **WHEN** 页面准备调用 Root bridge 或渲染结果
- **THEN** 未知动作在调用 bridge 之前被拒绝
- **AND** 非零退出码、错误 envelope 或畸形状态都显示稳定错误
- **AND** 不向页面暴露原始 stderr 或响应中的未知私有字段

### Requirement: 状态与事件展示必须有界

WebUI MUST 展示模块状态中已有的健康、配置名、修订与重试信息；事件视图和导出 MUST 限制为最近 40 条固定字段事件。WebUI MUST NOT 读取或导出原始日志、诊断归档、凭据或私有文件。

#### Scenario: 导出诊断事件

- **GIVEN** 模块状态包含超过 40 条事件
- **WHEN** 用户导出事件摘要
- **THEN** 文件只包含最近 40 条事件及导出时间
- **AND** 文件不包含原始日志或未列入视图模型的状态字段

### Requirement: 安装权限必须满足本地页面读取且保持最小化

首次安装与 Go 热更新安装 MUST 使模块根目录及 `webroot/` 可遍历，四个 WebUI 文件可由管理器读取；目标权限为目录 `0755`、资源文件 `0644`。其他模块普通文件和数据 MUST 保持既有私有权限策略。Go package manifest 若包含任何 WebUI 文件 MUST 包含完整四文件集合。

#### Scenario: 安装完整 WebUI 模块

- **GIVEN** 完整、校验通过的 WebUI 包
- **WHEN** 模块首次安装或热更新安装完成
- **THEN** 管理器可遍历模块根目录与 `webroot/` 并读取四个资源
- **AND** 其他模块文件未被整体放宽权限

#### Scenario: 安装部分 WebUI 包

- **GIVEN** package manifest 只列出部分 WebUI 文件
- **WHEN** 安装器校验 manifest
- **THEN** 安装在替换当前模块前失败

### Requirement: 兼容性声明必须保守

KernelSU 原生 WebUI API 是目标运行时。 页面 MUST 直接使用管理器注入的 `globalThis.ksu.exec(cmd, optionsJson, callbackName)`，不得依赖裸模块导入、importmap 或远程脚本；每次调用 MUST 使用独立的全局回调名，并在回调、调用失败或超时时清理。APatch 仅在提供兼容 KernelSU WebUI API 时可用；Magisk 本身 MUST NOT 被声明为提供原生 WebUI API。未验证的第三方运行时 MUST NOT 被宣称支持。API 不可用时页面 MUST 说明原因并保持不执行 Root 操作。

#### Scenario: 管理器未注入兼容 API

- **GIVEN** 当前管理器未提供 `kernelsu` WebUI bridge
- **WHEN** 页面初始化
- **THEN** 页面显示不支持提示
- **AND** 不尝试任意 shell、网络访问或其他写入通道

### Requirement: WebUI 和下载体积必须有自动门禁

源 WebUI 文件总量与模块 ZIP 内 WebUI 未压缩内容 MUST 各不超过 1 MiB。每个 PR（包括 fork PR）MUST 运行不依赖 `libcore`/Android app 作业成功与否的独立 WebUI-only 检查：按固定 `webroot/` 路径生成只用于门禁的 ZIP 探针，并检查源与探针 ZIP；若完整模块 ZIP 已构建，PR 流程仍检查实际 ZIP。Preview 候选 MUST 对实际模块 ZIP 执行该门禁。模块 ZIP 与配套 APK 的下载总量仍 MUST 独立满足现行 27,262,976 字节预算；不得改变 Release/Tag 语义。

#### Scenario: WebUI 体积超过上限

- **GIVEN** 源 WebUI 或 ZIP 内静态资源超过 1 MiB
- **WHEN** PR 或 Preview 候选体积门禁运行
- **THEN** 门禁失败并指出超限项
- **AND** 不以压缩率掩盖 ZIP 内过大的 WebUI 原始 payload
