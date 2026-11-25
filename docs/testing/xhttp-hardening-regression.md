# XHTTP 入站请求级回归验证

本记录补充 PR #94 的请求级证据。测试在 XHTTP 加固实现之后补写；旧基线失败与修复分支通过是事后对照，不是原始实现前完成 TDD 的证明。

## 环境与边界

- 旧基线：`b2eae57dcec9354dfcacb12f212a3393ede06d2b`，独立 detached worktree。
- 被测实现：PR #94 的 `d8b16c32da52a214cae702739196d13b32798d8a`；本次只追加测试与本记录，没有再改生产代码。
- 相同的 `libcore/protocol/vless/xhttp/request_regression_test.go` 直接复制到两个工作区，无旧基线编译适配或行为补丁。
- 使用 Linux loopback 上的真实 HTTP/1.1 TCP 连接及传输层回显处理器，不访问外部服务，不把传输层回显称为真实 Xray 客户端互通或完整 UUID 认证测试。
- 四条空闲 POST 发送完整请求头、声明一个字节的正文，但不发送正文、不关闭写端或连接。读入通知只用来同步已进入正文读取，断言来自 HTTP 响应和回显字节。
- 仅超时测试把服务端实际请求设置的非零读截止时间缩短到最多 2 秒；未主动设置截止时间的旧实现不会被测试夹具补上超时。此测试验证实际 socket 超时、408 和配额释放，不声称等待了生产默认的整整 30 秒。其他测试不缩短上传截止时间。
- 来源隔离通过 `net.Dialer.LocalAddr` 分别绑定 `127.0.0.2`、`127.0.0.3`，不是伪造转发请求头或修改会话计数器。

## 同一测试的旧基线与修复分支对照

| 测试 | 旧基线的实际失败 | 修复后的断言 |
| --- | --- | --- |
| `TestIdleUploadsDoNotBlockOtherSession` | 四条 POST 已进入读取；另一会话合法上传返回 429 | 另一会话返回 200，并收到完整 `healthy` 回显 |
| `TestIdleUploadTimeoutReleasesSessionSlots` | 4 秒客户端观察窗口内读不到 408，socket 读超时 | 两条空闲上传都由服务端返回 408；不关闭旧上传写端，原会话两槽能再次同时接收正文，返回 200 并回显 `ab` |
| `TestSessionQuotaIsolatesRealPeerSources` | 同一来源第 33 个会话返回 200 | 前 32 个实际下行保持打开，第 33 个返回 429；另一实际来源新建会话，上传 200 并收到完整回显 |
| `TestSessionRejectsAnotherRealPeer` | 非原来源对已有会话上传返回 200 | 非原来源返回 403；原来源继续上传 200 并收到回显 |
| `TestSessionRejectsSecondDownlink` | 同会话第二个 GET 返回 200 | 第二个 GET 返回 409；第一个下行仍能接收回显 |
| `TestPacketOverflowTerminatesHTTPDownlink` | 缺失序号 0、超出单包乱序预算后，GET 持续等待直至客户端 6 秒超时 | 溢出会话的下行及时结束，不输出缺失前缀后的数据；第二个上传允许已经入队的 200 或并发关闭产生的 409 |

基线测试命令以退出码 1 结束，六项均为行为断言失败而不是构建失败：

```sh
go -C ../wan319-request-baseline/libcore test -count=1 -v \
  ./protocol/vless/xhttp \
  -run 'Test(IdleUpload|SessionQuotaIsolatesRealPeerSources|SessionRejects|PacketOverflowTerminatesHTTPDownlink)'
```

修复分支的重复和竞态验证：

```sh
go -C libcore test -count=10 ./protocol/vless/xhttp \
  -run 'Test(IdleUpload|SessionQuotaIsolatesRealPeerSources|SessionRejects|PacketOverflowTerminatesHTTPDownlink)'
go -C libcore test -race -count=3 ./protocol/vless/xhttp
```

这些回归将随三份工作流既有的 XHTTP 普通测试及 race 门禁一起执行。真机、真实 Xray 客户端、HTTP/2 下的本组请求级资源隔离，以及物理设备峰值内存，仍需各自独立验证。


## PR-A：stream-up 响应 padding 保活

基线复核（`040efef08cd1a6952b3b6bb9908c7a856f3c0d4c`）确认配额隔离、同会话 stream-up 排队、认证回调回收、乱序溢出关会话、uplink method 校验以及 XHTTP JSON 示例门禁已存在；本 PR 不重复改写这些行为。新增回归使用公开 `NewServer`、`Serve(net.Listener)`、`Close()` 和真实 loopback HTTP 请求，不读取私有会话表或准入计数。

