# wanBox for Android v3.0.10-preview.2 更新说明

本版本为 `v3.0.10-preview.2` 预览版本（Pre-release），在保留既有 Root 模块真实遥测与会话状态管理的基础上，完成设置主页的 Jetpack Compose 八组收拢重构与冗余入口精简。

### 核心变更说明
* **设置主页 Compose 八组收拢**：一级设置主页接入 Jetpack Compose（`SettingsHomeComposeScreen`），保留顶部独立【分应用代理】入口，并将全部设置项收拢为八大一级分组：【运行、HTTP/TLS、DNS设置、路由、测速、主题与外观、日志与诊断、其他】。
* **取消独立高级设置与工具页**：移除右上角菜单与配置中的独立「高级设置与工具」页（`categoryAdvancedGroup`），将原高级设置子项按职责完整归并至八大分组（DNS 全量参数归入【DNS设置】，TLS 分片、应用层 TLS 版本及两项不安全开关归入【HTTP/TLS】且保持默认开启证书校验，规则集源与更新间隔归入【路由】，日志级别与清理缓存归入【日志与诊断】，全局自定义配置与重置归入【其他】）。
* **移除 4 项冗余 UI 入口并保留核心能力**：仅从界面层移除「Root 模块管理（`minimalModuleStatus`）」、「运行模式选择（`serviceMode`）」、「禁用混合入站（`disableMixedInbound`）」与「Clash API 固定展示项（`clashApiFixed`）」4 项入口；完整保留分应用代理（`perAppProxyEntry` / `proxyApps`）、FakeDNS（`enableFakeDns`）、双网络加速（`dualNetworkAcceleration`）与全局自定义配置（`globalCustomConfig`）。
* **单一 Root 模式与底层契约不变**：只保持单一 Root 模式，继续沿用现有 `RootModuleClient` 与 `DataStore.serviceMode = Key.MODE_ROOT`，不新增普通 VPN 模式或任何 AI 功能；所有偏好键名、默认值、存储委托与备份恢复数据契约均保持不变。
* **保留既有核心遥测与稳定性修复**：完整承接 `preview.1` 的模块真实内存/活跃连接数/运行时长遥测、Connected 会话状态过滤、未知语义归一化及 ALL 分页稳定 ID 修复。
* **对齐预览版本号**：预览渠道版本号更新为 `3.0.10-preview.2`，`PRE_VERSION_CODE=364`（对应客户端 APK `versionCode=1820`），正式版 `VERSION_NAME=3.0.9` 与 `VERSION_CODE=362` 保持原样。

### 兼容性与交付产物
* **覆盖升级目标**：面向相同包名（`com.lit008834.pixel.wanboxforandroid`）与原签名构建，构建号 `PRE_VERSION_CODE=364`（APK `versionCode=1820`），底层数据库与历史配置项完全向下兼容。
* **交付发布文件**：
  * 独立 ARM64 客户端安装包（`arm64-v8a` APK）
  * 纯 Root 模块包（适用于 Magisk / KernelSU / APatch）
  * 内嵌相同 APK 的整合型 Root 模块包
  * 对应产物的 `SHA256SUMS` 校验清单文件

### 注意事项与未测边界说明
* **尚未完成物理真机联调与覆盖安装实测**：本版本已完成 Debug/Release 构建、全量 JVM 单元测试及模拟器仪器测试对齐，但**尚未在真实 ARM64 Root 物理设备上进行模块挂载与透明代理联调，也尚未在真机上执行覆盖安装实测**。
* **问题反馈**：若在测试使用过程中遇到设置项跳转、模块状态暴露或连接异常，请保留相关诊断日志并反馈。
