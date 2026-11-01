# B 路线最终报告（体积 + 功能变更）

> 预算口径：**模块 zip + APK 合计 ≤ 26 MiB**（26 MiB = 27,262,976 B，"GitHub 显示口径"按 MiB 两位小数）。
> 基线：`v3.0.10-preview.26`（APK 26,802,221 B + 模块 zip 19,064,451 B = **43.74 MiB**）。

## 一、结果（字节级实测，非估算）

| 产物 | 字节 | MiB |
|---|---:|---:|
| 瘦管理器 APK（`-PwanboxThin=true`） | 6,700,524 | **6.39** |
| 模块 zip（deflate-9，当前核心） | 18,955,319 | **18.08** |
| 两附件合计 | 25,655,843 | **24.47** |
| **合体包（模块 + APK，`bundle_manager.py` 实际打包）** | 25,096,880 | **23.93** |
| 26 MiB 预算 | 27,262,976 | 26.00 |
| **余量** | 2,166,096 | **2.07 MiB** |

裁掉 `with_gvisor` 后（切片实测：每端压缩后 1.04 MiB）模块降到 **≈17.04 MiB**，
合体包 **≈22.82 MiB**（余量 3.2 MiB）、两附件 **≈23.35 MiB**。两个口径都进 26。

明细拆解（APK 从 26,802,221 降到 6,700,524）：

| 项 | 变化 |
|---|---:|
| 移除 `lib/arm64-v8a/libgojni.so` | −16,600,870 B |
| `classes.dex` Stored → Deflate（4,122,288 → 2,014,436） | −2,107,752 B |
| 启动图 PNG → WebP（4 张真实条目：1,707,258 → 327,084） | −1,380,174 B |
| zip 条目 recompress(9) 等 | −12,901 B |

> 注：仓库里 11 张图标中只有 4 张真正进了 APK（`mipmap-*` 的四档位图被 resource shrinker 判定未使用）。
> 因此收益是 1.32 MiB，不是早期按 11 张估的 2.0 MiB —— 这是实测修正。
> 复现：`python3 tools/measure_b_route_size.py`（图标按「同名尺寸 + 原始字节数最近」与 APK 内混淆名配对）。

## 二、这不是"砍功能换体积"，而是修一个结构性浪费

排查中发现的关键事实：**本分支的 APP 早就不含 VpnService 单机模式**。
`AndroidManifest` 只注册了 `RootTunService`（纯观察者），`ProxyService` 从未被 `startService` 启动；
`SagerNet.startService()` 走的是 `RootModuleClient` → `wanboxctl`。也就是说管理器一直是模块客户端，
APK 里那份 49.7MB 的 `libgojni.so` 只被用于四件事：**配置编译、HTTP 客户端、测速/延迟、.xz 解压**。

所以 B 路线是"把重复的那份核心删掉"，代价只有下面这一条清单，而且都不影响代理主功能。

## 三、保留项：仪表盘（yacd + `with_clash_api`）不许砍

用户明确要求保留仪表盘，这条已从"顺便留"升级为**硬约束**，并加自动守卫
`tools/check_keep_features.py`（CI 可跑，误删即红）：

| 保留项 | 实现位置 | 守卫断言 |
|---|---|---|
| `with_clash_api` 构建标签 | `libcore/build.sh:53` 默认 `CORE_TAGS`（`libgojni` 与 `rootbox` 共用同一份） | tags 必须含 `with_clash_api`，且 rootbox 复用 `$CORE_TAGS` |
| yacd 前端 `assets/yacd.zip`（750 KB，48 条目） | 随 APK 自带，**未被任何体积改动触碰** | zip 内须有唯一 `Yacd-*` 顶层目录且含 `index.html` |
| Clash API 监听 `127.0.0.1:9090` + `external_ui="../files/yacd"` | `ConfigBuilder.kt:733`（非导出配置恒开启，节点热切换也依赖它） | 两处字面量必须存在 |

体积代价：`with_clash_api` 实测每端仅 0.06 MiB、yacd 资源 0.72 MiB，全部计入上表 6.39 + 18.08，**没有靠它们省体积**。