- 正向保活 RED：未修改基线执行 `go test -count=1 -run '^TestStreamUpResponseWritesConfiguredKeepalivePadding$' ./protocol/vless/xhttp`，在 `NewServer` 处失败，错误为 `xhttp sc_stream_up_server_secs is not supported on the server`。
- 正向保活 GREEN：同一测试通过；配置 `1-1` 秒、固定 4 字节 padding，成功响应收到连续两段 `XXXX`，证明是周期写入而非单次写入。
- 禁用哨兵 RED/GREEN：先支持正数区间后，新增 `-1` 禁用用例在构造期因“必须为正数区间”失败；加入精确 `-1/-1` 禁用语义后，`go test -count=1 -run '^TestStreamUpResponse(KeepaliveCanBeDisabled|WritesConfiguredKeepalivePadding)$' ./protocol/vless/xhttp` 通过，禁用情况下成功响应正文为空。
- 上传读取仍由独立的滚动空闲截止时间处理，默认 30 秒；`sc_stream_up_server_secs` 只选响应 padding 保活间隔。字段缺省为 20–80 秒；只有 Referer 兼容标记或服务端接受 obfs padding 时启用；上传结束/请求取消后写协程停止并退出。每轮 padding 的 `Write` 与随后的 `Flush` 共用独立 5 秒响应写截止时间，成功 Flush 后清除；期限设置、写入或刷新失败会停止保活协程。

Xray 官方 [`hub.go`](https://github.com/XTLS/Xray-core/blob/main/transport/internet/splithttp/hub.go) 与 [`config.go`](https://github.com/XTLS/Xray-core/blob/main/transport/internet/splithttp/config.go) 是线格式与默认值依据。Xray [issue #7180](https://github.com/XTLS/Xray-core/issues/7180) 报告 HTTP/1.1 Nginx 上游可能受响应保活影响；`-1` 可作为诊断/兼容性开关，但会放弃响应保活带来的空闲保护。PR-A 未在 Nginx 反代或 Root 真机上复现/确认该权衡，不能将本地 loopback 测试当作该场景证据。

### GPT/Codex 补充测试

Codex CLI `0.172.0` 使用模型 `gpt-6.1-sol`，该模型实际编写了 `server.go` 中独立 `defaultXHTTPStreamUpResponseWriteTimeout = 5 * time.Second` 与每轮 `ResponseController.SetWriteDeadline`/清理逻辑，并新增了 `TestStreamUpResponseKeepaliveBlockedWriteStopsOnUploadEOF`、`TestStreamUpResponseKeepaliveLegacyRefererCompatibility`、`TestStreamUpResponseKeepaliveNotActivatedWithoutRefererInNonObfsMode`、`TestStreamUpResponseKeepaliveStopsOnUploadEOF`；此前还补充了 `TestStreamUpResponseKeepaliveStopsOnRequestCancellation`。主环境先运行阻塞写用例并在 2 秒后观察到响应 drain 仍阻塞（RED），应用 deadline 修复后该测试普通与 `-race` 均通过，完整 XHTTP 包普通与 `-race -count=3` 也通过。测试通过真实 loopback HTTP 与 test-only `net.Conn` wrapper：无 deadline 时 write 阻塞，安装未来 deadline 后模拟 `os.ErrDeadlineExceeded`；仅验证 deadline 设置及 handler 收尾，不代表真实慢网/反代上发生了 5 秒超时。Codex 沙箱没有运行这些测试。

本轮由当前助手补充了 `TestStreamUpResponseKeepaliveDefaultIntervalRange`、`TestStreamUpResponseKeepaliveStopsWhenWriteDeadlineSetFails`，将 EOF 用例改为使用缺省 20–80 秒配置，并加强阻塞写用例以确认 Flush 错误后不会开始下一轮；这些后续补测并非 Codex 编写。

本地 Go race、完整 XHTTP 回归、`tools/` 测试和治理门禁结果由本次 PR-A 任务清单继续记录；本地复审和提交后推送分支、创建 PR 并运行 GitHub Actions `Verify Android Build`（包括 12 项 Xray v26.11.9 互通硬门禁）。本任务禁止合并或发布；CI 与 Android 构建产物检查在 PR 运行完成前仍属待验证。
