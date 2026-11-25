## Why

`support-xhttp-transport` 只覆盖了 VLESS XHTTP **客户端出站**，并在 design.md 里把「XHTTP 服务端 Inbound 驱动」明确列为 Non-Goal（当时移动端只需要 Client Outbound）。PR #90（VLESS XHTTP 服务端入站，+1820/−4）把这一块补上了：wanBox 现在可以作为 XHTTP 服务端接受 VLESS 入站连接。但代码先行、规范没跟上——仓库里没有任何一份 OpenSpec 变更或主规范条目描述服务端入站能力。按仓库治理规范，代码与规范必须同步，这份变更就是补上缺的那一块。

## What Changes

- **服务端入站驱动接入 (libcore)**:
  - 在 `libcore/protocol/vless/` 新增自定义 VLESS 入站（`inbound.go`），在 `libcore/box_include.go` 的 `nekoboxAndroidInboundRegistry` 中新增注册，非 XHTTP 传输委托官方 `singboxVLESS.NewInbound`。
  - 在 `libcore/protocol/vless/xhttp/` 新增 XHTTP 服务端传输实现（`server.go`），按 Xray 公开的 XHTTP 线格式处理握手与上下行。
  - 配置 `type: vless` + `transport: xhttp` 时走新代码；其他传输类型原样委托回官方实现，非 XHTTP 流量不受影响。
  - 完整支持 `auto`、`packet-up`、`stream-up`、`stream-one` 四种模式，支持 TLS + HTTP/2（含 h2c）与 HTTP/1.1；不宣称支持 HTTP/3，TLS ALPN 配置含 `h3` 时在构造期拒绝启动。
  - 服务端资源上限全部有数：会话全局 1024 / 每来源 IP 32，并发上传全局 64 / 每来源 IP 8 / 每会话 2，单帖 2 MiB、读入预算 24 MiB、队列全局 32 MiB / 每会话 4 MiB、缓冲帖子 256、未连接会话 30 秒超时、填充上限 4096 字节、会话 ID 上限 128 字符。
  - 校验收口：header/cookie 放置位置校验、路径匹配、会话 ID 与 Host 校验、CORS 预检处理。
- **既有客户端包两处小修（同 PR，属本仓库自有代码，非内核）**:
  - `xhttp/dialer.go`：原来先写 session 头再整体替换 Header，session 头会被冲掉，改为正确的写入顺序。
  - `xhttp/writer.go`：写后计数被消费后变小的问题修正。
- **规范与文档更新**:
  - 在 `openspec/specs/libcore-integration/spec.md` 中确立 VLESS-XHTTP 扩展入站的接入约束。
  - 新建 `openspec/specs/xhttp-transport/spec.md` 主规范，把 `support-xhttp-transport` 的客户端出站 delta 与本变更的服务端入站需求一并合入。
  - `support-xhttp-transport` 的 Non-Goal「XHTTP 服务端 Inbound 驱动」由本变更关闭。

## Capabilities

### New Capabilities
- `xhttp-transport`（扩展）：定义 XHTTP (SplitHTTP) 服务端入站协议规范，涵盖四种模式、TLS/h2 装配、资源上限、请求校验与会话管理。

### Modified Capabilities
- `libcore-integration`：明确扩展版 VLESS 入站通过 out-of-tree 注册处理 XHTTP，非 XHTTP 传输委托官方实现；保持官方 sing-box 源码树零侵入，`SINGBOX_VERSION` 与版本号文件不受影响。

## Impact

- **代码影响（PR #90 已合入，7 文件 +1820/−4）**:
  - `libcore/box_include.go`：注册自定义 VLESS 入站。
  - `libcore/protocol/vless/inbound.go`（新增）：扩展版 VLESS 入站实现。
  - `libcore/protocol/vless/xhttp/server.go`、`server_test.go`（新增）：XHTTP 服务端传输实现与测试。
  - `libcore/protocol/vless/xhttp/dialer.go`、`writer.go`（小修）：既有客户端包 bug 修复。
  - `libcore/vless_xhttp_inbound_test.go`（新增）：入站往返测试。
- **构建与依赖**:
  - `nb4a.properties` 中 `SINGBOX_VERSION` 官方内核基线保持不变；不改 sing-box 上游源码、不碰版本号文件。
  - 不引入外部二进制文件，不增加多进程复杂度。

## Verification （2026-11-24 复审实测）

- 用户提供的 CI run（actions/runs/45251550225）为 success。
- 本地复跑：xhttp 包 -race 测试、四种模式 + TLS/h2 + header/cookie 放置 + 普通 VLESS/WS 回归往返测试全部通过。
- Python 工具 125 项：本地一度有 1 项失败（`test_release_tag_check`），定位为 VM 的 /tmp（512 MB）写满导致临时目录不可用，清理后 125 项全过，与本 PR 无关。
- 本地全量 Go 测试未跑完：VM 的 /tmp（512 MB）被写满，环境限制；CI 侧全绿。

## Known Limitations （复审结论，非拦路项）

- 原始复审时只有 wanBox 客户端 ↔ wanBox 服务端自对自；后续已使用官方 Xray v26.11.9 完成双向四模式及 TLS、header/cookie 补测。复测发现并修复了 wanBox `stream-one` 客户端 session ID 问题；环境、命令与逐格结果见 [`docs/testing/xhttp-xray-interop.md`](../../../docs/testing/xhttp-xray-interop.md)。
- 尚未上真机跑过，Go 层通了不等于手机上全链路通。
- `inbound.go` 里 KTLS 判断条件是从上游抄来的死条件（此路径恒不成立，无害）。
- packet 乱序缓冲达到上限时会关闭会话并回收缓冲资源；极端乱序会使当前会话中断，客户端需重建会话，不会静默丢包（tasks.md §3.4）。
- 原始复审列出的后续项状态见 tasks.md 第 3 节；真实 Xray 互通与乱序溢出处理已完成，真机全链路任务仍未执行。
