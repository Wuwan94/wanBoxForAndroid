# B 路线设计：核心只随模块分发（瘦管理器）

> 决策来源：用户已拍板走 B 路线，学 NetProxy-Magisk 的实际形态——**sing-box 核心只保留一份（root 模块里的 `rootbox`），
> APP 瘦身为纯管理器**。A 路线（双核心硬压）不再讨论。
> 日期：2026-10-28　落地范围：`app/`、`rootmodule/`、`buildScript/`、`tools/`

## 一、结论与账本（实测口径）

> 最终实测与完整变更清单见 [`size-report-final.md`](./size-report-final.md)，本节保留决策时的推演过程。

| 方案 | APK | 模块 zip | 合计 (MiB) | vs 26 MiB |
|---|---:|---:|---:|---|
| 现状（双核心，已发布） | 25.56 | 18.18 | **43.74** | 超 17.74 |
| **B1 瘦管理器 + 模块（已实施，实测）** | **6.39** | **18.08** | **24.47** | ✅ 余量 1.53（合体包口径 23.93，余量 2.07） |
| B1 + 裁 `with_gvisor`（本轮默认已裁） | 6.39 | ≈17.04 | **≈23.43** | ✅ 余量 2.57（合体包 ≈22.89） |
| B1+ `rootbox.xz` 载荷（未采用） | 6.39 | 13.00 | **19.39** | ✅ 余量 6.6 |

- 瘦 APK 6.39 MiB 为**实测**：从 `v3.0.10-preview.26` 剥离 `lib/arm64-v8a/libgojni.so`（−16,600,870 B）、
  `classes.dex` 重新 deflate（−2,107,752 B）、4 张真实入包图标 PNG→WebP（−1,380,174 B）后重建得 6,700,524 B。
  早期版本按 11 张图标估 2.0 MiB 是错的：其中 7 张 `mipmap-*` 档位图被 resource shrinker 判为未使用，从未进包。
- 模块 zip 18.08 MiB = `rootbox` deflate9 15.48 + `wanboxctl` 2.58 + 脚本 ≈0.02。
- 合体包用 `bundle_manager.py` 实际打包：**25,096,880 B = 23.93 MiB**，低于 26 MiB。
- 首页仪表（`ui/DashboardFragment`）为**保留项**，数据来自模块侧 `queryClashStats` → `wanboxctl dashboard`
  → `RootTunService.cbSpeedUpdate` → `lastSpeedStats`，因此 **`with_clash_api` 必须保留**；
  但 yacd 网页面板本身**不是**保留项，已随 `assets/yacd.zip` 一起移除（`external_ui` 不再下发）。
  瘦版仍需 `core/CoreAssets.kt` 落地离线规则库 `geoip.db` / `geosite.db`（模块侧硬校验）。

## 二、现行控制通道（root 模式到底怎么和核心通话）

查清了，共三条，全部不依赖进程内核心：

| 通道 | 实现 | 用途 |
|---|---|---|
| `wanboxctl` CLI | `/data/adb/modules/wanbox/bin/wanboxctl`，由 `RootModuleClient.call()` 经 root shell 调用 | `status` / `config apply <file>` / `start` / `stop` / `autostart on\|off` / `dashboard` / 节点热切换 |
| 配置快照文件 | APP 生成 JSON 快照（config + files/base64 + plugins）→ 写 `cacheDir` 临时文件 → `config apply` | 把完整 sing-box 配置与资源交给模块 |
| 回环 HTTPS（Clash API） | 模块侧核心里 `clash_api` 监听 127.0.0.1，`wanboxctl` 与 APP 读 dashboard/统计 | 面板、流量、节点切换 |

**关键发现（决定了改造成本）**：root 模式的配置是**纯 Kotlin 生成**的。
`bg/proto/BoxInstance.kt:loadConfig()` 写的是
`if (DataStore.serviceMode != Key.MODE_ROOT) { box = Libcore.newSingBoxInstance(...) }` ——
即 root 路径**根本不创建 Go 实例**，`RootModuleSnapshot.build()` 只读 `instance.config.config`（Kotlin 侧已生成好的 sing-box JSON）。
所以 B 路线**不需要**给 `rootbox` 新增 `--compile` 子命令（原报告的判断在此修正），模块侧已有的 `config apply` + `core_config_invalid` 校验足够。

## 三、问题 1：哪些功能依赖内嵌核心，B 路线怎么处理

