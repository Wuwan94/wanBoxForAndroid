## Context

指定基线 `040efef08cd1a6952b3b6bb9908c7a856f3c0d4c` 中，XHTTP 服务端已实现全局/每来源 IP/每会话配额，stream-up 同会话排队，认证失败回调释放会话、乱序缓冲溢出显式关闭，以及 `docs/config/xhttp-inbound.json` 和 sing-box 检查。剩余协议差异集中在 `NewServer` 拒绝非零 `sc_stream_up_server_secs`，且成功的 stream-up 响应没有 padding 保活。

官方参考：Xray [`hub.go`](https://github.com/XTLS/Xray-core/blob/main/transport/internet/splithttp/hub.go) 在 stream-up 成功响应后写入重复 `X`，按 `ScStreamUpServerSecs` 随机秒数休眠后继续；[`config.go`](https://github.com/XTLS/Xray-core/blob/main/transport/internet/splithttp/config.go) 的缺省区间为 20–80 秒。官方实现只在 Referer 兼容标记或 obfs padding 被接受时开启。该配置不是上传 body 的 idle timeout。

## Goals / Non-Goals

**Goals**
- 对齐成功 stream-up 响应的周期性 padding 行为和 Xray 默认区间。
- 保留 Xray `-1` 禁用响应 padding 的开关，以便反代兼容性诊断；不改变上传读取超时。
- 通过服务构造和真实 loopback TCP 的公开行为验证配置接受、重复响应 padding、禁用效果和资源清理。
- 同步中文说明、入站示例和规范。

**Non-Goals**
- 不重做基线已实现的会话/上传配额、stream-up 排队、认证回调清理、方法白名单或乱序溢出关闭。
- 不修改 Xray、sing-box、`nb4a.properties`、Root 模块、Android UI、Release/Tag 或网站。
- 不声称本次已验证 Nginx/HTTP/1.1 反代、大文件或 Root 真机矩阵。

## Decisions

### 1. 配置归一化与验证

- `0-0`（字段省略）沿用 `GetNormalizedScStreamUpServerSecs()` 的 20–80 秒缺省值。
- 正数且有序的显式范围可用；精确 `-1/-1` 禁用响应 padding。其他显式负值、零起始范围和乱序范围在 `NewServer` 构造期拒绝，避免零秒忙循环或误用缺省值。
- `sc_stream_up_server_secs` 只选择响应保活间隔；`deadlineBody` 继续独立使用 `defaultXHTTPUploadReadTimeout`（30 秒）。

### 2. 响应 padding 生命周期

- stream-up 发送成功状态并 Flush 后，在符合 Referer 兼容标记或 obfs padding 已配置并通过请求校验时，启动单个写协程。
- 每轮生成 `x_padding_bytes` 范围长度的 `X`，写入并 Flush；再按配置的秒数随机区间等待。请求取消或 upload handler 结束时关闭 stop channel 并等待 done，确保不在 `ServeHTTP` 返回后访问 `ResponseWriter`。
- 每轮 padding 的 `Write` 与随后的 `Flush` 共用一个独立的 5 秒响应写截止时间；成功 `Flush` 后立即清除，协程退出时也尝试清除。期限设置、写入或刷新失败时停止保活协程，避免 handler 在同步等待时被永久阻塞。
- 正常的单条 upload EOF 仍只释放该队列槽位；不关闭同一会话的上行管道，下一条 stream-up 可继续。

### 3. HTTP/1.1 反代风险

Xray [issue #7180](https://github.com/XTLS/Xray-core/issues/7180) 报告 stream-up 服务端响应 keepalive padding 与 HTTP/1.1 Nginx 上游交互时可能出现大上传截断；报告者观察到 `-1` 禁用保活可规避其复现，但会牺牲该响应空闲保护，HTTP/2 后端也通过其测试。本实现保留 `-1` 诊断/兼容开关，不声称复现已由本次解决；需在目标反代链路做独立验证。

## Risks / Trade-offs

- 默认 20–80 秒保活与 Xray 对齐，但 HTTP/1.1 中间代理行为可能不同；遇到相似现象可测试 `-1` 或 HTTP/2 后端，并评估空闲保护损失。
- 定时器按请求生命周期运行；通过停止并等待写协程结束避免 ResponseWriter 生命周期越界。若客户端不消费响应、代理强制缓冲或限流，真实反代兼容性仍需 CI/环境验证。
- 5 秒响应写截止时间是保活写入的独立安全上限；慢客户端或反代流控持续超过该时间时会停止 padding 协程，不改变独立的 30 秒上传读空闲截止时间。
- `-1` 仅禁用响应 padding，不会禁用或延长上传空闲读截止时间。
