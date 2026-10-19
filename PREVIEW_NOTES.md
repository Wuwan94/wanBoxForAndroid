# wanBox for Android v3.0.10-preview.13 更新说明

preview.12 发布后定位到三处缺陷，本版全部修掉。其中前两处是**运行期缺陷**（触发条件不常见但后果严重），第三处是 CI 上的测试竞态。本版包含 preview.12 的全部内容。

### 核心变更

* **配置意图改为应用成功后才落盘**：`ApplyConfigIntent` 原来在 `Apply` 之前就把新意图写进 store。一旦 Apply 失败（核心校验不通过、启动失败回滚），这份**从未生效**的意图已经持久化，下次 reload 会拿它悄悄拼出一份没人验证过的配置。现在严格在 Apply 成功后才落盘，失败时 store 保持上一次生效的配置。
* **热更新异常分支恢复管理服务**：停管理面被提前到「重命名模块目录之前」之后，两处 `module_replace_failed` 直接返回，把已停掉的管理面丢在停止状态 —— 热更新失败后 App 直接失联。现在两个重命名失败分支都会把旧管理面重新拉起（目录要么没动、要么已还原成旧代码，都能安全恢复）。
* **消除 TLS 测试断言竞态**：`ModulePinningTest` 原来在客户端读到响应后立即断言服务端握手计数，而计数在另一个线程记账 —— CI 上服务端线程还没来得及记账就判失败，偶发假阴性（preview.12 首次 CI 失败就是死在这里）。两处服务端计数断言改为带截止时间的轮询等待。

### 兼容性与产物

* 包名 `com.lit008834.pixel.wanboxforandroid`、原签名、数据库与备份格式未变，可直接覆盖 v3.0.9 与 v3.0.10-preview.12。
* 仅 arm64-v8a、Android 12+、Root 环境；仍为纯 Root 模块，不提供 VPN 模式。
* 预览版本 `3.0.10-preview.13`，模块版本代码 `375`，APK 版本代码 `1875`，内核 `sing-box v1.15.0-alpha.12`。
* 交付 ARM64 APK、纯 Root 模块、内嵌同一 APK 的整合模块与 `SHA256SUMS`；预览渠道仍只保留最新一版。

### 本地验证

* `go build ./...`（windows 与 linux/arm64 双目标）、`go vet ./...` 全过。
* Go 测试 **94 通过**；失败的 8 个是 Windows 平台既有基线（`root_requires_android_or_linux` / `VmRSS`），与本次改动无关。
* App 单测 **357 通过 / 0 失败 / 0 跳过**；androidTest 编译通过。
* 新增 `TestConfigIntentNotPersistedWhenApplyFails` 做了变异自证：把落盘挪回 Apply 之前，该用例必然 FAIL。
* TLS 测试本地强制重跑（`--rerun-tasks`）两个测试类各 5 次，全部通过。
* **本版未做真机联调**，以上均为本地构建与自动化测试结论。

### 升级与自查

1. 安装 `wanbox-root-module-with-manager-arm64-v8a.zip` 后重启手机（`service.sh` 只在开机执行）。
2. 打开 App 等待 10 秒；若仍不可用，查看 `/sdcard/wanbox-serve.log` 与 `/data/adb/wanbox/delivery.log`。
3. 若日志出现 `profile_missing`，说明当前选中的节点还没进模块 store，App 会自动补写一次；持续出现请反馈节点来源（手工添加 / 订阅导入）。

### 已知问题

* 订阅拉取与节点 CRUD 仍未做真机联调。
* 跨用户 UID 暂缺，多用户/克隆应用的分流只覆盖当前用户。
* `RootModuleSnapshot` 仍用于收集资源文件，配置本体已不由它下发。
