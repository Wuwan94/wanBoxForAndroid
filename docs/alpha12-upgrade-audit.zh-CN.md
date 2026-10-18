# sing-box alpha.12 升级与节点热切换审计（2026-10-18）

作者：@author 雾晚

## 基线与证据

- 基线为 `v3.0.10-preview.3`（tag 提交 `2df46b44341ca12f41216822584953d22cd01aa8`，2026-10-18 16:16 +08:00）。该提交比 `main`（`dee22283b`）领先 5 个提交，已包含 `SagerNet.kt`、`ProfileSelection.kt`、`ConfigurationFragment.kt` 的节点选择对账与列表差分更新，`nb4a.properties` 已是 `PRE_VERSION_NAME=3.0.10-preview.3`、`PRE_VERSION_CODE=365`。
- 源码获取：沙箱到 `github.com:443` 不稳定，`git clone` 超时，改用 jsDelivr 逐文件拉取 `main` 快照（1082 个源码文件，无 `.git` 历史）；涉及版本号与节点选择的文件再按 preview.3 提交单独核对。
- `buildScript/lib/core/get_source.sh` 是 `SINGBOX_VERSION` 的唯一读取点，CI 按该 tag 检出官方源码后 `replace` 到 `libcore/go.mod`。
- 目标内核 `v1.15.0-alpha.12`（2026-10-11 发布）。本地用 `go install github.com/sagernet/sing-box/cmd/sing-box@v1.15.0-alpha.12` 构建出真实二进制，用于配置校验，不是推测。
- 基线测试：`rootmodule` 的 `go build ./...`、`go vet ./...`、`go test ./...` 全部通过（`wanbox/module` 22.8s，`wanbox/module/cmd/wanboxctl`）。Go 工具链由 `GOTOOLCHAIN=auto` 自动取 1.25.0（`rootmodule/go.mod` 要求 1.25）。

## 版本升级结论

alpha.10 与 alpha.12 之间共 54 个提交，与 wanBox 相关的条目及判断：

| 官方变更 | 对 wanBox 的影响 | 处理 |
| --- | --- | --- |
| Add go TUN stack / Remove dependency on gVisor | 默认栈是否变化 | **已排除**。alpha.10 与 alpha.12 依赖的 `sing-tun` 中 `NewStack("")` 都返回 `NewGo`，默认栈未变，无需改配置 |
| Fix outbound group resolution and interruption for nested groups | `Proxy` → `Auto/Select` 是嵌套出站组 | 正向修复，热切换回读确认的行为不变 |
| Close idle connections of unreferenced outbounds | 切换后回收旧出站连接 | 符合预期；切换瞬间的短连接重建需真机观察 |
| Validate IP addresses and prefixes in JSON schema | 用户自定义配置可能被更严格拒绝 | 保留 `sing-box check` 作为保存前校验，不额外放宽 |
| Use Prefixable for ip_cidr rule options | 路由规则字段形态 | wanBox 生成的是字符串 CIDR，已通过 alpha.12 校验 |
| Implement fully functional auto redirect for Android | Root TUN `auto_redirect` | 保持 `auto_redirect=true`，不追加平台字段 |

### 实测校验

用 alpha.12 二进制校验仓库内的两份配置样例：

```text
sing-box check -c docs/config/local-proxy.json   -> exit 0
sing-box check -c docs/config/tun-fakeip.json    -> exit 0
```

两条弃用告警：

1. `independent_cache`（1.14 弃用，1.16 移除）。`ConfigBuilder.kt:742` 仍固定写 `true`。本次不删除：它是 DNS 缓存语义开关，直接去掉会改变缓存命中行为，应作为 1.16 迁移的独立改动并附验证。
2. `stack`（1.15 弃用，1.17 移除）。仅出现在**文档样例** `docs/config/tun-fakeip.json` 的 `"stack": "mixed"` 中；`ConfigBuilder.kt:771` 已是 `stack = null`，实际运行配置不输出该字段。样例属历史文件，未在本次修改。

