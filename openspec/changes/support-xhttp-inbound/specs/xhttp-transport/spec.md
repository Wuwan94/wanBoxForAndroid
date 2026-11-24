## Purpose

定义 XHTTP (SplitHTTP) 服务端入站协议在 wanBoxForAndroid 中的运行契约，涵盖模式协商、流式上下行、资源上限、请求校验及会话管理，与已有的客户端出站需求互补。

## ADDED Requirements

### Requirement: XHTTP 服务端入站模式与协议装配

XHTTP 服务端传输驱动 MUST 支持 `auto`、`packet-up`、`stream-up` 和 `stream-one` 四种工作模式，并遵循与 Xray 公开的 XHTTP 线格式一致的请求装配规则。在 `stream-up` 与 `stream-one` 模式下，驱动 MUST 支持 HTTP/2 分块流式传输；在 `packet-up` 模式下，驱动 MUST 将上行数据作为离散 HTTP 请求接收。当模式设置为 `auto` 时，服务端 MUST 按请求形态自动区分。`stream-up` MUST 串行接续同一会话的上传，单条正常结束不得关闭整个上行；暂不支持非零 `sc_stream_up_server_secs`，构造期 MUST 明确拒绝。服务端 MUST 支持 TLS + HTTP/2（含 h2c）与 HTTP/1.1；TLS ALPN 配置含 `h3` 时 MUST 在构造期拒绝启动，不得静默降级。

#### Scenario: 建立 stream-up 入站流
- **GIVEN** VLESS 入站的 XHTTP 模式配置为 `stream-up`
- **WHEN** XHTTP 客户端发起连接
- **THEN** 服务端通过 HTTP/2 建立全双工或分块长连接流
- **AND** 上行与下行通道均按流式协议交互

#### Scenario: 建立 packet-up 入站流
- **GIVEN** VLESS 入站的 XHTTP 模式配置为 `packet-up`
- **WHEN** XHTTP 客户端发送离散上行请求
- **THEN** 服务端逐个接收并按序组装上行数据
- **AND** 下行通道独立返回

#### Scenario: 拒绝 HTTP/3 协商
- **GIVEN** 服务端配置的 TLS ALPN 列表含 `h3`
- **WHEN** 服务端创建 XHTTP 传输实例
- **THEN** 构造直接失败并返回明确错误
- **AND** 不以 HTTP/1.1 或 HTTP/2 静默接管

### Requirement: 服务端资源上限

XHTTP 服务端 MUST 显式限制资源占用：会话上限为全局 1024、每来源 IP 32；上传并发上限为全局 64、每来源 IP 8、每会话 2；单帖上限 2 MiB，读入预算 24 MiB，队列预算为全局 32 MiB、每会话 4 MiB；缓冲帖子上限 256，未连接会话 30 秒超时、填充上限 4096 字节、会话 ID 上限 128 字符。超限的配置 MUST 在服务端构造时直接拒绝启动，不得静默放行。上传读取 MUST 有滚动空闲截止时间；会话按直连来源 IP 绑定，同一会话只允许一个下行读取端。VLESS 认证失败或会话异常关闭时 MUST 及时回收资源；乱序缓冲溢出 MUST 终止会话，不得静默丢包。

#### Scenario: 超限配置拒绝启动
- **GIVEN** 入站配置的填充或单帖上限超出服务端允许范围
- **WHEN** 服务端构造传输实例
- **THEN** 构造失败并返回明确的校验错误

#### Scenario: 未连接会话超时释放
- **GIVEN** 会话已创建但 30 秒内未建立连接
- **WHEN** 超时到达
- **THEN** 会话被释放并回收其缓冲
- **AND** 全局缓冲计数随之扣减

### Requirement: 服务端请求校验与元数据放置

XHTTP 服务端 MUST 校验入站请求的 header/cookie 元数据放置位置、请求路径、会话 ID 与 Host，非法请求 MUST 被拒绝；MUST 正确处理 CORS 预检请求。

#### Scenario: 非法元数据放置被拒绝
- **GIVEN** 请求的 header/cookie 放置位置不符合配置
- **WHEN** 服务端收到该请求
- **THEN** 请求被拒绝并返回明确的错误状态

#### Scenario: CORS 预检得到正确响应
- **GIVEN** 浏览器或客户端发起 CORS 预检请求
- **WHEN** 服务端收到预检
- **THEN** 返回符合 CORS 约定的响应头
- **AND** 不建立代理会话
