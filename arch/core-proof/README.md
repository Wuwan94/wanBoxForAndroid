# core-proof · 无界面验证工程

证明「删掉界面后核心仍能跑通」的独立 JVM 工程。不属于 Android 构建，不会被 `:app` 引用。

```
core/contracts   ← 接口（Contracts.kt）+ DTO（dto/），仅 coroutines-core
core/domain      ← 状态机、六组用例、协议编解码、纯函数策略（只依赖 contracts）
adapters/memory  ← 内存实现（CLI 与测试共用，证明换实现不动核心）
cli              ← 第二个入口：headless 跑完全部用例
tests            ← 91 个 JUnit5 用例，只依赖 core + adapters
```

## 跑法

```bash
bash arch/core-proof/build.sh      # 编译 + 测试 + CLI + 架构守卫，一条命令
```

脚本自己找便携 JDK17 与 Maven Central 的 `kotlin-compiler-embeddable`，不依赖 Gradle、不依赖 Android SDK。

有 Gradle 的机器上等价于：

```bash
./gradlew -p arch/core-proof :tests:test :cli:run
```

## 它证明了什么

| 验收项（原始规范第六条） | 证据 |
| --- | --- |
| 删掉 ui 后 core 仍通过全部用例 | 本工程根本没有 ui 模块；`tests` 只依赖 `:core:domain` 与 `:adapters:memory`，91 个用例全绿 |
| core 依赖清单里没有界面/平台框架 | `core/*/build.gradle.kts` 只有 coroutines 与 `project(":core:contracts")`；`arch-check.sh` 扫 import 与平台类型，零命中 |
| 任意两个模块之间没有循环依赖 | `arch-check.sh` 按允许边清单校验方向 + 拓扑检测环，5 模块 6 条边全合规 |
| 新加一个界面不需要改 core | 已经有两个：`:cli` 与 `:tests` 共用同一份 `AppCore`，core 未为任何一方开洞 |
| 同一份 core 能接出第二种界面 | `io.wanbox.cli.MainKt` 无 Android 运行，40+ 项断言覆盖全部用例组 |
| 单一状态源 | `AppCore.snapshot` 是唯一快照，通知/磁贴/小组件端口拿到的是同一个对象（`assertSame` 断言） |

## 28 条用例的落点

| 用例 | 实现 | 测试 |
| --- | --- | --- |
| UC-CONN-01/02/03 | `AppCore.connect / disconnect / switchTo` | `ConnectionTest` |
| UC-PROF-01 ~ 06 | `ProfileUseCases.kt` + `LinkCodec` | `ProfileTest`、`LinkCodecTest` |
| UC-SUB-01 ~ 04 | `SubscriptionUseCases.kt` + `SubscriptionPolicy` + `BackoffPolicy` | `SubscriptionTest` |
| UC-ROUTE-01/02、UC-APP-01 | `RouteUseCases.kt` | `RouteTest` |
| UC-NET-01、UC-DATA-04 | `SettingsUseCases.kt` + `SettingsMigration` | `SettingsTest` |
| UC-DATA-01/02/03、UC-LOG-01 | `DataUseCases.kt` + `LogRedactor` | `DataTest` |
| UC-STAT-01/02 | `StatsUseCases.kt` + `TrafficAggregator` + `ByteFormat` | `StatsTest` |
| UC-SYS-01/02/03、UC-ROOT-01 | `AppCore.connect / onBoot / onPlatformAction`、`RootCommandPolicy` | `ConnectionTest`、`PlatformTest` |

## 它没有证明什么

- **没有接真实 Room/Service/Binder**：那是 `:adapters:android` 的活，需要 Android SDK 才能编译。
- **没有真机验证**：`USE-CASES.yaml` 里 28 条状态是 `implemented`，不是 `frozen`。`frozen_at` 仍为空——真机逐条观察行为一致之后才算冻结基线。
- **`ConfigBuilder.kt`（2133 行）没搬**：它依赖 `DataStore`/`SagerDatabase`/`Toast`/`SingBoxOptions`，必须连 Android 构建一起搬，否则搬过来就是不可编译代码。
- **`buildSingBox*` 没搬**：依赖 okhttp3/gson/org.json，属适配层。
- **`fmt/` 是重实现不是平移**：`LinkCodec` 按规则重写（JDK Base64 + 手写 MiniJson），保持 core 零第三方依赖；与上游 `parse*` 的行为差异未逐协议对拍。

## 实测结果（2026-10-27，Windows + JDK 17.0.13）

```
$ bash build.sh
== 1/5 编译 contracts + domain + adapters:memory + cli ==
   class 数：125
== 2/5 编译测试 ==
   test class 数：78
== 3/5 跑用例测试 ==
[        91 tests found           ]
[        91 tests successful      ]
[         0 tests failed          ]
== 4/5 跑 CLI（第二个入口） ==
CLI: ALL PASS
== 5/5 架构守卫 ==
  PASS  core 无 android/androidx/ui/bg/database 引用
  PASS  core 未出现 Context/Uri/Bitmap/Parcelable 类型
  PASS  adapters / cli / tests 无平台/历史包引用
  PASS  依赖方向合规，无循环依赖（模块 5 个 / 边 6 条）
  PASS  用例基线 28 条，全部在代码中可追溯
ARCH CHECK: PASS

BUILD: OK
```

## 环境说明

- 本机无 Gradle 发行包（`services.gradle.org` 连接被重置），所以走 Maven Central 的 `kotlin-compiler-embeddable` 直编译，不依赖 Gradle。
- `build.sh` 里的 `MSYS2_ARG_CONV_EXCL='*'` 是必须的，否则 Git Bash 会把 `-cp` 里的 `;` 当路径列表改写，classpath 只剩第一项。
- `$HOME` 需要 `cygpath -m` 归一化，`java.exe` 不认 `/c/Users/...` 这种 MSYS 路径。
- 依赖清单（都在 `~/.workbuddy/binaries/m2`）：kotlin-compiler-embeddable / kotlin-stdlib / kotlin-script-runtime / kotlin-reflect / kotlin-daemon-embeddable / trove4j / annotations / coroutines / junit-console，版本 2.0.21 与 1.9.0 / 1.11.3。
