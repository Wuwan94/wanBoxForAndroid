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
