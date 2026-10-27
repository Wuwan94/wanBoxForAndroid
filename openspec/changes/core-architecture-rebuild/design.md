# 设计：core 骨架与端口

## 1. 模块与依赖方向

```
:app ──────────────► :core-domain ──────────────► :core-contracts
  │                        ▲
  └── testImplementation ──┴── :core-testing ────► :core-contracts
```

- `:core-contracts`：接口与 DTO，纯 Kotlin/JVM，只依赖 `kotlinx-coroutines-core:1.6.4`（与 app 现有版本一致，不新增运行时库）。
- `:core-domain`：`api(:core-contracts)`，不含 Android；测试用 `:core-testing` + JUnit4 + `kotlinx-coroutines-test`。
- `:core-testing`：`api(:core-contracts)`，只放假实现，不依赖 domain，避免「测试代码反向绑定业务实现」。
- `:app`：`implementation(:core-domain)`，P1 阶段只编译不调用。

未建 `:adapter-android`：它需要 Android Library 插件与真实 `RootModuleClient` 委托，属 P2 第二批。先让 core 在无 Android 环境下立住，再接适配层，避免一次性把 CI 风险叠在一起。

## 2. 包名

沿用仓库根包，core 放 `core` 子树（工作词 3.2）：

- `io.nekohasekai.sagernet.core.contracts`
- `io.nekohasekai.sagernet.core.domain`
- `io.nekohasekai.sagernet.core.testing`

不动既有 `io.nekohasekai.sagernet.ui/bg/database/fmt/...`，迁移期原地保留。

## 3. 状态归属

`CoreEngine` 是唯一状态源，持有 `StateFlow<CoreSnapshot>`。它**不持有协程作用域**：事件由适配层收集后 `onEvent(...)` 投递，用例同步驱动。这样：

- core 用例在 JVM 单测里可 `runBlocking` 直接验证，不依赖 Android 生命周期；
- 界面 / 通知 / 磁贴只订阅同一个 `StateFlow`，不再各自缓存业务状态；
- 后续 P3 把 `DataStore` 的会话字段以适配器方式接进来时，不需要改引擎签名。

`CoreSnapshot` 只含 `status` / `selection` / `traffic` / `lastErrorCode`，全部为不可变 DTO。

## 4. 错误处理

- 端口失败抛 `ModuleError(code)`，code 沿用 `RootModuleClient.safeError` 的 `[a-z_]{1,80}` 约定。
- 用例返回 `CoreResult.Success` / `CoreResult.Failure(code, message)`，不把异常当控制流，也不新增 `!!`。
- 失败时同时写入 `lastErrorCode`，界面只需渲染快照，不需要自己判断业务分支。

## 5. 端口与现状的对应

| 端口 | 现状实现（P2 委托目标） | 阶段 |
| --- | --- | --- |
| `RootModulePort` | `bg/RootModuleClient.kt`（`wanboxctl` CLI，14 条命令） | P2 |
| `ProfileRepository` | `database/ProfileManager.kt`、`GroupManager.kt` | P2 |
| `SettingsPort` | `database/DataStore.kt`（偏好 + 会话拆分） | P3 |
| `SubscriptionPort` | `group/GroupUpdater.kt`、`bg/SubscriptionUpdater.kt`（调度留适配层） | P5 |
| `TrafficPort` | `aidl/TrafficData*`、`bg/proto/TrafficUpdater.kt` | P5 |
| `ProbePort` | `bg/proto/NodeTestRunner.kt`、`UrlTest.kt`、`utils/LandingIpManager.kt` | P5 |
| `NotificationPort` | `bg/ServiceNotification.kt`、`widget/*`、`TileService` | P5 |

## 6. 验证方式

- 本地（无 Android SDK）：用 Kotlin 2.0.21 编译器直接编译三个模块并跑 JUnit4，当前 10/10 通过。
- CI（权威）：`:app` 依赖 core 后，`verify-android` 的 `assembleOssDebug` / `assembleOssRelease` 会连带编译 core；`testOssDebugUnitTest` 不受影响。
- 后续需在 CI 增加 `./gradlew :core-domain:test`，让 core 单测成为门禁（P2 与 `:adapter-android` 同批提交）。

## 7. 与工作词的偏差

| 偏差 | 原因 | 处置 |
| --- | --- | --- |
| 未建 `:adapter-android` | 需 Android Library 插件与真实委托，属 P2 第二批 | 下一批补齐，接口已按委托目标设计 |
| 未做 30 条用例的完整回归 | P0 需要真机 / Android 测试环境；本轮只做可 JVM 验证的三条用例 | 后续按 P0 清单逐条补，优先 `AuditDatabaseTest` 相关 |
| 保留 `arch/` 目录 | 上一轮实验性 core-proof，与本变更的 `core/` 并存 | 待 P2 完成后评估删除，避免两套结构并存 |
| 签名已换新证书 | preview.24 发布时按用户要求更换（工作词 G9 假设签名不变） | 记录在 `docs/rebuild/BASELINE-AUDIT.md`，重建不回滚签名 |
