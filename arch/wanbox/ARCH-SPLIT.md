# wanBox 三层拆分 · 项目落版规范

> 把「核心逻辑 + 接口 + 可视化窗口」的通用工作词，落成这个仓库的具体模块、接口、用例与验收命令。
> 基线：`Wuwan94/wanBoxForAndroid`，`main@44c359d`（含 `v3.0.10-preview.23` 的安全/性能修复）。
> 现状：单 Gradle 模块 `:app`，`app/src/main/java` 下 360 个文件；`libcore`（Go，AAR）与 `rootmodule`（Go，root 侧）为原生依赖。

---

## 零、现状量化（拆分起点，不是评价）

| 区 | 文件数 | 说明 |
| --- | --- | --- |
| `ui/` | 78 | 含 43 个 screen 级类、20 个 `ui/profile/*SettingsActivity` |
| `widget/` | 21 | 桌面小组件、QS 磁贴，同样直接读 `DataStore` |
| `bg/` | 40 | `BaseService`（进程 `:bg`）、`RootTunService`、`RootModuleClient` |
| `database/` | 18 | `DataStore`(object)、`ProfileManager`(object)、Room 实体与 DAO |
| `fmt/` | 46 | 协议解析/格式化（纯逻辑，最易先搬） |
| `group/` | 5 | `GroupUpdater`（订阅更新） |
| `utils/` | 29 | 网络监听、HTTP 客户端、包缓存等 |

耦合证据：`ui/` 与 `widget/` 中 **47 个文件**直接 import 并调用 `ProfileManager.*` / `DataStore.*` / `BaseService.*`；连接开关是 `DashboardFragment.kt:41`、`ConfigurationFragment.kt:3916`、`BackupFragment.kt:478`、`QuickDisableShortcut.kt:47` 各自直接 `SagerNet.startService()/stopService()`。这就是要拆掉的东西。

---

## 一、目标模块（Gradle 层）

```
:core:contracts     纯 Kotlin/JVM，仅 coroutines-core  ← 接口（Contracts.kt）+ DTO（dto/）
:core:domain        纯 Kotlin/JVM，仅依赖 contracts     ← 规则/状态机/用例/纯函数
:adapters:memory    纯 Kotlin/JVM，仅依赖 contracts     ← 内存实现（CLI 与测试共用）
:adapters:android   Android Library                    ← Room、Service、Binder、WorkManager、文件、网络
:ui                 Android Library                    ← 视图与绑定，只依赖 contracts
:cli                JVM 应用（无 Android）              ← 第二个界面：headless 跑通全部用例
:tests              JVM 测试                            ← 只依赖 core + adapters
:app                Application/Activity 组装 + DI       ← 只剩装配与生命周期
```

已落地（2026-10-27）：`core-proof/` 里的 `:core:contracts`、`:core:domain`、`:adapters:memory`、`:cli`、`:tests` 五个模块真实可编译可测。`:adapters:android`、`:ui`、`:app` 仍在原仓库内，等接上 Android 构建后按 S2/S3 迁移。

关于「只允许三层」：`core` 拆成 `contracts` 与 `domain` 两个 Gradle 模块，但**在架构上仍是一层**。它挡住的是「domain 反向依赖适配层」——contracts 零依赖，domain 只能依赖 contracts，适配层与 UI 都依赖 contracts，依赖倒置才成立。`:adapters:memory` 与 `:adapters:android` 是同一层的两个实现，`arch-check.sh` 用允许边清单固化这些方向。

`libcore` 与 `rootmodule` 不动：它们是 `:adapters:android` 背后的实现细节，通过现有 AAR/进程边界调用，不进 core。

---

## 二、接口清单（`:core:contracts` 唯一对外文件）

一组接口放一个文件 `Contracts.kt`，DTO 放同模块 `dto/`。签名草案见 `contracts.kt`，此处只列职责与替换对象：

