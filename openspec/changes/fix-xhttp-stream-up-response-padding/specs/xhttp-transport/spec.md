## MODIFIED Requirements

### Requirement: XHTTP 服务端入站模式与协议装配

XHTTP 服务端传输驱动 MUST 支持 `auto`、`packet-up`、`stream-up` 和 `stream-one` 四种工作模式，并遵循与 Xray 公开的 XHTTP 线格式一致的请求装配规则。在 `stream-up` 与 `stream-one` 模式下，驱动 MUST 支持 HTTP/2 分块流式传输；在 `packet-up` 模式下，驱动 MUST 将上行数据作为离散 HTTP 请求接收。当模式设置为 `auto` 时，服务端 MUST 按请求形态自动区分。`stream-up` MUST 串行接续同一会话的上传，单条正常结束不得关闭整个上行。`sc_stream_up_server_secs` MUST 控制 stream-up 成功响应中 padding 保活的周期，而非上传读超时；省略时 MUST 使用 20–80 秒默认区间，设置为 `-1` 时 MUST 禁用响应 padding，其他显式值 MUST 为正数且区间有序。符合 Referer 兼容标记或已接受 obfs padding 的请求，其成功响应 MUST 写入 `x_padding_bytes` 区间内的重复 `X` padding，并按该秒数区间随机周期继续写入；上传结束或请求取消时 MUST 停止写入，且 MUST NOT 影响独立的上传滚动空闲截止时间。服务端 MUST 支持 TLS + HTTP/2（含 h2c）与 HTTP/1.1；TLS ALPN 配置含 `h3` 时 MUST 在构造期拒绝启动，不得静默降级。

每轮成功响应 padding 的 `Write` 与随后的 `Flush` MUST 共用一个独立的 5 秒响应写截止时间；成功 `Flush` 后 MUST 立即清除该截止时间。截止时间设置失败、写入错误或刷新错误时 MUST 停止保活协程。该截止时间 MUST NOT 替代或改变独立的上传滚动空闲截止时间。

#### Scenario: stream-up 响应按配置发送 padding 保活

- **GIVEN** 服务端配置 `sc_stream_up_server_secs` 为 `1-1`，并接受请求的 obfs padding
- **WHEN** stream-up 上传的成功响应保持打开
- **THEN** 响应会立即写入 `x_padding_bytes` 区间长度的重复 `X` padding，并每 1 秒继续写入
- **AND** 上传结束或请求取消后停止写入，上传读取的 30 秒默认空闲截止时间保持独立
- **AND** 每轮 padding 的 Write 与 Flush 共用一个独立 5 秒响应写截止时间，成功 Flush 后清除；期限设置失败、超时或写入/刷新错误后停止保活协程

#### Scenario: 显式关闭 stream-up 响应 padding 保活

- **GIVEN** 服务端将 `sc_stream_up_server_secs` 配置为 `-1`
- **WHEN** 服务端创建 stream-up 入站
- **THEN** 服务端接受该 Xray 兼容的禁用值并不发送周期性响应 padding
- **AND** 上传读取超时仍独立生效
