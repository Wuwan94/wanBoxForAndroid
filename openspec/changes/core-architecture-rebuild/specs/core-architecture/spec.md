# core-architecture Specification

## Purpose

定义 core 层的边界、端口集合与验收方式：核心不依赖 Android 框架，状态只有一个来源，外部能力全部经接口注入。

## Requirements

### Requirement: core 不依赖 Android 框架

`:core-contracts`、`:core-domain` MUST NOT 出现 `import android.*`、`import androidx.*`、`io.nekohasekai.sagernet.{ui,bg,database,fmt}` 的引用，也 MUST NOT 引用 `moe.matsuri.nb4a.NativeInterface` 等 JNI 门面。DTO MUST NOT 含 `Context` / `Uri` / `Parcelable` / `Bitmap`。

#### Scenario: 静态扫描

- **GIVEN** core 模块源码
- **WHEN** 执行全量 import 扫描
- **THEN** 上述引用数量为 0
- **AND** 依赖清单仅含 `kotlinx-coroutines-core` 与项目内模块

### Requirement: 单一状态源

业务状态 MUST 由 `CoreEngine` 持有的 `StateFlow<CoreSnapshot>` 提供；界面、通知、磁贴 MUST 订阅同一份快照，MUST NOT 各自缓存节点、选择或连接态。

#### Scenario: 事件驱动

- **GIVEN** `CoreEngine` 与已注册的模块事件
- **WHEN** 收到 `ModuleEvent.StateChanged`
- **THEN** `state.value.status` 更新
- **AND** `state.value.selection` 与状态中的选择一致

### Requirement: 外部能力经端口注入

所有外部依赖（模块 CLI / 管理面、Room、偏好、订阅网络、流量、探测、通知）MUST 通过 `:core-contracts` 中的端口访问；核心 MUST NOT 直接起进程、读数据库或访问文件。

#### Scenario: 用例只依赖接口

- **GIVEN** `SelectNodeUseCase`
- **WHEN** 注入 `RootModulePort` 与 `ProfileRepository` 的假实现
- **THEN** 用例可完成节点校验、下发选择与状态更新
- **AND** 不出现任何平台 API

### Requirement: 用例返回显式结果

用例 MUST 返回 `CoreResult`，失败时给出 `[a-z_]{1,80}` 形式的错误码；MUST NOT 用异常做常规控制流，MUST NOT 新增 `!!` 解包。

#### Scenario: 节点不存在

- **GIVEN** 一个不存在的 `profileId`
- **WHEN** 调用 `SelectNodeUseCase`
- **THEN** 返回 `CoreResult.Failure("profile_not_found", ...)`
- **AND** 模块端口未被调用
- **AND** 状态中的选择保持不变

### Requirement: 无 Android 可运行

core 用例 MUST 能在 JVM 测试中运行，不依赖 Android 运行时或真机。

#### Scenario: 本地回归

- **GIVEN** 仓库源码，无 Android SDK
- **WHEN** 编译三个 core 模块并运行 JUnit4 用例
- **THEN** 全部通过（当前 10/10）
