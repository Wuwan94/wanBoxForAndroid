# wanBox for Android v3.0.10-preview.11 更新说明

本版本修掉真机实测定位到的最后一个断点：**证书钉扎失败**。模块的 HTTPS 接口正常监听、凭据文件也已交付，但 `api.json` 里记录的证书指纹与实际服务的证书不是同一张，App 的钉扎校验拒绝连接，界面表现为「模块服务暂时不可用」。

### 核心变更

* **证书指纹自愈**：`EnsureAPIConfig` 每次启动都用**实际证书**重新计算 SHA-256，与 `api.json` 中的值比对；不一致就写回并重新交付。之前只要 `api-cert.pem` / `api-key.pem` 缺失或损坏被重新生成，`api.json` 就会留着旧指纹，客户端全部被钉扎拦截。
* **token 仍然不轮换**：只校准指纹，不重置 token，避免把已经配对成功的管理端全部踢下线。
* **证书轮换留痕**：指纹变化时记录 `api_certificate_rotated` 事件并写启动追踪，便于以后排查。
* 新增回归测试 `TestAPIConfigRefreshesFingerprintAfterCertificateRotation`：删除证书文件后重启，断言 token 不变、指纹刷新、且 `api.json` 与实际证书一致。
* preview.10 的 cgroup 兜底与启动追踪保持不变。

### 兼容性与产物

* 包名 `com.lit008834.pixel.wanboxforandroid`、原签名、数据库与备份格式未变，可直接覆盖 v3.0.9 与 v3.0.10-preview.5 ~ preview.10。
* 仅 arm64-v8a、Android 12+、Root 环境；仍为纯 Root 模块，不提供 VPN 模式。
* 预览版本 `3.0.10-preview.11`，模块版本代码 `373`，APK 版本代码 `1865`，内核 `sing-box v1.15.0-alpha.12`。
* 交付 ARM64 APK、纯 Root 模块、内嵌同一 APK 的整合模块与 `SHA256SUMS`；预览渠道仍只保留最新一版。

### 本地验证

* `go build ./...`（windows 与 linux/arm64 双目标）、`go vet ./...` 全过。
* `go test -run "TestAPIConfig|TestReplaceNodes"` 全过（含新增的证书轮换用例）。
* 全量 `go test ./...` 仅剩 Windows 平台既有的 8 个平台相关失败（`root_requires_android_or_linux` / `VmRSS`），与本次改动无关。

### 升级与自查

1. 安装 `wanbox-root-module-with-manager-arm64-v8a.zip` 后重启手机（`service.sh` 只在开机执行）。
2. 打开 App 等待 10 秒；若仍不可用，查看 `/sdcard/wanbox-serve.log`，出现 `certificate rotated` 说明自愈生效过。
3. Root 侧可对照：`cat /data/adb/wanbox/api.json` 里的 `certSha256` 应与 `/data/adb/wanbox/api-cert.pem` 的哈希一致。

### 已知问题

* 订阅拉取与节点 CRUD 仍未做真机联调。
* 跨用户 UID 暂缺，多用户/克隆应用的分流只覆盖当前用户。
* `RootModuleSnapshot` 仍用于收集资源文件，配置本体已不由它下发。