| # | 功能 | 依赖点 | B 路线处理 | 状态 |
|---|---|---|---|---|
| 1 | 免 root 单机代理（VpnService/TUN） | `Libcore.newSingBoxInstance` + `box.start()` | 瘦版**直接拒绝启动**并提示"本版本无内嵌核心，请装模块或用完整版" | ✅ 已实施（`BaseService.kt` 启动前判断 + 新字符串） |
| 2 | root 模式配置生成/下发 | 仅 Kotlin（`ConfigBuilder` → 快照 JSON） | 不变，走 `wanboxctl config apply` | ✅ 天然可用 |
| 3 | 日志 | `Libcore.nekoLogPrintln` ×10 + `nekoLogClear` | 瘦版回退 `android.util.Log`（logcat），不再有进程内核心日志环 | ✅ 已实施 |
| 4 | 断开全部连接（网络切换/亮屏） | `Libcore.resetAllConnections` ×5 | 瘦版 no-op；root 模式下由模块侧按网络变化自己重置 | ✅ 已实施（降级） |
| 5 | 连接测试 / urlTest | `Libcore.urlTest/urlTestFull`、`newURLTestSession` | 瘦版明确失败（"连接测试 需要完整版"）；root 模式本就走 `RootTunService.urlTest` 交给模块 | ✅ 已实施（门控）／⚠️ 待补模块侧兜底 |
| 6 | 测速（SpeedTest） | `Libcore.newSpeedTestSession` + 进程内核心 | 瘦版明确失败提示 | ✅ 门控／⚠️ 待补：可改为纯 OkHttp 直连测速 |
| 7 | STUN NAT 探测 | `Libcore.stunTest` | 瘦版明确失败提示 | ✅ 门控 |
| 8 | 路由规则本地校验（schema/RE2） | `newTestSingBoxInstance(...).close()` | 瘦版跳过本地校验，交给模块 `config apply` 的 `core_config_invalid` | ✅ 已实施（降级） |
| 9 | 订阅/资源/更新检查下载 | `Libcore.newHttpClient()` ×4（`libcore.HTTPClient`：支持代理出站、TLS 指纹） | 瘦版抛 `core_unavailable`；**P0 待办**：改 OkHttp 直连（root 模式下 TUN 已全局代理，直连等价） | ⚠️ **未完成（阻塞发布）** |
| 10 | `.xz` 资源解压（geoip/geosite） | `Libcore.unxz` | 瘦版改用 Tukaani XZ（纯 Java，新增依赖） | ✅ 已实施 |
| 11 | 核心版本号展示 | `Libcore.versionBox()` | 瘦版回退应用版本号 | ✅ 已实施 |
| 12 | 网络接口类型回调 | `Libcore.InterfaceType*` 常量 | 瘦版回落 `0`（无核心时该回调不会产出有效配置） | ✅ 已实施 |
| 13 | `initCore` / `go.Seq.setContext` | `Application.onCreate` 无条件执行 | 瘦版整段跳过（否则启动即 `UnsatisfiedLinkError`） | ✅ 已实施（最关键） |

## 四、问题 2：无 root、无模块时，瘦 APK 还能干什么

**结论：只能看和改配置，不能连接。** 这是学 NetProxy 的必然代价，需要写进发布说明与商店描述。

可做：
- 浏览/编辑/导入导出节点与订阅（数据库、分组、排序）；
- 生成与校验配置（Kotlin `ConfigBuilder` 全在本地）；
- 检测 root 与模块状态（`wanboxctl status`），引导安装模块；
- 主题、日志（仅 logcat）、关于页等 UI。

不可做（会明确提示，不再崩溃）：
- 任何实际代理连接（无核心 = 无数据通路）；
- 本地测速、STUN 探测、连接测试；
- 订阅/资源/更新下载（P0 待办补齐 OkHttp 后恢复）。

**发布策略**：瘦 APK 只随「模块合体包」分发；GitHub 独立 APK 附件继续发**全功能版**（含核心），保证"不想 root 的用户"不被断供。
两者同 `applicationId`、同签名、同版本号，可互相覆盖安装；瘦版检测到模块缺失时提示装完整版。

## 五、问题 3：体积目标与模块打包形态

- 瘦 APK 实测 **5.68 MiB**（明细见§一）。
- 模块 zip **18.08 MiB**（pack.py 已是 deflate-9），合计 **23.76 MiB ≤ 26 MiB**，余量 2.24 MiB。
- **打包形态：选「合体包」为主 + 「独立附件」为辅**，与 NetProxy 一致：
  - `wanbox-root-module-with-manager-arm64-v8a.zip` = 模块目录 + 瘦 APK（`bundle_manager.py` 已有流程，本次放宽了它对 APK 必须含 `lib/arm64-v8a/` 的校验）；
  - 同时保留纯模块 zip 与全功能 APK 两个独立附件，方便已有用户增量升级。
