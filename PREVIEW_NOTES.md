
# wanBox v3.0.10-preview.32 — 内核升级至 sing-box v1.15.0-alpha.13

本版基于 preview.31，仅升级内置 Root 模块的 sing-box 内核：reF1nd/sing-box `v1.15.0-alpha.11-reF1nd` → `v1.15.0-alpha.13-reF1nd`（commit `17397d022647b73378705e1f91ff031dddaaec21`），并同步 libcore 的 fork 依赖版本。管理器界面、代理策略与配置格式均未改动。

## 变更

- 内核升级至 sing-box v1.15.0-alpha.13（reF1nd 分支），包含上游 connect-udp 的 UDP Fast Open 等更新；上游此版本未附更新日志。

## 安装与兼容性

适用环境为 **ARM64、Android 12+、Root**（Magisk / KernelSU / APatch）。管理器版本为 `3.0.10-preview.32`，Root 模块版本码为 **2010**，Android APK `versionCode` 为 **10050**；请使用同一发布页中的 APK 与模块。本版 APK 为瘦管理器，不含内嵌核心，必须配合本发布页的 Root 模块使用。它与 preview.31 使用相同包名和官方签名，不涉及数据库、备份格式或模块配置迁移，可直接覆盖升级并保留原有数据。

## 已知限制

瘦管理器不支持免 Root 单机代理、本地测速、STUN 探测和本地 URL 测试（TCP 重测与仪表盘的当前连接测试可用）。预览通道只保留最新一个预览版，需要旧包请自行留档。
