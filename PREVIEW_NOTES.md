# wanBox for Android v3.0.10-preview.4 更新说明

本版本为内核升级与模块管理面改造的预览版本（Pre-release）。核心变化是 sing-box 升级到 1.15.0-alpha.12、节点热切换不再导致上方按钮闪烁，以及模块新增独立于代理进程的本地 HTTPS 管理接口。

### 核心变更

* **内核升级至 sing-box 1.15.0-alpha.12**：相对 alpha.10 的 54 个提交中，直接影响本项目的为嵌套出站组解析与空闲连接回收两项修复。TUN 默认栈经核对未变化（两版依赖的 sing-tun 中 `NewStack("")` 均落到 go 栈），配置未改动。仍保留 `independent_cache`，该字段在 1.16 移除，届时需单独迁移。
* **修复节点切换时按钮与节点名闪烁**：根因不是核心重启，而是 supervisor 按 stats 节奏（约 5 秒）用启动快照重写 `state.json`，把热切换写下的 `profileId` 覆盖回旧值。新增 `runtime/selection.json` 作为运行态选择事实源，带 `RunningRevision` 隔离代次，`supervisor.persist` 与 `Status()` 均从它合并。节点切换仍是 Clash API `PUT /proxies/proxy` + 回读确认，全程不重建 TUN。
* **新增模块独立 HTTPS 管理接口**：`__internal serve` 成为唯一长期宿主，先监听 loopback HTTPS 再执行开机自启，代理停止或核心启动失败都不退出。当前提供 `/v1/health`、`/v1/status`、`/v1/events`（SSE）、`/v1/select`、`/v1/service/start|stop`。鉴权为每安装独立的 256 bit token 加自签证书钉扎，只接受回环 Host。
* **App 侧采样防抖**：原先任何一次 dashboard 采样异常都会立刻把界面置为未连接并清空当前节点，节点切换并发拉起 su 造成的单次超时会让按钮先灭后亮。改为连续三次失败才降级；真实停服走 `phase=stopped` 正常响应，不经过异常分支。
* **App 侧新增 HTTPS 客户端**：`ModuleApiClient` 已实现证书指纹校验、SSE 订阅与 401 重新交付，但尚未替换现有调用点，本版本行为仍走原有通道。

### 兼容性与产物

* 包名 `com.lit008834.pixel.wanboxforandroid`、原签名、数据库与备份格式不变，可直接覆盖 v3.0.9 与 v3.0.10-preview.3。
* 仅 arm64-v8a、Android 12+、Root 环境，仍为纯 Root 模块，不提供 VPN 模式。
* 预览版本 `3.0.10-preview.4`，模块版本代码 `366`，APK 版本代码 `1830`。
* 交付 ARM64 APK、纯 Root 模块、内嵌同一 APK 的整合模块与 `SHA256SUMS`。

### 验证边界

* 内核与模块：`go build`、`go vet`、`go test ./...` 全绿，含新增的管理接口鉴权、停止态可读、热切换不重启、凭据幂等、选择跨代次隔离等测试；alpha.12 二进制对两份配置 `sing-box check` 均通过。
* App：`testOssDebugUnitTest` 313 项通过，`assembleOssDebug` 产出 arm64-v8a APK。本地构建使用 NDK r27c，与 CI 的 25.0.8775105 不同，原生层以 CI 结果为准。
* **尚未在真实 ARM64 Root 设备验证**：模块挂载、TUN 与 auto_redirect 联调、切换录屏、杀 App 后代理是否持续、开机自启走 `serve` 入口、覆盖安装实测均未进行。
* `service.sh` 已从 `__internal boot` 改为 `__internal serve`，这是本版本运行时风险最高的改动，升级后请首先确认开机后模块能否正常拉起代理。

### 已知问题

* App 的 `RootModuleClient` 仍走 `su` 调用，尚未切到 HTTPS 客户端；「消除 Root 标志」与配置生成下沉未在本版本完成。
* 核心停止时管理接口可访问，但 `/v1/nodes`、`/v1/subscriptions`、`/v1/config` 尚未实现。
