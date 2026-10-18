# wanBox for Android v3.0.10-preview.7 更新说明

本版本修复 preview.6 的致命故障：模块已安装、Root 也已授权，但 App 仍提示「模块服务暂时不可用」。根因是管理接口的凭据从未交付到 App 可读的位置——模块只把 `api.json` 写在 `/data/adb/wanbox/`（0700 root），而 App 在 preview.6 里改为只读自己的私有目录，这条链路断在最后一环。

### 核心变更

* **模块主动交付管理凭据（零 Root 的基础）**：`serve` 启动时、端口回写后以及每 5 分钟，把 `{listen, token, certSha256}` 写入 App 的 `no_backup/`、`files/` 与外置私有目录三处；私有目录按 App 数据目录属主 `chown`、`chmod 0600` 并 `restorecon`，外置目录由系统授权。App 只做普通文件读取，全程不执行 `su`、不申请超级用户。
* **端口占用不再导致管理面永久失效**：`api.json` 记录的端口被占用时，改用系统分配端口重新绑定，随后回写并重新交付凭据。
* **App 侧凭据查找多路兜底**：按 `no_backup → files → 外置私有目录` 顺序读取，任一可用即连接；覆盖安装、清除数据后模块会在 5 分钟内补交付。
* **传输层区分两类失败**：业务错误（`409/401` 等固定错误码）直接上报不重试；传输错误（模块重启导致端口变化）丢弃缓存、重读凭据后自动重试一次，界面不再需要手动重进。
* **清除最后一处会弹 Root 的调用**：`PackageCache` 的跨用户 UID 枚举默认关闭（`allowRootUidLookup = false`），App 侧不再存在任何 `ProcessBuilder("su", ...)`。
* 节点热切换、事件流优先/轮询兜底、六组设置与界面精简成果全部保持不变。

### 兼容性与产物

* 包名 `com.lit008834.pixel.wanboxforandroid`、原签名、数据库与备份格式未变，可直接覆盖 v3.0.9、v3.0.10-preview.5 / preview.6。
* **升级后无需重装模块**：新模块启动即交付凭据；若先只升级 App，需等模块升级到 preview.7 才能读到凭据文件。
* 仅 arm64-v8a、Android 12+、Root 环境；仍为纯 Root 模块，不提供 VPN 模式。
* 预览版本 `3.0.10-preview.7`，模块版本代码 `369`，APK 版本代码 `1845`，内核 `sing-box v1.15.0-alpha.12`。
* 交付 ARM64 APK、纯 Root 模块、内嵌同一 APK 的整合模块与 `SHA256SUMS`；预览渠道仍只保留最新一版。

### 验证边界

* 模块侧本地通过 `go build ./...`（windows 与 linux/arm64 双目标）、`go vet ./...`；属主处理用 `!windows` / `windows` 双实现隔离，桌面开发构建不受影响。
* CI 的 `Native Build (LibCore)` 执行 `go test -race` 与 `go vet`，App 侧执行 Gradle 编译与 `testPreviewDebugUnitTest`。
* **未在真实 ARM64 Root 设备验证**：交付文件的属主与 SELinux 上下文、`restorecon` 是否可用、端口占用退避路径，均需真机确认。
* 若设备上模块数据区已被其它 Root 进程改动，或 App 与外置存储不在同一用户，可能需要手动重启模块一次以补交付。

### 已知问题

* **订阅拉取与节点 CRUD 仍未做真机联调**：接口与本地单测齐备，真实订阅 URL 的全链路未验证。
* **跨用户 UID 暂缺**：App 已关闭 `su` 枚举，克隆应用/多用户的按应用分流目前只覆盖当前用户；后续由模块 `/v1/uids` 提供。
* **配置仍以「意图 + 资源文件」下发**：`RootModuleSnapshot` 尚未删除，配置生成进一步收敛见 `docs/refactor-v3.1.zh-CN.md` 的 P1 阶段。
