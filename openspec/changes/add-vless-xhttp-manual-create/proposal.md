# 提案：补齐 VLESS + XHTTP 手动新建入口

## Why

配置列表的手动新建菜单已有 VLESS 项，现有 VLESS 设置页也已有 XHTTP 传输和参数界面，但用户没有直接创建 VLESS + XHTTP 节点的入口。用户必须先选择普通 VLESS 再手动切换传输，或改用导入方式。本变更只补上已存在能力的直达入口。

## What Changes

- 在「手动设置」菜单增加独立的「VLESS + XHTTP」项，并通过默认与简体中文字符串资源提供标签。
- 点击该项仍打开现有 VMess/VLESS 设置 Activity；Intent 同时指定 VLESS 与 XHTTP 新建预置。
- 新 Bean 使用 `alterId = -1` 标记 VLESS、`type = "xhttp"` 选择现有 XHTTP 表单；普通 VLESS 保持 `tcp` 默认值，VMess 行为保持不变。

## Capabilities

### Modified Capabilities

- `android-application`：新增手动创建 VLESS + XHTTP 配置的 UI 契约。

## Impact

仅修改 Android 菜单、既有新建路由、VMess/VLESS 新建默认 Bean、语言资源、回归测试、中文说明和 OpenSpec delta。不改 `libcore`、Root 模块、协议解析/订阅导入，不改 `nb4a.properties`、Release/Tag 或个人网站。Mieru、策略组与 HWID 本次均不改，HWID 不外发。

## Verification

- 新入口契约测试先对缺少菜单项的基线失败；新增入口与预置后定向 JVM 测试通过。
- 本地 OSS/Preview 全量 JVM、`:core-domain:test`、`tools/` 单测和治理门禁结果须在 `tasks.md` 记录。
- 本 PR 不涉及 Go/libcore，Go `-race` 本地不适用；创建 PR 后运行 `Verify Android Build`，包括 Xray v26.11.9 的 12 项 XHTTP 互通测试。
- Magisk / KernelSU / APatch Root 真机矩阵尚未执行，需按中文说明补测；CI 不能替代该证据。

## Rollback

回退本变更提交即可移除菜单项、预置分支、字符串资源、测试和说明；既有 VLESS/VMess 表单及 XHTTP 传输能力保持不变。