## 节点切换闪烁的根因与修复

### 根因

现象是「切节点时上方按钮与节点名先变、随后弹回」，并非核心真的重启。链路如下：

1. 热切换本身是干净的：`rootmodule/node_select.go` 经 Clash API `PUT /proxies/proxy` 切换，再回读确认，全程不重建 TUN。
2. `supervisor.go` 在内存里持有启动快照得到的 `ProfileID`，并按 stats 节奏（约 5 秒）用 `jsonWrite(state.json)` 整体重写状态。
3. `SelectNode` 原先只读改写 `state.json`。supervisor 下一次 `persist()` 立刻把 `ProfileID` 覆盖回启动时的旧值。
4. App 的 `RootTunService` 每 2 秒采样 `dashboard`，`changedProfile` 触发 `cbSelectorUpdate`，节点名与勾选先新后旧再新——用户看到的就是闪烁。

### 修复

- 新增 `runtime/selection.json` 作为**热切换的运行态事实源**，写入 `{revision, profileId, profileName}`。
- `SelectNode` 先写该文件，再写 `state.json`（立即生效）。
- `supervisor.persist()` 与 `Runtime.Status()` 都从该文件合并，跨进程消除覆盖；`revision` 不匹配时自动失效，避免上一代选择污染新配置。
- `Supervise` 启动时清理该文件，以本次快照的选择为准。

App 侧同时加了一道防抖：`SamplingFailurePolicy` 要求连续 3 次（约 6 秒）采样失败才降级为 `Stopped`。真实停服走 `phase=stopped` 的正常响应，不经过异常分支，因此不会掩盖真实断开。

### 回归测试

新增 `rootmodule/selection_state_test.go`：

- `TestRuntimeSelectionSurvivesSupervisorPersist`：热切换后模拟 supervisor 用启动快照覆盖 `state.json`，合并结果与 `Status()` 都必须仍是新节点。
- `TestRuntimeSelectionIgnoresOtherRevision`：`revision` 不匹配的选择不得生效。

两项通过，`rootmodule` 全量测试仍全绿。

## 未验证项（必须在真机/CI 完成）

1. **App 未编译**：沙箱无 Android SDK，`RootTunService.kt`、`SamplingFailurePolicy.kt` 及 JVM 测试只做了静态审查，需 `./gradlew testOssDebugUnitTest` 与 `assembleDebug` 验证。
2. **内核未在设备运行**：`sing-box check` 只证明配置可解析，不证明 TUN/auto_redirect/iptables 在目标设备可用。
3. **闪烁是否消失**：需要在真实 ARM64 Root 设备上用录屏对照，至少覆盖：同组切换、跨组切换、连续快速点击、urltest 组回退。
4. **v3.0.10-preview.3 的既有真机缺口仍在**：该版本发布说明已注明未做模块挂载与覆盖安装实测，本次不改变这一结论。

## 下一步

P2/P3 的前提是模块侧存在**独立于服务生命周期的常驻宿主**。当前 `wanboxctl __internal supervise` 与服务同生共死，停止服务即无管理面，因此不能直接把 HTTPS API 挂在 supervisor 上。建议顺序：

1. 新增 `__internal serve` 常驻进程（单实例文件锁），承载 HTTP API 与订阅调度，由 `service.sh` 与开机流程拉起。
2. `config/api.json` 固定 loopback + 256 bit secret；每安装独立自签证书，客户端钉 SHA-256。
3. 端点先覆盖只读：`/v1/status`、`/v1/nodes`、`/v1/subscriptions`，再接 `POST /v1/node/select` 与 SSE `/v1/events`。
4. App 的 `RootModuleClient` 改为 OkHttp + 证书钉扎，保留 `su` 仅用于首次凭据交付（私有 Provider），之后不再执行 CLI。
