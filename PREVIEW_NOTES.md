# wanBox for Android v3.0.10-preview.12 更新说明

本版本把「模块装好了但 App 提示模块服务不可用」这条链路彻底收口，并堵掉一个会**静默换节点**的缺陷：用户选 A、实际流量走 B，界面上还完全看不出来。

### 核心变更

* **删除与钉扎实现冲突的 `CertificatePinner`**：模块下发的是 DER 的 SHA-256（小写十六进制），OkHttp 的 `CertificatePinner` 要的却是 Base64 SPKI，两套格式混用会无条件拒绝连接。现在只保留 `PinnedTrustManager` 做 DER 比对。
* **协议版本兼容**：模块 HTTP 响应写 `schema`、历史快照写 `schemaVersion`，两侧都接受；冲突、未知或类型错误一律 `module_protocol_mismatch`，不再误判。
* **禁止静默换节点**：显式指定的 profile 不在模块节点集合时如实报 `profile_missing`，不再悄悄挑另一个节点顶上。只有 App 未指定时才允许回退到第一个可选节点。
* **wireguard 端点一次性迁移**：preview.11 的迁移把 wireguard 端点当成普通出站存了下来，重新组装配置时它会写进 `outbounds`，而 sing-box ≥ 1.11 只允许 wireguard 出现在顶层 `endpoints`，`rootbox --check` 必失败、核心起不来。现在读取时回填端点标记并落盘（store `schemaVersion` 1→2，只跑一次，不覆盖用户手工改动）。
* **端点节点恢复可选**：wireguard 这类端点只是必须写在顶层 `endpoints`（内核要求它不能进 `outbounds`），并不代表不能选——内核的 selector 完全可以引用 endpoint 的 tag。之前把端点一律排除在 selector 候选之外，等于 wireguard 节点迁移后依然选不了。现在端点的 tag 正常进 selector 候选与热切换映射。
* **热更新停服务的顺序修正**：原流程先重命名模块目录、再按原可执行路径核验进程，此时 `/proc/<pid>/exe` 已指向备份目录，身份校验必然不匹配——旧管理面停不掉，新管理面又抢不到 `api.lock`，热更新后 App 直接失联。现在停管理面严格早于目录替换。
* **补上 preview.11 的升级兼容路径**：旧版管理面不写 `api.pid`，热更新无从知道它的 pid。现在按「可执行文件是本模块的 `wanboxctl` 且命令行是 `__internal serve`」两条硬条件反查，确认身份后再停，绝不按进程名或裸 pid 杀进程。
* **App 只补写缺失的那一条**：模块已有节点但缺当前 profile 时，用单条 `POST /v1/nodes` 补写，绝不整表回写——否则用户在模块里新增/编辑过的节点会被 App 的旧配置冲掉。
* **配置意图依赖保真**：路由/DNS 引用的 direct/bypass 出站、链式 detour、顶层 `endpoints`、顶层 `log` 全部参与拼装，缺一样真实核心校验就会失败。
* **管理服务生命周期**：`api.pid` 记录 pid + 启动刻度 + 可执行路径，身份不符绝不发信号、也不删别人的记录；`serve-stop` 无记录即成功（幂等）；热更新与卸载都按 `serve-stop → stop` 的顺序停进程，不再把 serve 留在后台。
* **Store 改为 Runtime 单例**：之前并发请求各持一把锁，写 nodes/config 会互相覆盖。
* 客户端侧：超时分档（15s / 300s / 流式 0）、强制不走系统代理、SSE 可取消、缓存键纳入证书指纹、凭据日志脱敏。

### 兼容性与产物

* 包名 `com.lit008834.pixel.wanboxforandroid`、原签名、数据库与备份格式未变，可直接覆盖 v3.0.9 与 v3.0.10-preview.11。
* 仅 arm64-v8a、Android 12+、Root 环境；仍为纯 Root 模块，不提供 VPN 模式。
* 预览版本 `3.0.10-preview.12`，模块版本代码 `374`，APK 版本代码 `1870`，内核 `sing-box v1.15.0-alpha.12`。
* 交付 ARM64 APK、纯 Root 模块、内嵌同一 APK 的整合模块与 `SHA256SUMS`；预览渠道仍只保留最新一版。

### 本地验证

* `go build ./...`（windows 与 linux/arm64 双目标）、`go vet ./...` 全过。
* Go 测试 90 通过；失败的 8 个是 Windows 平台既有基线（`root_requires_android_or_linux` / `VmRSS`），与本次改动无关。
* App 单测 **357 通过 / 0 失败 / 0 跳过**。
* 新增用例做了变异自证：失败也落盘、静默回退、跳过 wireguard 迁移、随便拿节点顶上 —— 4/4 均被用例抓到，不是写了就绿的摆设。
* **本版未做真机联调**，以上均为本地构建与自动化测试结论。

### 升级与自查

1. 安装 `wanbox-root-module-with-manager-arm64-v8a.zip` 后重启手机（`service.sh` 只在开机执行）。
2. 打开 App 等待 10 秒；若仍不可用，查看 `/sdcard/wanbox-serve.log` 与 `/data/adb/wanbox/delivery.log`。
3. 若日志出现 `profile_missing`，说明当前选中的节点还没进模块 store，App 会自动补写一次；持续出现请反馈节点来源（手工添加 / 订阅导入）。

### 已知问题

* 订阅拉取与节点 CRUD 仍未做真机联调。
* 跨用户 UID 暂缺，多用户/克隆应用的分流只覆盖当前用户。
* `RootModuleSnapshot` 仍用于收集资源文件，配置本体已不由它下发。
