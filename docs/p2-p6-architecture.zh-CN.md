# wanBox P2–P6 改造方案（2026-10-18）

作者：@author 雾晚
基线：`v3.0.10-preview.3`（`2df46b44`）
内核目标：sing-box `v1.15.0-alpha.12`（对应 `7ae1b8048`，已完成）

## 一、现状与差距

| 能力 | 现状 | 差距 |
| --- | --- | --- |
| 模块生命周期 | `service.sh` `exec __internal boot`，Boot 完成后进程退出；长期进程只有 `__internal supervise` | 没有独立常驻宿主，代理停止即无管理面 |
| App ↔ 模块 | 每次命令 `su -c wanboxctl <cmd>`，配置走 stdin 传完整快照 | 每次 100–300ms 的 su 往返，App 必须持 Root |
| 节点切换 | 模块侧 `node select` 已是 Clash API 热切换 | 被 supervisor 快照覆盖（已在 `92b4998d7` 修复） |
| 状态刷新 | `RootTunService` 每 2 秒 `dashboard` + Binder 回调 | 单次失败即置 Stopped（已在 `99f7fa4ea` 加防抖） |
| 配置生成 | App 侧 `ConfigBuilder` 生成整份 config + 资源，Base64 上传 | 重活在前台，快照可达 128 MiB |

## 二、目标架构

```text
                ┌──────────────────────────────┐
                │  App（轻量管理前端，无 Root）  │
                └──────────────┬───────────────┘
                               │ loopback HTTPS + Bearer + 证书钉扎
                ┌──────────────▼───────────────┐
                │  wanboxctl __internal serve   │  ← 唯一长期宿主
                │  ├─ HTTP API / SSE            │
                │  ├─ 首次凭据交付（一次性 su）   │
                │  └─ 开机自启 / 订阅调度（后续） │
                └──────────────┬───────────────┘
                               │ 领域函数直调，不经过 CLI
                ┌──────────────▼───────────────┐
                │  __internal supervise         │  ← 核心守护
                │  └─ rootbox / sing-box         │
                └──────────────────────────────┘
```

关键约束：`serve` 不随代理启停而退出；`supervise` 只负责核心；两者通过 `control.lock` 与 `runtime/selection.json` 交换状态。

## 三、分模块改动

### 3.1 模块侧（Go，已完成）

| 文件 | 改动 |
| --- | --- |
| `rootmodule/api_server.go`（新增） | `APIConfig` 读写、自签 ECDSA 证书、`Serve`、路由、鉴权、SSE |
| `rootmodule/cmd/wanboxctl/main.go` | 注册 `__internal serve` |
| `rootmodule/package/service.sh` | `boot` → `serve` |
| `rootmodule/selection_state.go`（新增） | 热切换运行态事实源，跨进程合并 |
| `rootmodule/{node_select,supervisor,runtime}.go` | 选择写入与持久化合并 |

`Serve` 的行为顺序：

1. 取 `api.lock`，已有实例则返回 `api_already_running`；
2. 读或生成 `api.json` 与证书（**已存在绝不覆盖**，否则已连接客户端全部失效）；
3. 监听 `127.0.0.1:0`，把实际端口回写 `api.json`；
4. 异步执行 Boot（等 `sys.boot_completed`、读 auto_start、拉起 supervisor）；
5. 阻塞至进程信号；退出时 `Shutdown` 5 秒。

端口绑定失败不退出进程，只记录 `api_listen_failed` 并继续 Boot——代理优先。

### 3.2 App 侧（Kotlin）

| 文件 | 改动 |
| --- | --- |
| `bg/ModuleApiClient.kt`（新增） | HTTPS 客户端、证书钉扎、SSE、凭据桥 |
| `bg/RootModuleClient.kt` | 保留 `Status`/`parseResponse`；`call()` 改为委托 `ModuleApiClient` |
| `bg/ServiceCommandController.kt` | 后端实现改为调用 API，删除 shell 分支 |
| `bg/SamplingFailurePolicy.kt`（新增） | 连续失败降级 |
| `AndroidManifest.xml` | 删除 `REQUEST_INSTALL_PACKAGES` 之外的 Root 相关声明与提示文案 |

`RootModuleClient` 的 `Status` 数据类与解析逻辑完全复用，避免 300+ 处调用点连带改动。

## 四、HTTPS 管理接口

### 4.1 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/health` | 存活探测，无需鉴权 |
| GET | `/v1/status` | 完整状态快照（与 CLI `status` 同构） |
| GET | `/v1/events` | SSE，`event: status`，先全量后增量 |
| POST | `/v1/select` | `{"profileId": N}`，只切换不重启 |
| POST | `/v1/service/start` | 等价 `start` |
| POST | `/v1/service/stop` | 等价 `stop` |

后续（P2 第二阶段）：`/v1/nodes`、`/v1/subscriptions`、`/v1/config`（分区读写 + revision 冲突检测）。

### 4.2 鉴权与传输

