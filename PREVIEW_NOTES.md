# wanBox for Android v3.0.10-preview.6 更新说明

> 历史源码说明：本文保留 preview.6 的实现与验证边界，不是当前最新预览版的发行说明。截至 2026-10-21，公开预览为 [preview.16](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.10-preview.16)，其管理器已换签名，不能套用本文的直接覆盖升级结论；当前源码元数据也不是 preview.6，发布前必须重新核对。见 [发布与源码版本对照](docs/current-version.zh-CN.md)。

本版本把「数据与职责」真正下沉到模块：节点、订阅、全局配置的真值改由模块保存，App 只负责展示与下发指令，管理面补全为完整的回环 HTTPS 接口，App 侧删除 `su` 传输层。界面、节点热切换、推送状态流与 preview.5 保持一致。

### 核心变更

* **模块成为节点/订阅/配置的唯一真实数据源**：新增模块内置存储 `store/nodes.json`、`store/subscriptions.json`、`store/config.json` 与 `store/meta.json`，读写走原子替换，与核心进程状态解耦。核心停止、启动失败或模块刚安装时，这些数据照常可读写。
* **补全模块 HTTPS 管理接口**：在原有 `/v1/health`、`/v1/status`、`/v1/events`、`/v1/select`、`/v1/service/start|stop` 之外，新增：
  * 节点：`GET /v1/nodes`、`POST /v1/nodes`、`PUT /v1/nodes/{id}`、`DELETE /v1/nodes/{id}`
  * 订阅：`GET /v1/subscriptions`、`POST /v1/subscriptions`、`POST /v1/subscriptions/{id}/update`、`DELETE /v1/subscriptions/{id}`
  * 配置：`GET /v1/config`、`PUT /v1/config`
  * 服务：`POST /v1/service/reload`、`POST /v1/service/autostart`
  * 数据：`POST /v1/data/prepare|finish|rollback|selection-finish`
  
  鉴权方式不变：仅接受回环地址、每次安装独立的 Bearer token、自签证书 SHA-256 钉扎；错误统一返回 `{"schema":1,"ok":false,"error":"<code>"}`。
* **App 全面切换到 HTTPS 客户端**：`ModuleApiClient` 的请求层泛化为可返回任意 JSON，新增节点、订阅、配置、服务与数据端点的方法；`RootModuleClient` 删除 `ProcessBuilder("su", ...)` 传输层，收敛为 HTTPS 门面，启停、切节点、配置应用、状态查询全部经本客户端。
* **配置改为「意图下发」**：App 不再生成完整配置快照并强推，只提交路由、入站、DNS、实验项与资源文件；模块读取自身节点数据、合并后生成完整 sing-box 配置，校验通过再走原有的 generation 与 Apply 流程，失败保留上一份有效配置。
* **节点切换体验不变**：热切换仍走 Clash API 选择器并由 `runtime/selection.json` 承载，不重启核心；事件流优先、轮询兜底的刷新机制保持原样，切换期间上方按钮与节点名不闪烁。

### 兼容性与产物

* 包名 `com.lit008834.pixel.wanboxforandroid`、原签名、数据库与备份格式均未改变，可直接覆盖 v3.0.9 与 v3.0.10-preview.5，用户数据与已保存设置保留。
* 仅 arm64-v8a、Android 12+、Root 环境；仍为纯 Root 模块，不提供 VPN 模式。
* 预览版本 `3.0.10-preview.6`，模块版本代码 `368`，APK 版本代码 `1840`，内核 `sing-box v1.15.0-alpha.12`。
* 交付 ARM64 APK、纯 Root 模块、内嵌同一 APK 的整合模块与 `SHA256SUMS`。
* 预览渠道只保留最新一版：发布本版时会自动清理更早的预览 Release 与 tag，正式版不受影响。

### 验证边界

* 模块侧在本地以 Go 1.25.0 完成 `go build ./...`、`go vet ./...` 与存储层运行时测试（含并发读写与锁重入回归），均通过；CI 的 `Native Build (LibCore)` 同样执行 `go test -race` 与 `go vet`，通过。
* App 侧在 CI 完成 Gradle 编译与 `testPreviewDebugUnitTest`，通过。本次同步更新了 3 个源码契约测试（旧 `config apply` 快照流程 → 新的 `applyConfig` 与配置意图流程），并保留节点 id 的注入防护断言。
* **尚未在真实 ARM64 Root 设备验证**：模块挂载、TUN 与 auto_redirect 联调、节点切换录屏、杀 App 后代理是否持续、覆盖安装实测均未进行。
* **订阅更新与节点 CRUD 未做设备联调**：接口已实现并有本地单元测试，但尚未用真实订阅 URL 走完整链路。

### 已知问题

* **首次凭据交付仍在过渡期**：默认的文件桥读取 App 私有目录下的 `module-api.json`，需要模块侧在启动时把 `api.json` 同步到该位置；本版尚未完成这一步同步，若该文件缺失会报 `module_credentials_unavailable`。迁移用的 `SuCredentialBridge` 代码保留但默认不启用。
* **订阅解析能力有限**：支持 sing-box JSON 节点数组与 base64 节点 URI 列表；Clash YAML 仅解析基础字段（`name`、`type`、`server`、`port`、`cipher`/`uuid`、`password`、`tls`），复杂协议与规则集不下沉。
* **节点与订阅界面仍以 Room 为草稿**：模块是已生效数据的真值，Room 继续承担离线编辑与备份导入；两者一致性靠显式回灌，暂未做双向自动同步。
* **`data` 系列端点已就绪但 App 侧旧事务链路仍在**：数据迁移的 HTTP 形态已提供，App 的 `dataUpdate` 目前仍以原有事务为主，后续版本切换。