| 接口 | 替换掉的现状 | 关键方法 |
| --- | --- | --- |
| `ProfileRepository` | `ProfileManager`（object，含 Room 调用与 listener） | `observe(): Flow<List<ProfileSummary>>`、`get(id)`、`create/update/delete/move` |
| `ConnectionController` | `SagerNet.startService()/stopService()` + `DataStore.serviceState` | `connect(profileId?)`、`disconnect()`、`state: Flow<ConnectionState>`、`switchTo(profileId)` |
| `SubscriptionGateway` | `GroupUpdater.executeUpdate()` + `SubscriptionUpdater` | `checkDue()`、`update(groupId, byUser)`、`history(groupId)` |
| `RouteRepository` | `RouteFragment` 直接写 `RuleEntity` | `rules(): Flow<List<RouteRule>>`、`upsert/delete/reorder` |
| `SettingsStore` | `DataStore`（object，40+ 字段，SharedPreferences 支持） | `snapshot(): Flow<SettingsSnapshot>`、`update(patch)` |
| `TrafficMeter` | `ProfileManager.updateTraffic` + `TrafficChartActivity` 自算 | `samples(window): Flow<TrafficSnapshot>`、`reset(profileIds)` |
| `FileGateway` | `BackupFragment`/`ConfigurationFragment` 直接读写 URI | `importFrom(uri)`、`export(bundle)`、`pickDestination()` |
| `AssetGateway` | `AssetsActivity`、图标缓存 | `ensureAssets()`、`iconFor(profile)` |
| `PermissionGate` | 各 Activity 里零散的 `checkSelfPermission` | `requireVpn()/requireNotification()/requireStorage()` |
| `PlatformPorts` | `SagerNet`/`TileService`/widget 各自读状态 | `updateWidget()`、`updateTile()`、`notifyState()` |
| `Clock` / `Logger` | `System.currentTimeMillis()`、`Logs` | `now()`、`log(level, tag, msg)` |

DTO 一律不可变 `data class`，不带 Android 类型（不含 `Uri`/`Context`/`Bitmap`，用 `String` 路径或字节数组代替）。

---

## 三、状态归属

- 唯一状态源：`domain` 内的 `AppState`（连接态、当前节点、订阅待更新数、待确认的错误），由 `StateHolder` 持有，`StateFlow<AppSnapshot>` 输出**只读快照**。
- UI 不得缓存业务状态：ViewModel 只持有「页面级 UI 状态」（展开/滚动/输入草稿），刷新以 `AppSnapshot` 为准。
- `DataStore` 降级为 `SettingsStore` 的 Android 实现，删除 `object` 语义；`serviceState` 从 `DataStore` 移到 `ConnectionState`。
- `ProfileManager` 拆成：Room DAO（adapters）+ `ProfileRepository` 实现（adapters）+ 领域规则（domain：排序、分组、去重、删除时清理订阅）。

---

## 四、用例基线（`USE-CASES.yaml`）

`USE-CASES.yaml` 里 28 条用例，每条四要素：**谁触发 / 输入 / 输出 / 副作用**，并附现有实现位置与验收方式。这份文件就是重建的验收基线：

- 每条用例在 `:cli` 里对应一个 `Case` 实现，跑 fake adapters，断言输出与副作用；
- 每条在 `:ui` 至少有一个入口映射（页面或快捷方式），迁移时删掉旧代码前先确认该用例在新链路通过；
- 用例只描述**可观察行为**，不描述实现（例：`UC-CONN-01 连接` 的副作用是「服务进入 `Connected`，通知出现，磁贴图标更新」，不写调用哪个类）。

节选（完整见 yaml）：

| ID | 用例 | 触发 | 关键副作用 |
| --- | --- | --- | --- |
| UC-CONN-01 | 连接 | 主界面开关 / 磁贴 / 小组件 / 快捷方式 | 服务 `Connected`、前台通知、磁贴与小组件刷新 |
| UC-CONN-02 | 断开 | 同上 | 服务 `Idle`、通知移除、流量落库 |
| UC-CONN-03 | 切换节点（热切换） | 节点列表点选 | 连接保持、路由重建、当前节点快照更新 |
| UC-PROF-01 | 从剪贴板/URI 导入节点 | 扫码、`sn://` 深链、粘贴 | 新增 profile、分组归属、去重提示 |
| UC-SUB-01 | 添加订阅 | 订阅页新增 | 首次拉取、生成节点组、`lastUpdated` 落地 |
| UC-SUB-02 | 手动更新订阅 | 下拉刷新 | 节点增删改、失败回退与退避计数 |
| UC-SUB-03 | 定时更新 | WorkManager 到期 | 仅更新 `autoUpdate` 且到期的组 |
| UC-ROUTE-01 | 修改分流规则 | 路由页 | 规则持久化、连接中即时生效 |
| UC-APP-01 | 分应用代理 | 应用列表 | 选择集持久化、规则灰度 |
| UC-DATA-01 | 导出配置 | 备份页 | 生成配置并分享，无残留临时文件 |
| UC-DATA-02 | 导入备份 | 备份页 | 校验格式、事务写入、失败不改动现有数据 |
| UC-STAT-01 | 查看流量曲线 | 流量页 | 查询窗口、聚合、单位换算 |
| UC-ROOT-01 | 安装/更新 root 模块 | 工具页 | 模块落盘、校验、重启服务 |

