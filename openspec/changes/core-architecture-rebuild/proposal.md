# 架构重建：core 骨架与端口落地（P1 / P2 第一刀）

## Why

`wanBoxForAndroid-架构重建工作词`（基线 `v3.0.10-preview.23`）给出的诊断在本次改动前逐条复核，全部与源码一致：

- `settings.gradle.kts` 只有 `include(":app")`，界面、后台服务、Room、协议编解码、平台适配全在一个 module。
- 状态单例 `database/DataStore.kt:27`（`object DataStore`）同时持有偏好、会话与服务句柄。
- 非 UI 包直接 `import android.*` / `androidx.*` 的文件 96 个；全仓 `!!` 254 处；`runBlocking` / `Thread.sleep` 30 处。
- `ui/ConfigurationFragment.kt` 4,376 行，同时读库、写库、起服务、调测速。
- 差距表 G1–G9 的核心是两条：**App 侧缺少模块管理面的一等客户端**（G3）、**节点与订阅真值仍在 App 侧 Room**（G2）。

因此重建的第一步不是继续改界面，而是先把「端口 + 用例 + 唯一状态源」在**不依赖 Android 的 core 里**立起来，让后续 P3–P6 有可接的骨架。

## What Changes

本变更只做 P1 骨架与 P2 第一批端口，**不迁移任何既有业务逻辑**，`:app` 的界面、Room、服务保持原样：

- 新增三个纯 JVM Gradle 模块并接入 `settings.gradle.kts`：
  - `:core-contracts`（端口接口 + DTO，仅依赖 `kotlinx-coroutines-core:1.6.4`，与 app 现有版本一致）
  - `:core-domain`（唯一状态源 `CoreEngine` + 三个用例）
  - `:core-testing`（假端口实现，供 JVM 测试与后续 `:adapter-android` 回归使用）
- `:app` 增加 `implementation(project(":core-domain"))` 与 `testImplementation(project(":core-testing"))`，让 CI 在编译 app 时连带编译 core（P1 要求：依赖但不下沉逻辑）。
- 按工作词 3.4 落地端口：`RootModulePort`、`ProfileRepository`、`SettingsPort`、`SubscriptionPort`、`TrafficPort`、`ProbePort`、`NotificationPort`；DTO 统一在 `Dtos.kt`，不含 `Context` / `Uri` / `Parcelable`。
- 落地第一批用例并配 JUnit4 测试（10 条）：`SelectNodeUseCase`（U-03）、`SetAutoStartUseCase`（U-18）、`ToggleServiceUseCase`（U-15/U-16），以及 `CoreEngine` 的事件归约。
- 新增本 openspec 变更记录；新增 `docs/rebuild/BASELINE-AUDIT.md` 记录文档与仓库实况的差异。

不在本变更内（后续阶段）：

- `:adapter-android` 与 `RootModulePort` 的 `wanboxctl` 实现（P2 第二批）
- 回环 HTTPS 管理面客户端、SSE、证书钉扎（P4）
- `DataStore` 状态下沉、界面逐屏换绑、Room 降级为缓存（P3 / P5 / P6）
- 命令行第二界面（P7）

## Capabilities

### New Capabilities

- `core-architecture`：定义 core 层的边界、端口集合、唯一状态源与无 Android 可运行的验收方式。

### Modified Capabilities

- 无。既有 `android-application`、`libcore-integration`、`repository-governance`、`theme-system` 规范未改变。

## Impact

- 构建：新增 3 个 Gradle 模块；`:app` 多两个 project 依赖；不引入新的运行时第三方库。
- 运行：无行为变化，尚无调用方使用新端口。
- 风险：Gradle 配置阶段新增模块可能影响 CI；由 `verify-android` 的 app 编译与后续 `:core-domain:test` 验证。
- 回退：删除 3 个模块目录、`settings.gradle.kts` 两行、`:app` 两行依赖即可完全回退，无数据与行为影响。
