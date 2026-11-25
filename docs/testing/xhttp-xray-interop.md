# XHTTP 真实 Xray 互通验证

本记录补齐真实 Xray-core 与 wanBox XHTTP 入站、出站的跨实现往返证据。测试仅绑定本机 loopback 和临时 TCP 端口，不连接外部服务；真机验证仍未执行。

## 环境与命令

- 仓库基线：`9f4299e50aa88bc6f385daf4663828e07528036d`（PR #95 合并提交，v3.0.19-preview.2 tag 提交）；本地验证包含仅修正 `stream-one` session ID 的未提交改动。
- 系统与 Go：Linux/amd64，Go `1.26.10`。
- Xray：官方最新稳定版 `v26.11.9`，版本输出为 `Xray 26.11.9`、commit `676eaf6`、Go `1.27.3 linux/amd64`。
- 官方 Linux 64 位压缩包：[`Xray-linux-64.zip`](https://github.com/XTLS/Xray-core/releases/download/v26.11.9/Xray-linux-64.zip)，下载后 SHA-256：`b651f7e1d9966ee2c1b1ee1bf8b52c72771824d3065dce96bbc8bcc19ed95f82`；与官方 Release API 的 digest 一致。
- Xray 客户端→wanBox 服务端使用本分支 `libcore` Box API，以自定义 JSON 启用 VLESS XHTTP 入站；wanBox 客户端→Xray 服务端使用官方 Xray XHTTP 传输。两端的 echo 目标均为 `127.0.0.1`。
- Xray 测试服务端配置仅允许 freedom 出站 TCP 到 `127.0.0.1/32`，以避免默认 final rule 阻断 loopback；此规则只在临时测试配置中。TLS 自签证书在两端均显式信任：Xray 客户端使用 SHA-256 pin，wanBox 客户端通过 `certificate_path` 信任本次生成的测试证书，并校验 `localhost` 名称；未关闭证书验证。

完整矩阵命令（在 `libcore/` 执行）：

```sh
XRAY_BIN=/tmp/xray-26.11.9/xray go test -count=1 -run '^TestRealXrayXHTTPInterop$' -v .
```

## 逐格结果

| 方向 | 模式 / 补测 | 结果 |
|---|---|---|
| Xray 客户端 → wanBox 服务端 | `auto` | 通过 |
| Xray 客户端 → wanBox 服务端 | `packet-up` | 通过 |
| Xray 客户端 → wanBox 服务端 | `stream-up` | 通过 |
| Xray 客户端 → wanBox 服务端 | `stream-one` | 通过 |
| Xray 客户端 → wanBox 服务端 | `auto` + TLS | 通过 |
| Xray 客户端 → wanBox 服务端 | `packet-up`，上行数据放在 header | 通过 |
| Xray 客户端 → wanBox 服务端 | `packet-up`，上行数据放在 cookie | 通过 |
| wanBox 客户端 → Xray 服务端 | `auto` | 通过 |
| wanBox 客户端 → Xray 服务端 | `packet-up` | 通过 |
| wanBox 客户端 → Xray 服务端 | `stream-up` | 通过 |
| wanBox 客户端 → Xray 服务端 | `stream-one` | 通过 |
| wanBox 客户端 → Xray 服务端 | `auto` + TLS | 通过 |

每个格子均通过端到端 TCP 数据写入和 echo 完全匹配断言。header 与 cookie 补测都使用 Xray 客户端到 wanBox 服务端方向的 `packet-up`。

2026-11-25 本机重跑的实际输出摘要（12 个子用例与总项均通过）：

```text
=== RUN   TestRealXrayXHTTPInterop
=== RUN   TestRealXrayXHTTPInterop/xray-client_to_wanbox-server/auto
=== RUN   TestRealXrayXHTTPInterop/wanbox-client_to_xray-server/auto
=== RUN   TestRealXrayXHTTPInterop/xray-client_to_wanbox-server/packet-up
=== RUN   TestRealXrayXHTTPInterop/wanbox-client_to_xray-server/packet-up
=== RUN   TestRealXrayXHTTPInterop/xray-client_to_wanbox-server/stream-up
=== RUN   TestRealXrayXHTTPInterop/wanbox-client_to_xray-server/stream-up
=== RUN   TestRealXrayXHTTPInterop/xray-client_to_wanbox-server/stream-one
=== RUN   TestRealXrayXHTTPInterop/wanbox-client_to_xray-server/stream-one
=== RUN   TestRealXrayXHTTPInterop/xray-client_to_wanbox-server/auto-tls
=== RUN   TestRealXrayXHTTPInterop/wanbox-client_to_xray-server/auto-tls
=== RUN   TestRealXrayXHTTPInterop/xray-client_to_wanbox-server/packet-up-header
=== RUN   TestRealXrayXHTTPInterop/xray-client_to_wanbox-server/packet-up-cookie
--- PASS: TestRealXrayXHTTPInterop (0.94s)
PASS
ok  libcore 0.955s
```

## 缺陷定位与修复

修复前，隔离运行 wanBox 客户端→Xray 服务端 `stream-one` 返回 `400 Bad Request`，Xray 日志为 `stream-up mode is not allowed`。定位到 wanBox 客户端当时对所有模式都生成 UUID session ID；而 [Xray v26.11.9 客户端](https://github.com/XTLS/Xray-core/blob/v26.11.9/transport/internet/splithttp/dialer.go#L343-L345)在 `stream-one` 模式留空 session ID，[Xray 服务端](https://github.com/XTLS/Xray-core/blob/v26.11.9/transport/internet/splithttp/hub.go#L200-L205)会把带 session ID、无 sequence 的上行请求当作 `stream-up`，并在 `stream-one` 模式拒绝。

按 owner 明确批准的修复范围，wanBox 客户端现仅在模式不是 `stream-one` 时生成 session ID。相同互通用例修复前失败、修复后通过；没有修改 Xray、sing-box fork、依赖声明、`SINGBOX_VERSION` 或服务端限额。

修复后单项复现命令通过：

```sh
XRAY_BIN=/tmp/xray-26.11.9/xray go test -count=1 \
  -run '^TestRealXrayXHTTPInterop/wanbox-client_to_xray-server/stream-one$' -v .
```

## CI 门禁

PR 验证（`verify-android.yml`）、预览发布（`preview.yml`）和正式发布（`release.yml`）的 LibCore 作业都调用同一个脚本：`bash tools/verify_xhttp_xray_interop.sh`。预览和正式发布必须等待同次运行、同一提交的 LibCore 成功；原生构建缓存命中也不会跳过互通验证。

- 从官方 Release 下载 `Xray-linux-64.zip`（`v26.11.9`），并以 `sha256sum --check --strict` 校验 `b651f7e1d9966ee2c1b1ee1bf8b52c72771824d3065dce96bbc8bcc19ed95f82`，校验失败即停（Fail-Closed）。
- 解压后执行 `XRAY_BIN=<临时目录>/xray go test -count=1 -run '^TestRealXrayXHTTPInterop$' -v .`，`go test` 非零、日志出现 `--- SKIP` 或 `set XRAY_BIN to an official` 提示、以及通过的子用例数不等于 12，均按失败处理，防止用例在 CI 里静默跳过。
- 本地演练：同一脚本 12 项全通过；去掉 `XRAY_BIN` 的对照运行时 `go test` 仍返回 0，但步骤按预期判失败。

## 相关回归

修复后以下命令均通过：

```sh
go test -count=1 ./protocol/vless/xhttp
go test -race -count=1 ./protocol/vless/xhttp
go test -count=1 .
```

这组结果仅代表本机 loopback 验证，不替代手机、Root 模块或真实网络环境验证；OpenSpec §3.2 真机任务仍未执行。