（yaml 中 28 条含上述 13 条及：删除节点、克隆节点、分组排序、订阅删除、规则排序、全局代理模式切换、DNS 设置变更、日志导出、扫码、数据库损坏恢复、权限申请、小组件点击、磁贴点击、开机自启。）

---

## 五、重建顺序（映射到真实文件）

**S0 冻结（1 周）**：把上面的用例逐条跑一遍真机，记录实际行为差异，回填 yaml 的 `observed` 字段。产出：`USE-CASES.yaml` 定稿 = 验收基线。此阶段**不改一行生产代码**。

**S1 抽核（2 周）—— 已落地（2026-10-27）**

五个模块真实建起，不是骨架：`:core:contracts`（`Contracts.kt` + `dto/`）、`:core:domain`（`AppCore` 状态机 + 六组用例文件 + `LinkCodec`）、`:adapters:memory`、`:cli`、`:tests`。

- 28 条用例全部实现并各自有断言；91 个 JUnit5 测试全绿（`bash arch/core-proof/build.sh` 一次跑完）
- CLI 作为第二界面跑 40+ 项断言，覆盖导入/连接/热切换/订阅/路由/分应用/导出导入/脱敏/统计/迁移/权限/自启/root 白名单
- `arch-check.sh`：core 零平台引用、模块方向合规、无环、28 条用例 ID 全部可追溯
- 旧代码不删，`:app` 一行未改；`DataStore` 仍是生产侧唯一开关源
- `fmt/` 的处理方式改成「按规则重实现」而不是整包平移：`LinkCodec` 覆盖 ss（SIP002 与旧式 base64）、vmess、trojan、vless、socks/socks4/socks4a、http/https 的 parse 与 toUri，用 JDK 自带 Base64 + 手写 MiniJson，维持 core 零第三方依赖

**S1 明确没做的两件事**

- `ConfigBuilder.kt`（2133 行）直接依赖 `DataStore` / `SagerDatabase` / `Toast` / `SingBoxOptions`，必须连 Android 构建一起搬；现在搬过去就是不可编译代码，违反「每一步都要能编译」
- `fmt/` 里的 `buildSingBox*` 依赖 okhttp3 / gson / org.json，属于适配层职责，留给 `:adapters:android`

**S2 接线（2 周）**：`ProfileRepository`、`ConnectionController`、`SubscriptionGateway` 三个接口先落地（其余后置）。适配层实现直接包现有 `ProfileManager`/`SagerNet`/`GroupUpdater`，UI 改成经接口调用——这一步只换调用方式，不改行为。

**S3 换屏（按风险从低到高，每屏一次提交）**：
1. `SettingsFragment`（纯表单，无连接态）
2. `GroupFragment`（订阅管理，只读列表 + 更新动作）
3. `RouteFragment`（规则表单）
4. `ConfigurationFragment`（节点列表，2088 行，最重）
5. `DashboardFragment`（连接开关 + 状态灯，紧耦合 `serviceState`）
6. `widget/` 与 `TileService`（同样经 `PlatformPorts` 订阅快照）

每换一屏：新绑定层接入 → 用例通过 → 删旧代码 → 编译绿。

**S4 断脐（1 周）**：删除 `object DataStore` 的全局直读、`SagerNet.startService()` 的静态入口、`ProfileManager` 的 UI 可见 API；把 `:app` 缩到只留 Application/DI/导航壳。

**S5 验收**：跑第六节五条，全绿才算完成。

---

## 六、验收（可执行命令）

当前可直接跑的一条（无需 Gradle / Android SDK）：

```bash
bash arch/core-proof/build.sh
```

它依次做：编译 5 个模块 → 编译测试 → 跑 91 个用例 → 跑 CLI（第二界面）→ 跑架构守卫。

接上 Android 构建后，追加：

```bash
# 1) 删掉 ui 后 core 仍全绿
./gradlew :ui:clean; ./gradlew :tests:test :cli:run

# 2) core 依赖里没有任何 Android / UI 框架
./gradlew :core:domain:dependencies --configuration runtimeClasspath | grep -Ei "android|compose|appcompat" \
  && { echo "FAIL: core 依赖了平台/界面库"; exit 1; } || echo "PASS"

# 3) 无循环依赖 + 依赖方向 + 用例可追溯
bash arch/wanbox/arch-check.sh arch/core-proof

# 4) 新增界面不改 core：`git diff --stat HEAD~1 -- 'arch/core-proof/core/**'` 为空

# 5) 同一份 core 接第二种界面
./gradlew :cli:run
```

