# Husi GHSA-q6pm-rh93-9hr5 同类问题排查（wanBox）

作者：雾晚

> 基线：wanBox `origin/main` `303aa996567969ce0801ce5c7c30740f5404ae31`；内核固定为 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd`（tag 提交 `17397d022647b73378705e1f91ff031dddaaec21`）。
> 参考：[GHSA-q6pm-rh93-9hr5](https://github.com/xchacha20-poly1305/husi/security/advisories/GHSA-q6pm-rh93-9hr5)（Husi Desktop 2.4.1 会从不可信备份恢复「自定义插件」节点，启动时执行备份指定的程序；修复提交 `dfba1c44` 在备份导入/导出时跳过自定义插件节点），以及 [Husi v2.4.3 Release](https://github.com/xchacha20-poly1305/husi/releases/tag/v2.4.3)。

## 结论

三个面都**不可达**：wanBox 没有「由数据指定可执行文件或参数」的插件类型。外部程序只有四种固定 ID 的插件，可执行文件只从已安装的插件提供方或 App 自带的 `.so` 解析，参数是代码里写死的常量加上 App 自己生成的临时文件路径。备份、订阅、分享链接只能改节点字段，这些字段经 `JSONObject` 序列化写进插件配置文件，进不了 argv，也进不了 shell 命令串。

本次没有改代码。

## 执行面清单（所有 `ProcessBuilder` / `exec` 调用点）

| 调用点 | 可执行文件 | 参数来源 | 导入数据能否影响 |
|---|---|---|---|
| `bg/proto/BoxInstance.kt` `launchExternal()`（L128 / L148 / L178 / L194） | `PluginManager.init("trojan-go-plugin" / "mieru-plugin" / "naive-plugin" / "hysteria-plugin")`：ID 写死在 `init()` L72–L87 | 常量参数加 `cacheDir/tmpcfg/<类型>_<elapsedRealtime>.json`；Hysteria 的 `--log-level` 由 `DataStore.logLevel` 经 `when` 映射到固定集合 | 否：只能改配置文件**内容**，内容由 `JSONObject` 构建（`buildTrojanGoConfig` / `buildNaiveConfig` / `buildHysteria1Config` / `buildMieruConfig`） |
| `plugin/PluginManager.kt` `init()` L27 | `initNativeInternal` 只认 `libhysteria.so` / `libhysteria2.so`（App 自身 `nativeLibraryDir`）；`initNativeFaster` 取已安装提供方 APK 的 `nativeLibraryDir` 加 meta-data 相对路径，并检查 `canExecute()` | — | 否：信任根是设备上已安装的 APK，与 Husi 修复后的 Android 模型一致（advisory 原文：「Android resolves plugins through installed providers」） |
| `bg/RootModuleClient.kt` `commandProcess`（`su -c "exec $CLI $command"`） | 固定 `wanboxctl` | `call()` L223–L226 的白名单：固定命令集，或正则 `node select [1-9][0-9]{0,18}`、`data selection-finish [a-f0-9]{32}` 等；`autostart on/off` 来自布尔值 | 否：任何拼接值都先过正则 |
| `RootModuleClient.kt` `editorSnapshotProcess` | `su -c test/cat` | `revision` 必须匹配 `[a-f0-9]{64}` | 否 |
| Root 快照插件：`RootModuleClient.kt` L940–L965 → `rootmodule/runtime.go` L326–L344 → `rootmodule/supervisor.go` L324–L348 | 二进制字节取自 `instance.pluginPath["$kind-plugin"]`（同上 PluginManager）；模块侧只接受 `kind ∈ {trojan-go, mieru, naive, hysteria}`，并且要求 `plugins/` 前缀和 ELF64 AArch64 头 | 模块侧按 `kind` 写死参数 | 否：备份里没有任何字段能指定二进制；`NekoBean` 等其他外部类型在 L948 直接抛 `external_plugin_unsupported` |
| `bg/SharedInterception.kt` `preflight()` L182（`su -c "... shared-preflight $dataPlane"`） | 固定 `wanboxctl` | `dataPlane` 来自 `parse()`，`validate()` 只允许 `socket_assign` 和 `packet_rewrite`；校验失败时调用方 `getOrDefault(defaults())` | 否 |
| `utils/PackageCache.kt` `su("pm list packages --user $u -U")` | `pm` | `$u` 是从 `pm list users` 输出里用正则解析出的 `Int` | 否 |
| `ui/LogcatFragment.kt`、`utils/CrashHandler.kt` | `logcat -c`、`getprop` | 常量 | 否 |
| `rootmodule/*.go` 其余 `exec.Command` | `getprop`、`settings`、`cmd package`、`iw`、`ip`、`am broadcast`、自身 `wanboxctl` / `rootbox` | 常量；`iw dev <iface>` 的 iface 先过 `ifaceName` 正则；`am broadcast` 由 `BuildAutoStopBroadcast` 校验包名正则、userID 范围和 phase 白名单 | 否 |

## 逐面判定

### ① 备份导入（PortableBackup，以及旧版兼容导入）——不可达

- `database/PortableBackup.kt` `parse()`：节点类型只能取 `beanClasses`（`ProxyEntity` 的 Bean 字段，L83–L88、L170），Bean 字段按类声明白名单逐键校验（`fields(beanJson, names(clazz))` L172）。没有「自定义插件 / 可执行路径 / 参数」这类 Bean。
- 唯一名字带插件含义的 `NekoBean`（`plgId` / `protocolId` / `sharedStorage`）是空壳：`displayType()` 返回 `"invalid"`；`BoxInstance.init()` / `launchExternal()` 没有它的分支，Root 快照构建遇到它直接抛 `external_plugin_unsupported`。
- `settings` 段可以恢复任意键值（`BackupRestore.validate` 只校验类型和长度），但上表里没有一个执行面读取未经校验的设置值。
- 旧版 Parcel 备份（`BackupRestore.kt` L35–L46）和 Throne 桌面备份（`ThroneDesktopBackupImporter.kt`）最终都落到同一组 Bean 和设置键上。Throne 的 `extracore` 一类未知类型会被转成 `ConfigBean`，作为 sing-box 自定义出站 JSON（L384–L389）；sing-box 不认识这个类型就拒绝，不会执行任何程序。

### ② 插件 / 外部程序调用面——不可达

见上表。可执行文件只来自固定插件 ID，加上已安装提供方或 App 自带 `.so`；参数是常量或 App 生成的路径；Root 侧再按 `kind` 白名单和 ELF 头复核一遍。导入数据改不了目标路径，也改不了参数。

### ③ 订阅内容与分享链接导入——不可达

订阅和分享链接解析出的也是上面同一组 Bean，或者 `ConfigBean`（sing-box 原生 JSON）。Shadowsocks 的 `plugin` 字段交给 sing-box 内置的 SIP003 实现（`transport/sip003/plugin.go` 只查注册表里的 Go 构造器，整个目录不引入 `os/exec`），不会拉起外部程序。

## 内核侧边界（只记录，不改 fork）

在 reF1nd tag `17397d02` 的源码中检索 `"os/exec"`，非测试文件里与 wanBox 构建相关的结果如下（构建标签：`libcore/build.sh` L53，`with_conntrack,with_quic,with_wireguard,with_utls,with_clash_api,with_ebpf`）：

- `protocol/tailscale/tailssh/*`：需要 `with_tailscale`，wanBox 不带这个标签，**不编译**。
- `experimental/clashapi/restart.go`：重启时执行的是自身 `os.Executable()` 加原有 `os.Args`，可执行文件不由配置指定；Windows 才走 `exec.Command`，其他平台走 `replaceProcess` 替换自身进程。
- `protocol/bridge/netfilter_linux.go`：bridge 出站会以当前进程权限调用固定的 `iptables` / `ip6tables`，参数里有配置提供的接口名（独立 argv，不经过 shell）。它不执行任意程序，不属于本公告这一类；但自定义出站 JSON 可以借它在 Root 模式下改 netfilter 规则。是否要在 App 侧拒绝 `bridge` 类型的自定义出站，**需用户拍板**（红线禁止改 fork，只能在 libcore 扩展层或 App 侧处理）。
- `common/netns/manager_linux.go`：`holderArgs` 来自 Go 调用方的 `box.Options.NetworkNamespaceHolderArgs`，不来自 JSON；libcore 不设置它。
- `cmd/*`：CLI 与构建工具，不进 libcore 和 rootbox。

## 非本公告类别的旁注（未修改）

- `RootModuleClient.kt` 的 `rewrite()` 会把配置里以 `/data/user/`、`/data/data/`、`/storage/emulated/` 开头的字符串当作 App 可读文件，复制进 Root 快照。这是读取文件、不是执行；这类路径可能来自导入的自定义 JSON。
- `ThroneDesktopBackupImporter.kt` L389 把 `typeHint` 直接拼进 JSON（没有转义），能往生成的自定义出站里注入字段。结果仍是一份 `ConfigBean`，能力不超过手工导入一个自定义出站。

## 验证方式

本次是只读源码审查（命令：`grep -rn "ProcessBuilder\|Runtime.getRuntime().exec\|exec.Command"` 覆盖 `app/src/main/java`、`rootmodule`、`libcore`；内核源码 `git clone --depth 1 --branch v1.15.0-alpha.13-reF1nd` 后 `grep -rln '"os/exec"'`）。没有在设备上复现，也没有构造样本做运行时测试。