- 只监听 loopback；`api.json` 固定 `{"listen","token","certSha256"}`。
- token 为 256 bit 随机值，`subtle.ConstantTimeCompare` 比较，防时序侧信道。
- 每安装独立自签 ECDSA P-256 证书，SAN 只含 `127.0.0.1`/`localhost`，有效期不依赖设备时钟。
- 客户端用自定义 `X509TrustManager` 比对 DER 的 SHA-256，并叠加 `CertificatePinner`；不回落系统 CA。
- 非回环 `Host` 直接 403，防 DNS rebinding。
- 请求体上限 4 KiB（当前端点），错误码统一 `^[a-z_]{1,80}$`。

### 4.3 凭据交付

首次连接由 `SuCredentialBridge` 执行一次 `su -c cat /data/adb/wanbox/api.json`，之后全部走 HTTPS。
这是**过渡态**：完全消除 App 的 Root 调用需要模块侧提供固定签名可验的私有 ContentProvider，仅在首次安装/重装时交付凭据。建议作为 P3 的收尾项。

## 五、节点切换为什么不再闪

旧链路有三个断点，逐一说明：

1. **核心不重启**：`SelectNode` 走 Clash API `PUT /proxies/proxy`，回读确认后才写状态，全程不触碰 TUN。
2. **状态不被回退**：supervisor 每约 5 秒用启动快照重写 `state.json`。现在热切换写 `runtime/selection.json`，persist 与 `Status()` 都从它合并，并用 `RunningRevision` 隔离代次。
3. **UI 不抖动**：`SamplingFailurePolicy` 要求连续 3 次采样失败才降级；`RootTunService` 不再因单次 su 超时清空 `currentProfile`。

配合 SSE 后，App 侧可以不再 2 秒轮询，状态由服务端推送，按钮的 `checked` 只随真实 `phase` 变化。

## 六、Root 标志消除与前台精简

### 6.1 分两层理解

- **设备层**：必须 Root（模块挂载、TUN、iptables/nftables），这是产品前提，不可消除。
- **App 层**：可消除。具体清单：
  - 删除 `Runtime.exec("su")` 全路径，只保留首次凭据读取；
  - 删除「Root 权限」「模块未授权」等提示文案与图标角标；
  - 删除 `REQUEST_INSTALL_PACKAGES` 之外的提权相关声明；
  - 首次连接失败只提示「模块服务未就绪」，不引导用户去授权 Root。

### 6.2 前台精简

| 原职责 | 迁移后 |
| --- | --- |
| 生成完整 sing-box 配置与资源快照 | 模块侧按偏好生成（P5 第二阶段） |
| 服务启停编排 | `POST /v1/service/*`，模块自行管理生命周期 |
| 状态轮询与 Binder 广播 | SSE 推送，App 仅订阅 |
| 订阅更新调度 | 模块 `serve` 内置调度器 |
| 热更新与数据迁移 | 模块已有 `data prepare/finish/rollback` |

App 最终只保留：节点列表、订阅列表、设置、仪表盘展示。

## 七、兼容性

- 包名 `com.lit008834.pixel.wanboxforandroid`、签名、`versionCode` 换算规则不变。
- Room 数据库与备份格式不改；`selection` 仍是 `module.json` 的持久事实源，`selection.json` 只是运行态。
- 覆盖安装：`api.json` 与证书保留在数据目录，升级后旧 token 继续有效，App 不需要重新引导。
- 仅 arm64-v8a、Android 12+、Root 环境；`customize.sh` 已有校验不变。

## 八、测试检查清单

**已验证（沙箱）**

- [x] `go build ./...`、`go vet ./...`、`go test ./...` 全绿
- [x] `/v1/status` 无 token → 401
- [x] 非回环 Host → 403
- [x] 核心停止（无 `state.json`）时 `/v1/status` 返回 200 + `phase=stopped`
- [x] `/v1/select` 触发 Clash 热切换且 `state.profileId` 更新
- [x] `api.json` 重复读取不轮换 token/证书
- [x] 热切换后 supervisor 覆盖 `state.json`，`Status()` 仍为新节点
- [x] 跨代次 `selection.json` 不生效
- [x] alpha.12 二进制 `sing-box check` 两份配置 exit 0
- [x] `:app:testOssDebugUnitTest` 313 项通过（Gradle 8.10.2 + AGP 8.x + JDK 20 + NDK r27c）
- [x] `:app:assembleOssDebug` 产出 `wanBoxForAndroid-3.0.9-arm64-v8a-debug.apk`（43 MB）
- [x] `libcore.aar` 由 alpha.12 源码重新 bind 并参与编译

**待验证（真机）**

- [ ] 真机：同组/跨组/连续快速切换，录屏确认按钮不闪
- [ ] 真机：杀 App 后模块继续代理，重开 App 状态正确
- [ ] 真机：`service.sh` 改为 `serve` 后开机自启仍生效
- [ ] 真机：覆盖安装后旧 `api.json` 可用，App 无需重新引导
- [ ] 真机：核心崩溃 → API 仍可读 → 可重启服务
- [ ] 证书轮换场景：删除证书文件后客户端能重新钉扎

## 九、未完成项（需排期）

1. `/v1/nodes`、`/v1/subscriptions`、`/v1/config` 尚未实现；配置写入需要分区 + revision 冲突检测。
2. 配置生成下沉到模块（P5 第二阶段），当前仍由 App 生成整份快照。
3. 凭据交付改为私有 Provider，彻底移除 App 的 su 调用。
4. 订阅调度器尚未挂到 `serve`。
