# XHTTP Transport Specification

## Purpose

定义 XHTTP (SplitHTTP) 传输协议在 wanBoxForAndroid 中的运行契约，涵盖客户端出站的模式协商、流式上传下载、混淆填充与连接复用，以及服务端入站的模式装配、资源上限、请求校验与会话管理。出站需求源自 `support-xhttp-transport` 变更，入站需求源自 `support-xhttp-inbound` 变更（PR #90），两者于 2026-11-24 一并合入本主规范。

## Requirements

### Requirement: XHTTP 客户端传输模式与协议装配

XHTTP 客户端传输驱动 MUST 支持 `auto`、`packet-up`、`stream-up` 和 `stream-one` 四种工作模式，并遵循与 Xray 公开的 XHTTP 线格式一致的请求装配规则。在 `stream-up` 与 `stream-one` 模式下，驱动 MUST 使用 HTTP/2 分块流式传输；在 `packet-up` 模式下，驱动 MUST 将上行数据包装为离散 HTTP 请求。当模式设置为 `auto` 时，驱动 MUST 具备自动检测与模式探测能力。

#### Scenario: 建立 stream-up 传输流

- **GIVEN** VLESS 节点的 XHTTP 模式配置为 `stream-up`
- **WHEN** 客户端拨号发起连接
- **THEN** 客户端通过 HTTP/2 建立全双工或分块长连接流
- **AND** 上行与下行通道均按流式协议交互

#### Scenario: 建立 stream-one 传输流

- **GIVEN** VLESS 节点的 XHTTP 模式配置为 `stream-one`
- **WHEN** 客户端与服务端完成 TLS/Reality 握手并拨号
- **THEN** 客户端使用单 HTTP 请求双向流建立通道，不额外发起独立的下载请求

### Requirement: X-Padding 混淆与流特征保护

XHTTP 传输驱动 MUST 支持 Xray 兼容的 X-Padding 填充规则。当配置提供 `x_padding_bytes` 或相关混淆参数时，驱动 MUST 在 HTTP 头部或数据包载荷中注入指定范围的填充字节，以对齐服务端反审查探测特征。

#### Scenario: 发送带 Padding 的数据包

- **GIVEN** 节点配置指定了 `x_padding_bytes` 范围
- **WHEN** XHTTP 客户端发送握手请求或上行数据包
- **THEN** 请求中包含符合长度区间的伪随机填充
- **AND** 远端服务能够正确去除填充并还原真实载荷

### Requirement: xmux 连接复用管理

XHTTP 传输驱动 MUST 支持 `xmux` 连接复用选项，包括并发连接上限（`max_concurrency`）、最大连接数（`max_connections`）以及请求复用策略。复用管理 MUST 保证同一会话及连接生命周期在网络接口重置或关闭时安全释放，不得产生 goroutine 或文件描述符泄露。

#### Scenario: 并发请求复用底层 HTTP 连接

- **GIVEN** 节点启用了 `xmux` 连接复用且并发数在配置限制内
- **WHEN** 多个应用连接并发经由该节点出站
- **THEN** 底层复用已建立的 HTTP/2 连接
- **AND** 避免为每个出站流重复执行完整的 TLS/Reality 握手

### Requirement: XHTTP 服务端入站模式与协议装配

XHTTP 服务端传输驱动 MUST 支持 `auto`、`packet-up`、`stream-up` 和 `stream-one` 四种工作模式，并遵循与 Xray 公开的 XHTTP 线格式一致的请求装配规则。在 `stream-up` 与 `stream-one` 模式下，驱动 MUST 支持 HTTP/2 分块流式传输；在 `packet-up` 模式下，驱动 MUST 将上行数据作为离散 HTTP 请求接收。当模式设置为 `auto` 时，驱动 MUST 具备自动检测与模式探测能力。服务端 MUST 支持 TLS + HTTP/2（含 h2c）与 HTTP/1.1；TLS 协商出 `h3` 时 MUST 明确拒绝，不得静默降级。

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

- **GIVEN** TLS 握手协商出的应用层协议为 `h3`
- **WHEN** 服务端创建 XHTTP 传输实例
- **THEN** 构造直接失败并返回明确错误
- **AND** 不以 HTTP/1.1 或 HTTP/2 静默接管

### Requirement: 服务端资源上限

XHTTP 服务端 MUST 显式限制资源占用：会话数上限 1024、全局缓冲上限 64 MiB、每会话缓冲上限 16 MiB、缓冲帖子上限 256、单帖上限 16 MiB、并发上传上限 4、未连接会话 30 秒超时、填充上限 4096 字节、会话 ID 上限 128 字符。超限的配置 MUST 在服务端构造时直接拒绝启动，不得静默放行。

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