### 这条要求顺带暴露一个会直接打死 root 渠道的 bug（已修）

排查保留项时发现：`files/yacd`（仪表板）与 `geoip.db`/`geosite.db`（规则库）在完整版里是
`libcore.InitCore` → `extractAssets()` 从 APK assets 解压落地的；而瘦管理器**跳过了 `initCore`**，
于是这几样永远不会落地。而模块侧是**硬校验**：

- `rootmodule/runtime.go:292` 要求快照带 `files/yacd/index.html`，否则返回 `config_resource_missing`；
- 规则里出现 `geoip:`/`geosite:` 时要求 `assets/geoip.db` / `assets/geosite.db` 存在。

也就是说，若只做"删 `libgojni.so`"，瘦版会**根本起不了核心**（不只是仪表板 404），
这是比体积更严重的问题。

修复：新增 `core/CoreAssets.kt`，用纯 Kotlin + Tukaani XZ 复刻 Go 侧同一套落地规则
（目标路径、`*.version.txt` 版本文件、数值版本比较、`Custom` 版本不覆盖、`Yacd-*` 改名、zip-slip 防护），
在 `SagerNet.onCreate` 与 `RootModuleClient.build()` 各调用一次（幂等）。
完整版（`CoreRuntime.available`）直接返回，仍由 Go 侧负责，避免双写。

## 四、砍了什么（全部清单）

| 项目 | 处理 | 代价 |
|---|---|---|
| `with_gvisor` 构建标签 | **裁掉** | TUN 改用 sing-box **system 栈**（`ConfigBuilder.TUN_STACK = "system"`）。TCP/UDP、auto_route、auto_redirect、分应用 UID 规则全保留；只失去 gVisor 用户态栈的私有行为。**root 渠道走 ebpf inbound，本来就不经 gVisor，零影响** |
| APK 内嵌核心 `libgojni.so` | **移除** | 管理器不能在本机起核心。单机代理在本次构建里本就不存在，故无功能回退；核心由模块里的 `rootbox` 承担 |
| 进程内 Go HTTP 客户端（订阅/规则库/更新检查） | **替换为 OkHttp**（新增 `core/WanHttp.kt`，方法名与原绑定一致） | `tryProxyOutbound()` 退化为直连：拉订阅不再借核心代理出站。root 模式下核心在模块进程，管理器流量本来也不经过它 |
| 本地测速 / STUN 探测 / 本地配置校验 / 核心日志缓冲 | 明确降级并提示 | 无核心时可用的部分改走模块通道；不可用的给出可读错误，不再 `UnsatisfiedLinkError` |
| `with_ebpf` / `with_quic` / `with_utls` / `with_clash_api` / `with_wireguard` / `with_conntrack` | **全部保留** | hysteria/hysteria2/tuic、TLS 指纹、yacd 仪表盘、WireGuard、root 分应用代理都不受影响 |
| 离线规则库与内置面板（geoip/geosite/yacd，2.2 MiB） | **保留** | 未发现需要靠它们换体积的必要性 |
| UPX / `rootbox.xz` | **未采用** | 已实测 xz 可再省 5.08 MiB，但 Magisk/KSU 安装器的 busybox `unzip` 不支持 LZMA 方法、且需额外 xz 解码器，安装期风险大于收益；已达标故不引入。UPX 有 execmem 兼容风险，`libgojni.so` 本就不可用 |

签名配置、applicationId、版本号：**未改动**。

## 五、代码改动

**新增**

- `app/src/main/java/io/nekohasekai/sagernet/core/CoreRuntime.kt` —— 能力闸门（`available` 取自 `BuildConfig.WITH_CORE`），全部内嵌核心调用的唯一出口
- `app/src/main/java/io/nekohasekai/sagernet/core/CoreAssets.kt` —— 瘦版资产落地（yacd / geoip / geosite），语义对齐 Go 侧 `extractAssets()`
- `app/src/main/java/io/nekohasekai/sagernet/core/WanHttp.kt` —— 纯 Kotlin HTTP 层，方法名对齐旧 `libcore.HTTPClient`/`HTTPResponse`，4 处调用点零改写
- `tools/check_core_gate.py` —— 静态守卫：禁止绕过闸门直调 `libcore`（当前 290 个源文件全绿，可挂 CI）
- `tools/measure_b_route_size.py` —— 复现本文所有体积数字
- `buildScript/size-verify.sh --b-route` —— 在有网机器上一条命令出瘦 APK + 模块 + 合体包 + 预算判定