`arch-check.sh` 现在做六件事：`core` 禁止 `import android/androidx/io.nekohasekai.*`；`core` 禁止出现 `Context/Uri/Bitmap/Parcelable` 类型；`adapters/cli/tests` 禁止平台与历史包；按允许边清单校验模块依赖方向（`:core:contracts` 无出边、`:core:domain → :core:contracts`、`:adapters:* → :core:contracts`、`:cli/:tests → domain + adapters`）；拓扑检测环；`USE-CASES.yaml` 每条 ID 是否都在代码里可追溯。

---

## 七、这四条禁令在本项目里的具体含义

| 通用禁令 | 落到本项目的判定 |
| --- | --- |
| 不为复用抽万能工具类 | `ktx/`、`utils/` 现有 41 个文件里，只有 `Logs`、`formatBytes` 这类纯函数允许进 core；`AppHttpClient`、`PackageCache`、`DefaultNetworkListener` 属于适配层，不许上提 |
| 界面里不写业务分支 | `ConfigurationFragment` 里既有点击态又有「该节点是否可连接/是否在链中」的判断，要搬进 domain 的 `ProfileUsability` |
| 不用全局单例做跨模块通信 | 现存的 `object DataStore`、`object ProfileManager`、`object DefaultNetworkListener`、`object SubscriptionUpdater` 全部要变成注入实例；`SagerNet.application` 仅允许在 `:app` 与 `:adapters:android` 内出现 |
| 重建期间不加新功能 | S0–S4 期间 `CHANGELOG.md` 只允许出现「重构」条目；PR 模板增加一项「本 PR 是否新增行为」 |

---

## 八、风险与前置确认

1. **进程边界**：`BaseService` 跑在 `:bg` 进程，`RemoteWorkerService` 跑订阅任务，`RootTunService` 走 root。`ConnectionController` 的状态流要跨进程——建议 `contracts` 里定 `ConnectionState`，适配层用 AIDL 现有通道 + `StateFlow` 桥接，不要新造 Binder。
2. **状态迁移的兼容**：`DataStore` 的 key 已被 `SharedPreferences`/Room 双写支持，改 `SettingsStore` 必须保留 key 语义，否则老用户升级丢配置。`UC-DATA-04` 已按「只合并旧版本真正写过的键」实现——实测过：如果整份快照替换，`selectedProfileId` 会被默认值 null 覆盖，等于升级后选中节点丢失。
3. **libcore 回调**：流量、连接态由 Go 侧回调进来，属于适配层入口，不能直接进 UI。
4. **工时**：按现状 360 文件、单模块，S0–S4 保守估计 6–8 周（单人）；`ConfigurationFragment` 单屏约 1 周。S1 的「core 可跑」已经拿到（不是预估），剩余时间主要在 `:adapters:android`。
5. **本规范的边界**：它约束的是依赖方向与状态归属，不承诺性能收益；`largeHeap` 去除、协程治理等已在 `v3.0.10-preview.23` 单独处理，不要混进这次重构。

---

## 九、与规范的偏差登记

规范说「多出来的每一层都要说明它挡住的是什么」，以下四处是刻意偏离，逐条说明代价与理由，不藏。

| 偏差 | 实际做法 | 理由与代价 |
| --- | --- | --- |
| 「每个模块只暴露一个接口文件」 | `:core:contracts` 做到了（`Contracts.kt` + `dto/Dtos.kt`）；`:core:domain` 的用例是 `AppCore` 的扩展函数，分布在同一包的 6 个文件里 | 28 条用例塞进单一文件会到 800+ 行，且按主题分文件更利于 review。对外接收者仍只有一个类型 `AppCore`；代价是 IDE 里看不到「一个文件看全 API」，只能靠 `USE-CASES.yaml` 的 `impl_core` 索引 |
| 「只允许 core / adapters / ui 三层」 | 多出 `:adapters:memory`、`:cli`、`:tests` 三个 Gradle 模块 | `:adapters:memory` 属 adapters 层的第二个实现（不是新层），它挡住「测试替身流进生产包」；`:cli` 是第二个界面，属验收工具；`:tests` 只依赖 core+adapters。三者都已登记进 `arch-check.sh` 的允许边清单 |
| `core` 拆成 `contracts` 与 `domain` 两个模块 | 保持 | 挡住 domain 反向依赖适配层，依赖倒置才成立（见第一节） |
| `fmt/` 不整包平移 | 按规则重实现为 `LinkCodec` | 原文件依赖 okhttp3/gson/org.json/SingBoxOptions，整包搬会破坏 core 零依赖；代价是与上游 `parse*` 的边界行为未逐协议对拍，已在 README「它没有证明什么」里标注 |
