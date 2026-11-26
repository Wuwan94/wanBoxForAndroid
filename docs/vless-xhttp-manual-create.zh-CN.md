# 手动新增 VLESS + XHTTP 节点

## 入口与表单

在配置列表打开「+」→「手动设置」→「VLESS + XHTTP」，即可新建使用 XHTTP 传输的 VLESS 节点。菜单标签使用字符串资源，在默认语言与简体中文中均显示协议名「VLESS + XHTTP」。

该入口复用现有 VMess/VLESS 设置页，不新增另一套表单。新建时应用以 `alterId = -1` 标记 VLESS，并将传输类型预置为 `xhttp`；现有表单负责展示 XHTTP 模式与扩展参数。XHTTP 模式沿用 Bean 的默认值 `auto`。普通 VLESS 新建仍使用 `tcp` 默认传输，VMess 的创建方式不变。

## 范围

本功能只补齐「手动新建」入口和初始 Bean 选择，不改 VLESS/XHTTP 的核心协议实现、订阅或剪贴板识别，也不改变现有连接、策略组或 HWID 行为。HWID 本次不外发；Mieru 不在本 PR 范围内。

## 验证状态

| 验证项 | 状态 |
|---|---|
| OSS/Preview 全量 JVM 测试与 `:core-domain:test` | 本地通过：`:app:testOssDebugUnitTest`、`:app:testPreviewDebugUnitTest`、`:core-domain:test` |
| Android 构建与安全门禁 | 本地通过：OSS/Preview x86_64 Debug 包构建成功；`tools/check_android_security.py ossDebug previewDebug` 通过 |
| `tools/` 单测与治理门禁 | 本地通过：132 项 Python 单测、`tools/pre-push-check.py`、`tools/check_core_gate.py`（345 个源文件）、版本文档检查及 `git diff --check`；保留项源码检查通过，但本地未落盘的 `assets/sing-box/*.db.xz` 未做产物级检查 |
| `Verify Android Build` | PR CI 待运行；工作流包含 Xray v26.11.9 的 12 项 XHTTP 互通矩阵 |
| Go `-race` | 本 PR 未改 Go/libcore，故不适用本地 Go race 门禁；PR CI 仍会执行核心工作流 |

## 需 Root 真机矩阵实测

尚未在 Magisk、KernelSU、APatch 真机上验证。矩阵应确认 Root 模式下菜单可达、入口能打开现有表单、传输初值为 XHTTP、保存后重开仍保持 VLESS/XHTTP 配置，并使用实际 XHTTP 服务端验证连接与错误提示。当前自动化测试不证明 Root 模块与真实设备网络链路行为。
