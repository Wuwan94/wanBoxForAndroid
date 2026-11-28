## Context

详见 `proposal.md`。当前仓库采用固定 reF1nd sing-box 内核基线（`SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd`，不变），`openspec/specs/libcore-integration/spec.md` 严格要求：
1. `libcore` 仅接入固定内核基线源码，禁止修改内核源码。
2. 自定义协议扩展必须基于固定内核的注册表（`Registry`）和插件机制，不得破坏架构边界。

`support-xhttp-transport` 已按「方案 1A」把 XHTTP 客户端出站接进 `libcore/protocol/vless/xhttp`，并在 design.md 中把服务端入站列为 Non-Goal。PR #90 用同一方案补上服务端一侧：同一份 `xhttp` 传输包既有客户端也有服务端，入站与出站共用线格式与模式语义。

## Goals / Non-Goals

**Goals:**
- 在 `libcore/protocol/vless` 中实现扩展的 VLESS 入站，并在 `nekoboxAndroidInboundRegistry` 中新增注册，非 XHTTP 传输委托官方 `singboxVLESS.NewInbound`。
- 在 `libcore/protocol/vless/xhttp` 中实现 XHTTP 服务端传输（`server.go`），完整支持 `auto`、`packet-up`、`stream-up`、`stream-one` 模式，支持 TLS + HTTP/2（含 h2c）与 HTTP/1.1。
- 配置 `type: vless` + `transport: xhttp` 时走新代码，其他传输类型原样委托官方实现。
- 服务端资源上限全部显式化（会话数、缓冲、并发上传、超时），不是裸奔的监听器。
- 保持固定内核基线纯净，不修改内核源码，不碰版本号文件。
- 与 Xray 公开的 XHTTP 线格式对齐；真实 Xray 双向互通验证已完成，逐格结果见 [`docs/testing/xhttp-xray-interop.md`](../../../docs/testing/xhttp-xray-interop.md)。

**Non-Goals:**
- 修改 sing-box upstream 源码或 `SINGBOX_VERSION`。
- 为 Trojan / VMess 扩展 XHTTP 服务端（本次只做 VLESS 入站）。
- 引入外部 Xray-core 二进制或多进程旁路运行。
- PR #90 的原始实现范围不包含真实 Xray 互通与真机全链路验证；后续互通结果见验证记录，真机任务仍保留。

## Decisions

### 1. 采用 Out-of-tree 扩展注册新增 VLESS 入站
- **选择**: 在 `libcore/protocol/vless` 中定义自定义 VLESS 入站构造器，并在 `libcore/box_include.go` 的 `nekoboxAndroidInboundRegistry` 中为 `C.TypeVLESS` 新增扩展注册处理。
- **原因**:
  - 与出站侧同一手法（方案 1A）：sing-box 留有正规的扩展口；自定义构造器仅对 XHTTP 使用自有服务端传输，其他传输类型仍委托官方 `singboxVLESS.NewInbound`，保持官方行为。
  - 非 XHTTP 配置原样委托官方实现，回归面最小。
- **替代方案**:
  - *修改固定内核源码*: 违反项目主规范对固定内核基线的约束，增加后续升核维护成本。
  - *使用 Xray 进程旁路*: 引入额外体积及双进程保活复杂度，移动端保护性差。

### 2. 服务端与客户端共用同一 xhttp 传输包
- **选择**: 服务端实现（`server.go`）落在已有的 `libcore/protocol/vless/xhttp` 包内，与客户端驱动共用模式归一化、填充、放置校验等逻辑。
- **原因**: 线格式与模式语义两端一致，避免两份实现各自漂移；PR #90 顺带修掉的 `dialer.go`（session 头写入顺序）与 `writer.go`（写后计数）两处 bug 正是共用包里的既有问题。
- **替代方案**:
  - *另起独立服务端包*: 模式与校验逻辑重复实现，后续改一处漏一处。

### 3. 资源上限写死在服务端构造时校验
- **选择**: 会话全局 1024 / 每来源 IP 32，并发上传全局 64 / 每来源 IP 8 / 每会话 2，单帖 2 MiB、读入预算 24 MiB、队列全局 32 MiB / 每会话 4 MiB、缓冲帖子 256、未连接会话 30 秒超时、填充上限 4096、会话 ID 上限 128，全部在 `NewServer` 构造与配置校验时收口；超限配置直接拒绝启动，不静默放行。
- **原因**: 入站是对外暴露面，移动端跑服务端更怕无上限的连接/缓冲把内存吃光；显式上限让复审有数可查。
- **已知行为**: packet 乱序缓冲达到上限时会关闭会话并回收缓冲资源；极端乱序可能中断当前会话，客户端需重建会话。该行为以显式失败保证资源回收，不会静默丢包（见 tasks.md §3.4）。

## Risks / Trade-offs

- **[Risk 1] PR #90 原始验收时尚未完成真实 Xray 互通** —— 后续已使用官方 Xray v26.11.9 完成双向四模式、TLS 与 header/cookie 补测；记录见 [`docs/testing/xhttp-xray-interop.md`](../../../docs/testing/xhttp-xray-interop.md)。
  - *Mitigation*: tasks.md §3.1 已完成；发现的 `stream-one` session ID 互通缺陷已按授权最小修复并通过复测。
- **[Risk 2] 未上真机验证** —— Go 层往返测试通了不等于手机上全链路通。
  - *Mitigation*: 列为后续验证任务（tasks.md 3.2），Preview 包上真机跑入站连接。
- **[Risk 3] KTLS 死条件** —— `inbound.go` 里 KTLS 判断是从上游抄来的死条件，此路径恒不成立，无害但容易误导后人阅读。
  - *Mitigation*: 记录在案，后续清理时删掉或注明来源，不阻塞归档。
- **[Risk 4] 规范滞后于代码已成事实** —— PR #90 代码已合入，规范现在才补，存在「先斩后奏」的治理缺口。
  - *Mitigation*: 本变更即补口子；同时建议后续同类扩展先立变更再合代码，避免重蹈。
