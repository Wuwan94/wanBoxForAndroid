# wanBox v3.0.10-preview.25 — 核心模块接入与验证链

本版在 preview.24 的基础上接入了独立的核心模块，并把核心单元测试纳入持续集成。**面向普通用户的可感知行为与 preview.24 基本一致**：界面、协议、路由与数据读写仍走原有实现，本版只是把新骨架编译进来并验证它能在无 Android 环境下独立成立。

> 如果你只关心能不能用：可以按下面的「安装与兼容性」直接覆盖安装 preview.24，无需卸载。

## 主要变化

### 新增核心模块（纯 JVM，零 Android 依赖）

仓库拆出三个纯 JVM 模块，构建时与 `:app` 一起编译：

- `:core-contracts`：7 个端口与统一 DTO —— `RootModulePort`、`ProfileRepository`、`SettingsPort`、`SubscriptionPort`、`TrafficPort`、`ProbePort`、`NotificationPort`。
- `:core-domain`：`CoreEngine` 作为唯一状态源，加三个用例 `SelectNodeUseCase`（切换节点）、`SetAutoStartUseCase`（开关自启）、`ToggleServiceUseCase`（启停服务）。
- `:core-testing`：端口的内存实现，供测试直接驱动状态机。

这些模块不引用任何 `android.*`/`androidx.*`，也不持有协程作用域，因此可以在普通 JVM 里同步跑完整个流程。

### 验证链

- 核心用例 **15 条**，覆盖成功路径、模块拒绝、模块不可用、自启写入顺序、订阅导入与状态刷新。
- 新增 `buildScript/verify-core-local.sh`：无 Android SDK 时也能编译核心、跑用例、做静态检查（平台引用、许可证头、依赖方向）。
- CI 新增 `Verify Core` 工作流，只跑 `:core-domain:test`，几分钟内给出结论，不被原生库构建拖住。
- `Verify Android Build` 增加 `Test core modules` 步骤，核心测试与既有 Debug 单测一起进门禁。

### 继承自 preview.24 的修复

preview.24 包含的安全与性能改动（云备份不再含节点库/配置库/偏好、本地面板 CSP、移除 `largeHeap`、网络回调去 `runBlocking`、临时查询改用固定线程池等）本版全部保留。

## 安装与兼容性

- 适用环境：**ARM64、Android 12+、Root**。
- 管理器版本：`3.0.10-preview.25`；Android `versionCode`：**10015**。
- Root 模块版本码：**2003**。请使用同一发布页中的 APK 和模块，不要跨版本混用。
- 本发布包含 APK、Root 模块、带管理器的 Root 模块，以及 `SHA256SUMS`。
- 签名与 preview.24 相同，可直接覆盖安装，配置与数据保留。

### 签名（重要）

- 发布证书 SHA-256：`80ECEE1C42D861D59CDC460447335FC4BFDF80103D6225A17C1BDF83A33521F6`
- 该证书自 preview.24 起启用。**仍在用更早旧签名的安装无法覆盖升级**，需先导出配置，再卸载重装。
- 升级前请核对本页文件校验和与签名信息。

## 验证与限制

本发布包需通过 Preview Build 的 LibCore 与 Root 模块构建、Go/Python 测试、核心 JVM 测试、Debug 单测、APK ABI/包名/版本/签名校验，以及发布文件 SHA-256 校验。

必须说明的限制：

- **核心模块目前只是接入，尚未接管界面。** 设置读写、节点管理、服务启停仍由原实现执行，本版不改变这些行为的运行时表现。
- Android 侧的适配层（`RootModulePort` 的真实实现）**尚未落地**：`RootModuleClient` 位于 `:app` 内，直接包装会形成 `app → adapter → app` 的循环依赖，方案待定后再动。
- 当前环境没有可用的 KVM 模拟器；KSU、内核 eBPF 挂载、真实流量接管、管理器—Root 模块链路仍需真机回归。
- 预览通道只保留最新一个预览版，旧预览版的发布页会被清理；需要旧包请自行留档。
