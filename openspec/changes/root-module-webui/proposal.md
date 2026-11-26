# 提案：Root 模块静态 WebUI（PR-D）

## 背景

Root 模块已有固定的 `wanboxctl` 状态与生命周期命令，但模块包没有管理器可直接打开的本地页面。管理器 WebUI API 在 Root 权限边界内，页面实现必须避免任意 shell、原始日志或网络写接口。

本变更选择 KernelSU 原生 WebUI API 的最小接缝：静态页面只映射到固定 `wanboxctl` 子命令；资源由显式白名单打包与安装；页面能力和包体积分别由自动测试门禁约束。

## 目标

- 在 Root 模块中增加本地状态、启动/停止/重启和有界事件摘要 WebUI。
- 固定 bridge 命令和返回结构，折叠错误，不读取原始日志。
- 让首次安装与热更新安装后的管理器可以读取页面资源，同时不放宽其他文件权限。
- 增加打包、合包、Go 安装白名单和源/归档 WebUI 体积门禁。
- 同步中文文档与 OpenSpec，写明运行时兼容边界和回退方案。

## 非目标

- 不改 App Edge-to-Edge/WindowInsets、Root App UI、PR-C Google 网络策略、HWID、Root HTTP 写 API 或任何不相关功能。
- 不将 Magisk 或未经验证的第三方 WebUI 运行时宣称为支持目标。
- 不读取原始模块日志，不上传状态，不生成或持久化 HWID。
- 不修改 `nb4a.properties`、正式/预览发布流程、Release/Tag 语义或个人网站。
- 不合并 PR-C，不把 PR-C 与 PR-D 放入同一分支或 PR，不发布版本。
- Root 真机验收与本地/CI 测试分开报告；本地通过不等同于设备兼容。
