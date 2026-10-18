# wanBox for Android v3.0.10-preview.8 更新说明

本版本把「节点」也交回模块：App 首次连接时把当前配置里的节点一次性迁移进模块存储，之后节点真值只在模块；同时补上核心停止时仍可读取的日志接口。preview.7 的管理凭据交付是本版的前置条件。

### 核心变更

* **节点首次迁移（覆盖安装不丢节点）**：模块新增 `PUT /v1/nodes` 整表替换端点，App 在检测到模块 `store/nodes.json` 为空时，把当前配置的 outbounds 经 `profileTagMap` 还原成「profileId → 节点」，一次性写入模块。写入带 id 补全与去重，非法 outbound 整批拒绝、不落盘。
* **迁移只做一次**：之后模块是节点真值，App 不再整表回写，避免覆盖模块侧新增或编辑的节点；常规增删仍走单条 `POST/PUT/DELETE /v1/nodes`。
* **新增 `GET /v1/logs?lines=N`**：返回模块事件环（默认 200 条，上限 1000），核心停止时同样可读，App 不再需要读 logcat 或持有一份本地日志。
* **配置保持意图下发**：App 只提交路由、入站、DNS、实验项与资源文件；节点由模块合并进 outbounds 并生成 selector，`PUT /v1/config` 失败时保留上一份有效配置。
* 凭据交付、端口退避、三路兜底读取、传输层重试、热切换与 SSE 优先/轮询兜底全部沿用 preview.7。

### 兼容性与产物

* 包名 `com.lit008834.pixel.wanboxforandroid`、原签名、数据库与备份格式未变，可直接覆盖 v3.0.9、v3.0.10-preview.5 / preview.6 / preview.7。
* 首次升级后打开 App 会自动完成节点迁移；迁移后 `store/nodes.json` 即为节点真值，备份恢复与订阅更新都写这里。
* 仅 arm64-v8a、Android 12+、Root 环境；仍为纯 Root 模块，不提供 VPN 模式。
* 预览版本 `3.0.10-preview.8`，模块版本代码 `370`，APK 版本代码 `1850`，内核 `sing-box v1.15.0-alpha.12`。
* 交付 ARM64 APK、纯 Root 模块、内嵌同一 APK 的整合模块与 `SHA256SUMS`；预览渠道仍只保留最新一版。

### 验证边界

* 模块侧本地通过 `go build ./...`（windows 与 linux/arm64）、`go vet ./...`、`go test -run TestReplaceNodes`（id 分配、去重、非法输入、清空四类用例）。
* CI 的 `Native Build (LibCore)` 执行 `go test -race` 与 `go vet`，App 侧执行 Gradle 编译与 `testPreviewDebugUnitTest`。
* **未在真实 ARM64 Root 设备验证**：节点迁移后的热切换是否覆盖全部协议、迁移后 selector 与旧配置的 tag 是否一致、订阅更新与迁移节点的 id 是否冲突，均需真机确认。
* 迁移只覆盖当前配置实际包含的节点；Room 中未被当前配置引用的节点不会进入模块，需要时可用单条 `POST /v1/nodes` 补回。

### 已知问题

* **订阅拉取与节点 CRUD 仍未做真机联调**：真实订阅 URL 的全链路未验证。
* **跨用户 UID 暂缺**：App 已关闭 `su` 枚举，克隆应用/多用户的按应用分流目前只覆盖当前用户；后续由模块 `/v1/uids` 提供。
* **`RootModuleSnapshot` 仍在用**：当前仅用于收集资源文件（assets / yacd / 插件 / profile_tags），配置本体的 outbounds 已不下发；彻底删除属于下一阶段（见 `docs/refactor-v3.1.zh-CN.md` P1 剩余项）。
