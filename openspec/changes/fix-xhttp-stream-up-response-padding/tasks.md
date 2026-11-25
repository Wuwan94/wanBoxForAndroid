## 1. stream-up 响应 padding 保活协议收口

- [x] 1.1 先新增公开网络行为测试：通过 `NewServer`、`Serve(net.Listener)`、普通 loopback HTTP client 和 `Close()` 验证周期 padding；另验证 `-1` 禁用值。
- [x] 1.2 实现缺省 20–80 秒、正数有序区间和精确 `-1` 禁用；保活协程在上传 handler 结束/取消时停止并等待；每轮 padding 的 `Write` 与随后的 `Flush` 共用独立 5 秒响应写截止时间，成功 Flush 后清除；上传读空闲超时保持独立。
- [x] 1.3 更新入站 JSON 示例、中文配置说明、OpenSpec 主规范与本变更 delta；记录 HTTP/1.1 反代风险和回退说明。
- [x] 1.4 完成本地相关验证：
  - `libcore`：`go test -count=1 ./protocol/vless/xhttp` 与 `go test -race -count=3 ./protocol/vless/xhttp` 均通过；`go test -race -count=1 -run '^TestVLESSXHTTPInbound' .`，以及带 `with_clash_api,with_gvisor,with_quic,with_wireguard,with_utls` 标签的根包普通/race 测试也通过。
  - PR-A 生命周期回归：阻塞写测试在加 deadline 前 RED（2 秒后响应 drain 仍阻塞），加期限后普通与 `-race` 通过；本轮增加缺省 20–80 秒归一化、deadline 设置失败退出、Flush 错误后不重试断言。`TestStreamUpResponseKeepaliveLegacyRefererCompatibility`、`TestStreamUpResponseKeepaliveNotActivatedWithoutRefererInNonObfsMode`、`TestStreamUpResponseKeepaliveStopsOnUploadEOF` 等均通过；完整 XHTTP 包 `go test -count=1` 与 `go test -race -count=3` 均通过。
  - CI 相关包：`go test -count=1 ./internal/... ./protocol/urltest ./protocol/loadbalance ./protocol/vless/xhttp` 与对应 `urltest/loadbalance/xhttp` race 测试通过；`protect.go`、`log.go`、`tun_source.go` 文件级 race 测试通过。
  - 工具与治理：`tools/` 单测 132 项通过；`tools/pre-push-check.py`、默认 `tools/check_core_gate.py`（344 个源文件）、`tools/check_current_version_docs.py`、`tools/check_keep_features.py`、XHTTP 示例投影及 `git diff --check` 通过。`rootmodule` Go race、`go vet` 与 Python 单测 28 项通过。
  - 配置与协议互通：此前以干净的 reF1nd sing-box `v1.15.0-alpha.13-reF1nd`（`17397d022647b73378705e1f91ff031dddaaec21`）通过 local-proxy、tun-fakeip、XHTTP 三个 `sing-box check`；`bash tools/verify_xhttp_xray_interop.sh` 的 Xray v26.11.9 12/12 子用例通过。上述两类门禁在本轮补测后未重跑。
  - 限制/说明：Android `check_android_security.py` 需要先构建 `ossDebug`、`previewDebug` 产物，本 worktree 没有这些 APK/manifest；可选 `check_core_gate.py --all` 会误报 4 条既有测试中的 `Libcore.` 源码断言文本；CI 默认检查范围通过。`check_keep_features.py` 有既有规则库资产未落盘提示但检查通过。`openspec` CLI 未安装，规范以文件内容人工核对。
- [ ] 1.5 本地双轴复审与最终 diff 检查已通过；创建 Conventional Commit 后推送分支、开 PR，并确认 PR CI 通过。本任务授权这些操作，但禁止合并或发布。
- [ ] 1.6 PR 触发 GitHub Actions `Verify Android Build`（含 Xray 互通门禁）后补记结果；`check_android_security.py` 仍需有 `ossDebug`、`previewDebug` manifest/产物后执行。当前尚无 PR/CI 结果，不得标记为通过。
- [x] 1.7 Root 专项矩阵不适用：本变更只改 libcore XHTTP 入站，不改 Root 模块；Root 模块 Go/Python 本地测试和 vet 已通过。Android 真机端到端网络行为仍未由本地测试证明。
