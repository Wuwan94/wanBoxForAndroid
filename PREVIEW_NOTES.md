# wanBox for Android v3.0.10-preview.9 更新说明

本版本针对「模块已装、App 仍提示模块服务不可用」的现场反馈：让失败原因真正写进日志，并修掉 Android 13+ 上 root 向 App 私有目录写文件最常见的一处坑——文件属主对了、SELinux 分类没对上。

### 核心变更

* **失败原因必须进日志**：状态采样此前把异常整个吞掉，导出的日志里只剩下「模块服务暂时不可用」这句话。现在首次失败与之后每 20 次失败都会写入 `module status sample failed (#n): <错误码>`，可直接区分 `module_credentials_unavailable`（凭据没交付）、`module_api_http_404`（模块版本过旧）、`SSLHandshakeException`（证书钉扎不匹配）与连接被拒。
* **凭据文件对齐 App 的 SELinux 上下文**：Android 13+ 上仅靠 `restorecon` 往往拿不到 MLS 分类，文件即使 `chown` 到 App uid、权限 0600，App 读取仍会被 SELinux 拒绝。现在读取 App 数据目录的精确上下文并用 `chcon` 对齐凭据文件。
* **交付重试窗口缩短**：App 首次运行才会创建接收目录，因此启动后前 5 分钟改为每 20 秒补交付一次，之后每分钟一次（原来固定 5 分钟，首次打开 App 后最长要等 5 分钟才连上）。
* **交付结果落盘**：每次交付写入 `/data/adb/wanbox/delivery.log`（含目标路径与失败原因），root 侧可直接查看，不用再猜。
* 凭据交付、端口退避、三路兜底读取、传输重试、节点迁移与热切换逻辑保持不变。

### 兼容性与产物

* 包名 `com.lit008834.pixel.wanboxforandroid`、原签名、数据库与备份格式未变，可直接覆盖 v3.0.9 与 v3.0.10-preview.5 ~ preview.8。
* 仅 arm64-v8a、Android 12+、Root 环境；仍为纯 Root 模块，不提供 VPN 模式。
* 预览版本 `3.0.10-preview.9`，模块版本代码 `371`，APK 版本代码 `1855`，内核 `sing-box v1.15.0-alpha.12`。
* 交付 ARM64 APK、纯 Root 模块、内嵌同一 APK 的整合模块与 `SHA256SUMS`；预览渠道仍只保留最新一版。

### 升级后如何自查

1. 打开 App 等 30 秒后导出日志；若仍失败，日志里会出现 `module status sample failed (#1): <错误码>`。
2. Root 终端执行 `cat /data/adb/wanbox/delivery.log`，能看到三个交付目标各自成功或失败的原因。
3. `grep versionCode /data/adb/modules/wanbox/module.prop` 确认模块版本为 `371`（预览版模块版本号）。

### 验证边界

* 模块侧本地通过 `go build`（windows 与 linux/arm64）、`go vet`；App 侧本地通过 `compilePreviewDebugKotlin`。
* **未在真实 ARM64 Root 设备验证**：`chcon` 在 HyperOS / Android 16 上的行为、`ls -Zd` 输出格式、交付日志写入权限，均需真机确认。
* 本地单元测试受 Windows 测试 JVM 启动失败影响未能执行，由 CI 的 `testPreviewDebugUnitTest` 把关。

### 已知问题

* 订阅拉取与节点 CRUD 仍未做真机联调。
* 跨用户 UID 暂缺，多用户/克隆应用的分流只覆盖当前用户。
* `RootModuleSnapshot` 仍用于收集资源文件，配置本体已不由它下发。
