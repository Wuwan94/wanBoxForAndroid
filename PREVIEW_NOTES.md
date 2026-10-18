# wanBox for Android v3.0.10-preview.5 更新说明

本版本聚焦界面精简：把前台收敛为轻量管理前端，移除界面中的 Root 标识，并让状态刷新优先走模块推送。内核与节点热切换能力与 preview.4 一致。

### 核心变更

* **设置页由八组收敛为六组**：DNS 与路由合并为「网络」，HTTP/TLS 与日志诊断合并为「高级设置」，分应用代理保留顶部独立入口。设置首页右上角的「工具」「文档」入口移除。合并只改变一级入口的可见性，分区与偏好键不变，覆盖升级不影响已保存设置。
* **移除界面中的 Root 标识**：仪表盘顶部硬编码的 "Root" 小标题删除；服务开关的无障碍描述由「Root 模块管理」改为「服务开关」；模块错误提示不再要求「授予 Root 权限」，改为提示确认模块是否已安装并启用；文档页徽章与说明改为「模块服务」。设备侧仍由模块运行，不引入 VPN 模式，底层能力未变。
* **状态刷新优先使用模块推送**：绑定后订阅模块 `/v1/events`，事件到达即更新界面，与轮询共用同一应用点。推送在线时跳过轮询，轮询间隔由 2 秒放宽到 5 秒仅作兜底；连接失败或模块为旧版时静默回退，行为与之前一致。
* **节点切换保持不重启**：热切换仍走 Clash API 选择器，状态由 `runtime/selection.json` 承载，`supervisor` 持久化不会回退选择。本版本在此基础上减少了切换期间的 su 采样竞争。

### 兼容性与产物

* 包名 `com.lit008834.pixel.wanboxforandroid`、原签名、数据库与备份格式不变，可直接覆盖 v3.0.9 与 v3.0.10-preview.4。
* 仅 arm64-v8a、Android 12+、Root 环境，仍为纯 Root 模块，不提供 VPN 模式。
* 预览版本 `3.0.10-preview.5`，模块版本代码 `367`，APK 版本代码 `1835`。
* 交付 ARM64 APK、纯 Root 模块、内嵌同一 APK 的整合模块与 `SHA256SUMS`。
* 预览渠道只保留最新一版：发布本版时会自动清理更早的预览 Release 与 tag，正式版不受影响。

### 验证边界

* 本地完成 `testOssDebugUnitTest`（313 项）、`testPreviewDebugUnitTest`、`compilePreviewDebugAndroidTestKotlin`、`minifyPreviewReleaseWithR8`、`check_android_security.py`，以及模块的 `go test ./...` 与 11 项 Python 测试，均通过；本地 R8 使用临时密钥，仅验证压缩通过。
* **尚未在真实 ARM64 Root 设备验证**：模块挂载、TUN 与 auto_redirect 联调、节点切换录屏、杀 App 后代理是否持续、覆盖安装实测均未进行。
* **仪器测试未运行**：`MinimalUiTest` 的分组断言已随界面改动更新（`dns` → `network`），但本机无模拟器，未实际执行。
* **推送状态流依赖模块版本**：若设备上仍是 preview.3 及更早的模块（`service.sh` 未改为 `serve`），事件流连不上，会自动回退轮询，但拿不到推送收益。

### 已知问题

* 节点与订阅数据仍走原有 Room 与快照路径，`/v1/nodes`、`/v1/subscriptions`、`/v1/config` 尚未实现。
* 启停与配置应用仍经 `su` 调用，`ModuleApiClient` 目前只承担状态流。
