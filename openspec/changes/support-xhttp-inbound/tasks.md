## 1. 实现并注册扩展版 VLESS 入站 (libcore) —— PR #90 已完成

- [x] 1.1 在 `libcore/protocol/vless/` 新增自定义 VLESS 入站实现（`inbound.go`），`transport.type == "xhttp"` 时接入 XHTTP 服务端传输，其他传输类型委托官方实现
- [x] 1.2 在 `libcore/box_include.go` 的 `nekoboxAndroidInboundRegistry` 中注册自定义 VLESS 入站，覆盖官方默认注册
- [x] 1.3 在 `libcore/protocol/vless/xhttp/` 实现 XHTTP 服务端传输（`server.go`），支持 `auto`、`packet-up`、`stream-up`、`stream-one` 四种模式，TLS + HTTP/2（含 h2c）与 HTTP/1.1，HTTP/3 明确拒绝；资源上限（会话 1024、全局缓冲 64 MiB、并发上传 4、未连接会话 30 秒超时）与请求校验（header/cookie 放置、路径、会话 ID、Host、CORS 预检）收口
- [x] 1.4 修掉共用客户端包里的两处既有 bug：`xhttp/dialer.go` session 头写入顺序、`xhttp/writer.go` 写后计数
- [x] 1.5 编写测试：`libcore/protocol/vless/xhttp/server_test.go` 与 `libcore/vless_xhttp_inbound_test.go`，覆盖四种模式 + TLS/h2 + header/cookie 放置 + 普通 VLESS/WS 回归往返
- [x] 1.6 验证门禁：用户提供的 CI run（actions/runs/45251550225）success；本地复跑往返测试带 -race 全部通过；Python 工具本地一度 1 项失败，定位为 VM /tmp 写满的环境问题，清理后 125 项全过，非本 PR 引入

## 2. OpenSpec 变更归档（本变更的待办）

- [x] 2.1 本变更五件套落盘到 `openspec/changes/support-xhttp-inbound/`（proposal/design/tasks + specs/libcore-integration 与 specs/xhttp-transport 的 delta）
- [x] 2.2 同步主规范：把 `specs/libcore-integration/spec.md` 与 `specs/xhttp-transport/spec.md` 的 delta 合入 `openspec/specs/` 下对应主规范
- [x] 2.3 在 `support-xhttp-transport` 的 design.md 中把「XHTTP 服务端 Inbound 驱动」从 Non-Goals 划掉，注明由本变更关闭
- [ ] 2.4 验证门禁：提交变更并触发 GitHub Actions Verify CI，确认文档/规范检查通过后合并

## 3. 后续验证与清理（非拦路项，复审结论转任务）

- [ ] 3.1 与真实 Xray 端到端对打：wanBox 服务端 ↔ Xray 客户端、Xray 服务端 ↔ wanBox 客户端各跑一遍四种模式
- [ ] 3.2 真机全链路验证：Preview 包上真机跑 VLESS XHTTP 入站连接，确认手机上全链路通
- [ ] 3.3 清理 `inbound.go` 里的 KTLS 死条件（从上游抄来的恒不成立判断，无害，删掉或注明来源）
- [ ] 3.4 packet 乱序缓冲满时静默丢包改为显式处理（显式错误或主动关闭会话，避免极端情况下会话卡住等超时）
