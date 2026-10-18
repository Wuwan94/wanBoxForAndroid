# wanBox for Android v3.0.10-preview.10 更新说明

本版本定位并修掉 `serve` 根本起不来的一个启动阻断：`wanboxctl` 在进入 `serve` 之前会先做 cgroup 隔离，这一步在部分 Android 16 / HyperOS 机型上失败，导致**管理接口整体没有启动**——现象就是 App 一直提示模块服务不可用，而 `/data/adb/wanbox/delivery.log` 与 `api.json` 的时间戳都不更新。

### 核心变更

* **cgroup 失败不再阻断管理面**：`__internal serve` 在 cgroup 隔离失败时继续启动 HTTPS 管理接口（记录 `cgroup unavailable` 后照常监听），其余命令仍按失败处理。管理面是唯一长期宿主，它起不来，App 连状态都读不到。
* **启动追踪落两处**：`/sdcard/wanbox-serve.log`（无需 root，文件管理器直接打开）与 `/data/adb/wanbox/serve.log`，记录启动参数、euid、cgroup 结果、`api.json` 状态、监听地址、交付结果与退出原因。下次再出问题，看这个文件即可定位，不用再猜。
* **交付结果继续写** `/data/adb/wanbox/delivery.log`（每个目标路径的成败与原因）。
* 其余逻辑（凭据交付、SELinux 上下文对齐、端口退避、节点迁移、热切换）保持 preview.9 不变。

### 兼容性与产物

* 包名 `com.lit008834.pixel.wanboxforandroid`、原签名、数据库与备份格式未变，可直接覆盖 v3.0.9 与 v3.0.10-preview.5 ~ preview.9。
* 仅 arm64-v8a、Android 12+、Root 环境；仍为纯 Root 模块，不提供 VPN 模式。
* 预览版本 `3.0.10-preview.10`，模块版本代码 `372`，APK 版本代码 `1860`，内核 `sing-box v1.15.0-alpha.12`。
* 交付 ARM64 APK、纯 Root 模块、内嵌同一 APK 的整合模块与 `SHA256SUMS`；预览渠道仍只保留最新一版。

### 升级与自查

1. 安装 `wanbox-root-module-with-manager-arm64-v8a.zip`，**重启手机**（`service.sh` 只在开机执行）。
2. 打开 App 等 30 秒。
3. 若仍不可用，用文件管理器打开 `/sdcard/wanbox-serve.log`，里面会直接写明卡在哪一步：
   * `start euid=0 args=[__internal serve]` 之后没有 `serve: config ready` → 初始化或配置失败
   * `serve: lock failed` → 有残留进程占用锁
   * `serve: config ready ... delivered=false` → 凭据交付失败（再看 `delivery.log`）
   * `serve: listening 127.0.0.1:<port>` → 服务已起来，问题在 App 侧（日志里会有 `module status sample failed`）

### 验证边界

* 模块侧本地通过 `go build`（windows 与 linux/arm64）、`go vet`；App 侧未改动。
* **未在真实 ARM64 Root 设备验证**：cgroup 兜底路径、`/sdcard` 写入时机（开机 late_start 阶段）需真机确认。
* 本地单元测试受 Windows 测试 JVM 启动失败影响未执行，由 CI 把关。

### 已知问题

* 订阅拉取与节点 CRUD 仍未做真机联调。
* 跨用户 UID 暂缺，多用户/克隆应用的分流只覆盖当前用户。
* `RootModuleSnapshot` 仍用于收集资源文件，配置本体已不由它下发。