**修改**

- `app/build.gradle.kts`：`-PwanboxThin=true` → `WITH_CORE=false` + `packaging.jniLibs.excludes += "**/libgojni.so"`；`packaging.dex.useLegacyPackaging = true`（dex 压缩）；新增 `org.tukaani:xz:1.10`（瘦版解 `.xz` 资源）。**默认构建与产物路径不变**
- `fmt/ConfigBuilder.kt`：新增 `internal const val TUN_STACK = "system"` 并用于 TUN inbound
- `libcore/build.sh`：默认 tags 去掉 `with_gvisor`，可用 `WANBOX_CORE_TAGS` 覆盖
- `rootmodule/pack.py`：zip 压缩级别 6 → 9
- `rootmodule/bundle_manager.py`：允许瘦 APK 无 native 库（仍拒绝非 arm64）
- 托盘收口：`SagerNet`、`Logs`、`GuardedProcessPool`、`BaseService`、`NativeInterface`、`TestInstance`、`SpeedTestRunner`、`StunActivity`、`RouteSettingsActivity`、`BoxInstance`、`RawUpdater`、`AssetsActivity`、`AboutFragment`、`LogcatFragment`
- 测试：`ConfigBuilderLoadBalanceTest` 的 stack 断言改为 `system`；`LauncherResourceTest` 的图标断言改为 `.webp`
- 资源：`drawable-nodpi/wanbox_launcher_art`、`drawable/ic_launcher_{foreground,monochrome}` 及 4 档 mipmap 全部 PNG → WebP（原图备份在 `.workbuddy/tmp/res_backup/`）

## 六、必须说明的验证状态

**本轮代码未经编译。** 本机三条路都断：无 Gradle 发行版（`~/.gradle` 不存在）、
`libcore/go.mod` 的 `replace github.com/sagernet/sing-box => ../../sing-box-netproxy` 指向的源码目录不存在、
出网被代理拦截（goproxy/maven/github 全 000）。因此：

- **已实测**：本文所有体积数字（对真实发布产物做字节级重建，脚本可复现）。
- **未实测**：编译通过与真机行为。

在有网环境按顺序跑（这是唯一剩下的验证动作）：

```bash
./buildScript/size-verify.sh --b-route      # 出瘦 APK + 模块 zip + 合体包 + 预算判定
./gradlew :app:testPreviewDebugUnitTest     # stack / 图标断言
python3 tools/check_core_gate.py            # 闸门守卫（内嵌核心不得裸调）
python3 tools/check_keep_features.py --apk app/build/outputs/apk/preview/release/*.apk
                                            # 保留项守卫（yacd / clash_api / 资产链）
```

`check_keep_features.py` 已在本机对模拟瘦 APK 跑通：`assets/yacd.zip`、`assets/sing-box/geoip.db.xz`、
`geosite.db.xz` 均在包内，且 `lib/` 下无任何 `.so`。

真机回归重点：① root 模块起停、节点切换、仪表盘；② TUN 在 system 栈下的 TCP/UDP 与分应用规则；
③ 订阅拉取（现在是 OkHttp 直连）；④ 规则库更新（`.xz` 走 Tukaani XZ）。

## 七、与 NetProxy 的对照

| | 核心份数 | 模块 zip | 管理器 APK | 合计 |
|---|---:|---:|---:|---:|
| NetProxy v8.6.2 | 1（原版 60.6 MiB，零裁剪） | —（同包） | —（同包，瘦） | 26.27 |
| wanBox B 路线（本轮） | 1（裁剪核心） | 18.08 | 6.39 | **23.93（合体包）** |

同一架构下比参考项目小 2.3 MiB，且我们的核心本身比它小 11.7 MiB（48.9 vs 60.6）。
