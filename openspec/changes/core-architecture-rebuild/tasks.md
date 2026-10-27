# 任务：core 骨架与端口

## 1. 骨架（P1）

- [x] `settings.gradle.kts` 增加 `:core-contracts`、`:core-domain`、`:core-testing`
- [x] 根 `build.gradle.kts` 声明 `org.jetbrains.kotlin.jvm` 2.0.21（`apply false`）
- [x] 三个模块的 `build.gradle.kts`，JVM toolchain 17，协程版本与 app 一致（1.6.4）
- [x] `:app` 增加 `implementation(project(":core-domain"))` 与 `testImplementation(project(":core-testing"))`

## 2. 端口（P2 第一批）

- [x] `RootModulePort`（status / events / startReload / stop / selectNode / setAutoStart / validate / apply / dataUpdate / logs）
- [x] `ProfileRepository`
- [x] `SettingsPort`（含 `Session` 嵌套接口，拆分偏好与会话）
- [x] `SubscriptionPort`
- [x] `TrafficPort`
- [x] `ProbePort`
- [x] `NotificationPort`
- [x] DTO 统一在 `Dtos.kt`，无 Android 类型
- [ ] `RootModulePort` 的 `wanboxctl` 适配器（委托 `bg/RootModuleClient`）
- [ ] `ProfileRepository` 的 Room 适配器（委托 `ProfileManager` / `GroupManager`）
- [ ] `:adapter-android` 模块

## 3. 用例与状态

- [x] `CoreEngine`：唯一状态源，事件归约，无协程作用域
- [x] `SelectNodeUseCase`（U-03）
- [x] `SetAutoStartUseCase`（U-18）
- [x] `ToggleServiceUseCase`（U-15 / U-16）
- [ ] `RefreshStatusUseCase` 与事件流收集器
- [ ] `ImportSubscriptionUseCase`（U-07）

## 4. 测试

- [x] `SelectNodeUseCaseTest`（4 条：成功 / 节点不存在 / 错误码映射 / 清除旧错误）
- [x] `ServiceControlTest`（6 条：自启成功 / 自启被拒 / 启停 / 状态事件 / 流量事件 / 失败事件）
- [x] 本地用 Kotlin 2.0.21 + JUnit4 跑通 10/10
- [ ] CI 增加 `:core-domain:test` 门禁
- [ ] P0 的 30 条用例回归（需 Android 测试环境）

## 5. 后续阶段入口

- [ ] P3：`DataStore` 会话字段经 `SettingsPort.Session` 适配，界面改订阅 `CoreEngine.state`
- [ ] P4：回环 HTTPS 管理面客户端（Bearer token + 证书钉扎 + SSE），与 CLI 适配器双实现
- [ ] P5：逐屏换绑（GroupFragment → MainActivity → ConfigurationFragment）
- [ ] P6：Room 降级为缓存，真值改由管理面读回
- [ ] P7：命令行第二界面对齐验收第 5 条
