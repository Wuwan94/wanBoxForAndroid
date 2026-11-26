## Why

`origin/main` 已具备按来源 IP 与会话隔离的会话/上传配额、stream-up 排队续接、认证回调清理、乱序溢出关闭，以及 XHTTP 入站示例和检查门禁；但 `NewServer` 仍拒绝所有非零 `sc_stream_up_server_secs`。这与 Xray 的 stream-up 协议语义不符，也把响应 padding 保活错误地等同于不支持的服务端功能。

## What Changes

- `sc_stream_up_server_secs` 控制 stream-up 成功响应的周期性 padding 保活：缺省采用 20–80 秒，正数有序区间按秒随机取值；精确 `-1` 显式关闭响应 padding，其余无效或乱序区间在构造时拒绝。
- 仅在请求带 Referer 兼容标记或服务端已接受 obfs padding 时发送响应保活。成功响应先写入 `x_padding_bytes` 范围长度的重复 `X`，随后按间隔周期写入；每轮 padding 的 `Write` 与随后的 `Flush` 共用独立 5 秒写截止时间，成功 Flush 后清除，设置失败或写入/刷新错误即停止保活协程；上传结束或请求取消后也停止并等待写协程退出。
- 上传正文滚动空闲截止时间独立维持默认 30 秒，不由该参数控制。
- 更新 XHTTP 入站示例、中文配置文档、OpenSpec 主规范和本变更契约。

## Capabilities

### Modified Capabilities

- `xhttp-transport`: 明确 stream-up 响应 padding 保活、默认/禁用语义、请求条件和独立上传读超时。

## Impact

- 生产代码：`libcore/protocol/vless/xhttp/server.go`。
- 回归测试：通过公开 `NewServer`、`Serve(net.Listener)`、`Close()` 与真实 loopback HTTP 请求验证响应行为，不读取私有会话表或配额计数。
- 文档：`docs/config/xhttp-inbound.json`、`docs/config/README.zh-CN.md`、`docs/testing/xhttp-hardening-regression.md` 与 OpenSpec。
- 不改 `nb4a.properties`、Android UI、Root 模块、Release/Tag 或 `Wuwan94.github.io`；资源隔离等已在指定基线实现的行为不重复改写。

## Verification

- 正向保活与 `-1` 禁用均先新增真实 TCP 行为测试；实现前分别因不支持配置/拒绝禁用区间而失败，随后通过。
- 本地 Go race、XHTTP 请求级回归、`tools/` 单测和治理门禁结果写入 tasks.md 与测试说明。
- 本地复审和提交后推送分支并创建 PR，以运行 `Verify Android Build`（包括 12 项 Xray v26.11.9 互通硬门禁）；不得合并或发布。本地结果不替代该 CI。此改动不改变 Root 模块行为，Root 专项矩阵不适用；Android 真机网络链路仍未由本地测试证明。

## Rollback

回退本 PR 的提交即可恢复基线行为（服务端拒绝非零 `sc_stream_up_server_secs`）；同时回退新增测试、示例字段、中文说明和 OpenSpec delta。基线已有的会话/上传配额、排队和溢出关闭逻辑不在本变更回退范围内。