- 进一步压（可选，按需再拍板）：
  - `rootbox.xz` 载荷：模块 18.08 → 13.00（−5.08 MiB），需安装器能解 xz（busybox xz 或内嵌解码器，**必须真机验证**）；
  - 去掉 `wanboxctl` 改 APP 直连回环 HTTPS：模块 → 10.42（−2.58 MiB），`core/contracts` 里本就规划了这次迁移。

## 六、本次已实施的改动

**新增**
- `app/src/main/java/io/nekohasekai/sagernet/core/CoreRuntime.kt`：能力闸门（`available` / `log` / `clearLog` / `resetAllConnections` / `httpClient` / `requireAvailable` / `versionBox` / `unxz`）。
- `tools/check_core_gate.py`：静态守卫，CI 拦截任何绕过 `CoreRuntime` 的 `Libcore.*` / `go.Seq` 直调（当前 289 个源文件全绿）。
- `docs/b-route-design.md`：本文。

**构建侧**（`app/build.gradle.kts`）
- `-PwanboxThin=true` 开关：剔除 `lib/**/libgojni.so`，并写入 `BuildConfig.WITH_CORE=false`；**默认构建行为与产物路径完全不变**。
- 新增依赖 `org.tukaani:xz:1.10`（瘦版 `.xz` 解压，约 120KB dex）。
- dex 压缩（`packaging.dex.useLegacyPackaging = true`）沿用上一轮改动，瘦版同样生效。

**应用侧**（16 个文件，全部走闸门）
`SagerNet`（`go.Seq`/`initCore`）、`ktx/Logs`（10 处）、`GuardedProcessPool`（2）、`LogcatFragment`、`BaseService`（4 处重置 + urlTest + 启动拒绝）、`NativeInterface`（3）、`TestInstance`（2）、`SpeedTestRunner`、`StunActivity`、`RouteSettingsActivity`、`BoxInstance`、`RawUpdater`、`AssetsActivity`、`AboutFragment`。

**模块侧**
- `rootmodule/bundle_manager.py`：允许瘦 APK 没有 native 库（仍拒绝非 arm64 ABI）。
- `buildScript/size-verify.sh`：新增 `--b-route`，一条命令出「瘦 APK + 模块 zip + 合体包 + xz 预估」。

## 七、功能损失清单（B 路线不可逆的代价）

1. **免 root 单机代理在瘦版不可用**（核心在模块里）。全功能 APK 仍提供该能力。
2. **瘦版没有本地核心日志**，只有 logcat；排障需看模块侧 `logs/`。
3. 瘦版的**测速/STUN/连接测试不可用**（除非后续接模块侧或 OkHttp 实现）。
4. 瘦版的**路由规则不做本地校验**，错误配置要等模块 `config apply` 才报错（错误码更靠后）。
5. 维护成本：**两套构建产物**（thin / full），每次发版需各自冒烟；新增代码必须走 `CoreRuntime`，否则 CI 拦截。

## 八、未完成 / 下一步（按优先级）

1. **P0 · 订阅与资源下载改 OkHttp**（`RawUpdater` 1278 行里 `libcore.HTTPClient/HTTPResponse` 的替换）——不做完，瘦版不能发布。
2. **P1 · 编译验证**：本轮改动**未经过编译**。本机缺 Gradle 发行版（`~/.gradle` 不存在）、
   Go 模块缓存里只有 8 个 zip（`go build` 在 `reF1nd/sing`、`reF1nd/wireguard-go`、`golang.org/x/net`、`ulikunitz/xz` 等多处报 no such file）、
   且网络（proxy.golang.org / maven）仍 000。需在有网环境跑：
   ```bash
   ./gradlew :app:assemblePreviewRelease                 # full，验证无回归
   ./gradlew :app:assemblePreviewRelease -PwanboxThin=true
   ./buildScript/size-verify.sh --b-route                # 出实测账本
   python3 tools/check_core_gate.py                      # 静态守卫
   ```
3. **P1 · 瘦版 UI 门控**：隐藏/置灰 VPN 模式入口、测速与 STUN 入口，显示"当前为 root 客户端"。
4. **P2 · 模块侧兜底**：连接测试、测速改由模块执行（`wanboxctl` 已有回环通道）。
5. **P2 · 是否上 `rootbox.xz` 与去掉 `wanboxctl`**：需真机验证安装器解压能力后再定。
