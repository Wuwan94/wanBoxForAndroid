# v3.0.19-preview.3 发行说明（候选）

**状态：未发布的稳定性预览版候选。** 正式版仍为 `v3.0.17`；官方 sing-box 内核与 `SINGBOX_VERSION` 未改。

### 【修复 XHTTP stream-one 客户端互通缺陷，并把真实 Xray 互通矩阵接进 CI 门禁】

- **修好 `stream-one` 客户端**：wanBox 客户端连 Xray v26.11.9 服务端时不再被识别成 `stream-up` 吃 `400 Bad Request`；`auto` / `packet-up` / `stream-up` 行为不变。
- **拒绝日志降噪**：XHTTP 服务端 `invalid()` 从 Info 降回 Debug，正常拒绝不再刷屏。
- **12 项真实互通矩阵**：Xray 客户端 ↔ wanBox 服务端、wanBox 客户端 ↔ Xray 服务端各跑四模式，另加 `auto` 双向 TLS 与 Xray→wanBox header/cookie 补测，逐格记录见 `docs/testing/xhttp-xray-interop.md`。
- **进 CI 门禁**：`Verify Android Build` 的 `Build LibCore` 新增互通步骤，官方 `Xray-linux-64.zip` 按 SHA-256 `b651f7e1…ed95f82` 校验（Fail-Closed），12 项子用例必须真跑，跳过或少跑即失败。
- **版本与安装**：APK `versionCode` `10207 → 10213`，Root 模块码 `2041 → 2042`；同一 Release 的管理器 APK 与 Root 模块成套安装，不跨版混装。
- **验证边界**：PR #96（`Verify Android Build` run `45471279983`）与 PR #98（run `45562021260`）门禁全绿，PR #98 互通步骤日志 12/12 PASS；loopback 自动化不等于真机验证，Root 真机矩阵仍未执行。

---

## 历史归档

### v3.0.19-preview.2 已发布说明

**状态：已发布的稳定性预览版。** 正式版仍为 `v3.0.17`；官方 sing-box 内核与 `SINGBOX_VERSION` 未改。

本版收录 [PR #94](https://github.com/Wuwan94/wanBoxForAndroid/pull/94) 的 VLESS XHTTP 服务端加固，由 [升版 PR #95](https://github.com/Wuwan94/wanBoxForAndroid/pull/95) 发布。`Preview Build` [run 45426378108（#63）](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/45426378108) 于 2026-11-24T15:55:00Z 上架；tag 与资产提交同为 `9f4299e50aa88bc6f385daf4663828e07528036d`。

### 本版修复

- 增加上传滚动读取超时与分层资源配额；连接超时或关闭后及时回收资源，避免慢上传阻塞其他会话。
- 修复 `stream-up` 续接和 `stream-one` 并发竞态。
- 收紧会话来源校验、重复下行及请求校验；乱序缓冲溢出时结束会话并释放积压数据。
- 更新 XHTTP 自定义 JSON 示例，并在 CI 纳入 XHTTP 回归与 Go 竞态检测。

### 升级与验证范围

- 发布元数据：APK `versionCode=10207`，Root 模块码 `2041`。管理器 APK 与 Root 模块必须取自同一 Release，不跨版混装。
- [PR #94 Android 验证](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/45399072399) 已通过；新增请求级回归覆盖 HTTP/1.1。HTTP/2/真机矩阵不是该 CI 记录所覆盖的内容。
- [Xray 互通记录](docs/testing/xhttp-xray-interop.md) 的四模式、TLS `auto` 双向及 Xray→wanBox header/cookie 用例，均在后续本地修复分支上执行；`stream-one` 客户端修复不在已发布 preview.2 资产中，也不代表真机验证。
- GitHub Release API 回读的四件套：

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.19-preview.2-arm64-v8a-release.apk` | 6381543 | `c9884dcc06a38fb620ba0f6684ba545730f07987de5b2701306e83373c6cef1e` |
| `wanbox-root-module-arm64-v8a.zip` | 18308156 | `e070088063692787f184068aec32082468c12a317f4b573ad38cc10bf0f059c6` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23965220 | `8fcaa9d3f68186daedb97d9a514fdfc52235d535c6e76a008340129e184c7d48` |
| `SHA256SUMS` | 333 | `52eefd6f5a091193c691fee2187370760681d45bc6284091024542251d85abc2` |

APK + 普通 Root 模块合计 `24689699` B（23.55 MiB），低于 26 MiB 上限 `27262976` B；合一包为 22.86 MiB。preview.2 发布后，preview.1 的 Release/tag 已清理（Release API 与 tag-ref API 均返回 404）；本页仍保留其历史说明与快照。Root 真机矩阵仍未执行。

### v3.0.19-preview.1 已发布说明

**状态：已发布的稳定性预览版。**

本版收口 `v3.0.18-preview.4` 发布后合入 `main` 的一项新功能：为 VLESS 新增 XHTTP（SplitHTTP）**服务端入站**（PR #90，合并提交 `f3d8a67a443b2c450ba404683d97ef51f8b26d9f`，7 个文件 +1820/−4，`Verify Android Build` run `45251550225` 在 head `35c9256a` 全绿）。`3.0.18` 预览线已满每线 `preview.4` 的上限，本版按版本规则开新线 `3.0.19`。正式线 `3.0.17 / 2035`（APK `10175`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 均未变；预览元数据为 `3.0.19-preview.1 / 2040`，派生 APK `versionCode=10201`、Root 模块码 `2040`。`Preview Build` run `45338292530` 于 2026-11-24T07:17:56Z 发布本版（轻量 tag 指向升版 PR #91 合并提交 `94a9c9b895ccaf757119abdada3de4153e05aad2`，发布 job 断言 `APPROVED_SHA=94a9c9b` 并回读 tag；前两次尝试的冷启动测量作业因 Maven Central 429 限流失败，第 3 次尝试四作业全绿）；发布后 `prune_prereleases.py` 已清理 `v3.0.18-preview.4`，正式版 `v3.0.17`、`v3.0.14`、`v3.0.11` 保留。

### ✦ 与上一版对比

| 项目 | v3.0.18-preview.4 | v3.0.19-preview.1 |
|---|---|---|
| 内容 | UDP 回包按来源校验、截止判定先认取消；门代次变化仍删失败证据；自启结果可重放且只消费一次、锁外做 Room/IPC；UDP 迟到回包用例 | 新增 VLESS XHTTP 服务端入站（经自定义 JSON 配置启用）；顺带修复 XHTTP 客户端请求头顺序与上传字节计数 |
| APK `versionCode` | 10199 | 10201 |
| Root 模块码 | 2039 | 2040 |

URLTest 稳定性约束（检测间隔 ≥ 10 秒、空闲超时 ≥ 30 分钟、切换时不中断现有连接）、`default` 保真、层级回切门（连续 3 轮健康 + 60 秒无失败）本版均未放宽；失败方向仍是 fail-closed——证据不足时停留在当前层级，不回切。

### ✦ 本版更新

### 1. VLESS XHTTP 服务端入站（PR #90）

- **背景**：官方 sing-box 不支持 `xhttp` 传输；本项目此前已在 libcore 扩展层补齐 XHTTP **出站**（客户端），本版补齐**入站**（服务端），使 wanBox 可以作为 XHTTP 服务端接受连接。
- **改法**：libcore 新增 VLESS 入站注册——传输不是 XHTTP 时原样委托官方 sing-box 实现，普通 VLESS（含 ws 等传输）行为不倒退；XHTTP 时走自有服务端传输：支持 `auto`、`packet-up`、`stream-up`、`stream-one` 四种模式，HTTP/1.1、HTTP/2 与 h2c、TLS，session/seq 与上行数据的 path/query/header/cookie 放置兼容，X-Padding 校验与 CORS 预检。服务端带资源上限（会话数、单会话与全局缓冲字节、并发上行数、未连接会话超时），异常请求按状态码拒绝，不裸奔监听。
- **接入方式**：经现有节点自定义 JSON 配置路径启用（`transport.type = "xhttp"`），不新增服务器配置向导，App 界面与数据模型未改。官方 sing-box 内核源码与 `SINGBOX_VERSION` 未改，扩展只在 libcore 层。
- **证据**：新增 `libcore/vless_xhttp_inbound_test.go` 端到端往返（四种模式、TLS+HTTP/2、header/cookie 放置、GET 上行）与普通 VLESS / WebSocket 入站回归；`libcore/protocol/vless/xhttp/server_test.go` 覆盖会话 ID 与请求头校验、placement 校验、客户端/服务端填充互通、缓冲上限与关闭释放。PR 的 `Verify Android Build` run `45251550225` 全绿。

### 2. XHTTP 客户端两处既有问题顺带修复（PR #90）

- **请求头顺序**：出站构造请求时先整体替换 Header 再写 session/seq 头的旧顺序，会把会话头冲掉；改为先取配置 Header 再写会话头。
- **上传字节计数**：`uploadWriter` 在缓冲区被消费后才读长度，计数偏小；改为写前先记长度。

### 3. 版本与文档口径

`nb4a.properties` 预览线升为 `3.0.19-preview.1` / `2040`（新线首个预览）；正式线 `3.0.17 / 2035` 与 sing-box pin 未变。当前版本文档一致性检查改为「已发布 preview.4 + 未发布 preview.1 候选」的双态口径。

### ✦ 资产与安装

- **运行要求**：Android 12+（API 31+）、arm64-v8a、Magisk / KernelSU / APatch 等 Root 环境。
- **四件套（API 回读）**：APK `6381543` B / `e91b4a81…196507`、普通 Root ZIP `18295938` B / `95a2ec5f…b920ef`、合一 ZIP `23954760` B / `0b8dd917…88fd47`、`SHA256SUMS` `333` B / `a1c22e05…e1857b`；APK + 普通模块 `24677481` B（23.53 MiB）→ 26 MiB 门禁 PASS。
- **成套同版**：管理器 APK 与 Root 模块必须来自同一 Release；严禁跨 Release 混用。
- **回退**：换回正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17) 的 APK 与配套模块（模块码 `2035`）成套重装。
- **升级**：APK 由 `10199` 升到 `10201`、模块由 `2039` 升到 `2040`，严格递增且同签名，可直接覆盖安装。

### 🛡️ 验证与边界

- **PR 门禁**：PR #90 的 `Verify Android Build` run `45251550225`（head `35c9256a`）全绿，含 `go test -race`、`go vet`、Python 工具 125 项、`rootmodule` 测试与文档门禁、JVM 单测与插桩；升版 PR #91 的 Verify run `45328063751`（head `c02f04f`）首跑红于 3 个组编辑器插桩测试、失败作业重跑后全绿（抖动）。
- **新增回归**：`vless_xhttp_inbound_test.go` 往返用例（四模式 + TLS/HTTP2 + header/cookie 放置 + GET 上行 + 普通 VLESS 与 WebSocket 回归）；`server_test.go` 校验与资源上限用例（会话 ID、请求头、placement、填充互通、缓冲上限与关闭释放、OPTIONS 预检）。
- **未验证边界**：未执行 Magisk / KernelSU / APatch 真机矩阵（CI 自标 `runtime_regression: not_verified`）；XHTTP 服务端与真实 Xray 客户端的端到端互通未验证（自动化为本项目客户端↔服务端往返）；服务端入站的 OpenSpec 变更已由 PR #92 补齐并合入主规范（`support-xhttp-inbound`，合并提交 `12f657478ef3a6caf082211d415e215f23fe1d31`）。任何自动化结果都不代替真机验证，本版不晋升正式版。

### v3.0.18-preview.4 已发布说明

**状态：已发布的稳定性预览版。**

本版收口 `v3.0.18-preview.3` 发布后合入的三批复审修复（PR #84，合并提交 `00cfeedddc07249cccd8a8344eb1bf7df97d75dc`，5 个文件 +71/−3；PR #86，合并提交 `2add76212e54c54b6d4dda02b1077ab373352e24`，12 个文件 +535/−147；PR #87，合并提交 `f0f80b92d597a334440c25171648b19b76573b59`，11 个文件 +534/−45）。预览元数据 `3.0.18-preview.4 / 2039`，派生 APK `versionCode=10199`、Root 模块码 `2039`。`Preview Build` run `45175762344` 四作业全绿，2026-11-23T12:55:32Z 上架（prerelease，发布 job 断言 `APPROVED_SHA=a8cec2fa` 并回读 tag）；12:55:40Z 由 `prune_prereleases.py` 清理 `v3.0.18-preview.3`，正式版 `v3.0.17`、`v3.0.14`、`v3.0.11` 保留。内容：UDP 回包按来源校验、截止判定先认取消；门代次变化仍删失败证据；导出/测速排除探测端点可测化；自启结果可重放且只消费一次、锁外做 Room/IPC；UDP 迟到回包用例。四件套：APK `6381547` B / `2856c050…1424d5`、普通 Root ZIP `18151369` B / `d5a549c3…f4705b`、合一 ZIP `23807498` B / `5bc8c2fb…b39239`、`SHA256SUMS` `333` B，APK + 普通模块 `24532916` 字节（23.40 MiB）→ `size_budget --enforce-limit` PASS。本版自身的 Release/tag 将在 `v3.0.19-preview.1` 发布时按单预览策略清理。

### v3.0.18-preview.3 已发布说明

**状态：已发布的稳定性预览版。**

本版收口 `v3.0.18-preview.2` 复审提出的四项缺陷（PR #83，合并提交 `8881dd1c59481b3d23ab3766fb21e3b651fa1d65`，25 个文件 +332/−78，`Verify Android Build` run `44879210932` 在 head `dbdbf0b` 全绿）。预览元数据 `3.0.18-preview.3 / 2038`，派生 APK `versionCode=10193`、Root 模块码 `2038`。`Preview Build` run `44882104257` 四作业全绿，2026-11-21T08:13:01Z 上架（prerelease，发布 job 断言 `APPROVED_SHA=8881dd1c` 并回读 tag）；08:13:09Z 由 `prune_prereleases.py` 清理 `v3.0.18-preview.2`，正式版 `v3.0.17`、`v3.0.14`、`v3.0.11` 保留。内容：「设置 → 连接与核心 → UDP 回显端点」可填自管数字 IP 与端口（默认留空即零流量，fail-closed），各优先级层子组独立执行 UDP 回显验证；仅支持 UDP 的上层成员凭有效回显进入候选，不再依赖 TCP 延迟记录（回切仍要连续三轮新观测 + 60 秒无失败）；首次应用配置前也回读已保存的开机自启设置；自启等待确认期间连点按最后一次选择落地。四件套：APK `6374839` B / `e7832733…893507`、普通 Root ZIP `18149655` B / `1fbfc62a…bdc280`、合一 ZIP `23797772` B / `2fe93950…88c80d`、`SHA256SUMS` `333` B，APK + 普通模块 `24524494` 字节（23.39 MiB）→ `size_budget --enforce-limit` PASS。本版自身的 Release/tag 已在 `v3.0.18-preview.4` 发布后（2026-11-23T12:55:40Z）按单预览策略清理（现为 404）。

### v3.0.18-preview.2 已发布说明

[v3.0.18-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.2) 以 main `e7f1f9366316974d8318e8708dcaaa669bc896a0`（升版 PR #81 合并提交，轻量 tag）发布，`Preview Build` run `44728368529` 四作业全绿、2026-11-20T11:51:54Z 上架；预览元数据 `2037`，APK `10187` / 模块 `2037`。内容为收口 preview.1 六项缺陷（PR #80）：URLTest 观测按（成员, 网络）分账、UDP 探测只认真实往返回显、降级即作废旧回切资格、组关闭停拨备用、开机自启「下发 → 回读 → 落库」闭环、`boot.json` 不可用时状态与 `Boot()` 同口径。已知边界：该版 UDP 探测默认零流量，自管端点入口到 preview.3 才落地。其 Release/tag 已在 preview.3 发布后（2026-11-21T08:13:09Z）清理。

### v3.0.18-preview.1 已发布说明

[v3.0.18-preview.1](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.1) 以 main 的 `0a30baaf939a97b1941210609ce377e00d386157`（轻量 tag）发布，`Preview Build` run `44516333684` 四个作业全绿（发布 job 断言 `APPROVED_SHA=0a30baaf`）；预览元数据 `2036`，APK `10181` / 模块 `2036`。内容为 PR #77（`currentTier` / `tierGate` 按 TCP/UDP 拆分，消除跨协议污染）与 PR #78（App UI 对齐模块功能：开机自启开关、重启核心、回滚配置、顶栏模块版本）。已知边界：一次探测的观测仍按 tag 共享、嵌套组聚合 summary 与网络无关，两个新 UI 入口无自动化覆盖。`0a30baa` 是 `preview.yml` 的热修直推提交，没有单独的 `Verify Android Build` 记录，其代码树与已全绿的 `4a8ab9b` 相同。本版 Release 在 `v3.0.18-preview.2` 发布时由 `prune_prereleases.py` 清理。

### v3.0.17-preview.4 已发布说明

**状态：已作为稳定性预览版发布；Release/tag 已在 v3.0.18-preview.1 发布后清理。**

基于 main `906a6ea3f48779cc96c7a4116cfd1faeb63aa354`（PR #72，`Verify Android Build` run `44372679147`），`Preview Build` run `44378329740` 于 2026-11-18T19:38:07Z 发布；预览元数据 `2034`，APK `10174` / 模块 `2034`。内容为故障转移不再绕过层级回切门、过期候选保留为最后梯队兜底、direct 失败改 15s/1min/5min 有界退避、区分 `su` 超时与真正的空白名单，另收口 supervisor 吞错、预算到期虚增健康轮与零引用符号。资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。

### v3.0.17-preview.3 已发布说明

[v3.0.17-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.3) 已作为稳定性预览版发布（tag/commit `6adc5ac0bcbd87bf9ff4b2097fe367761a4bc841`，PR #69；`Verify Android Build` run `44295650161`，`Preview Build` run `44306599880` 于 2026-11-18T13:08:01Z 发布）。内容为修复 URLTest 预算到期后层级永不回切、诊断包 Android 12+ 不可读取，并收口八项 P2 与 Room 11 schema；APK `10168` / 模块 `2033`。资产快照见[版本对照](docs/current-version.zh-CN.md)；其 Release/tag 已在 preview.4 发布后按单预览策略清理，下载链接不再可用。

### v3.0.17-preview.2 已发布说明

[v3.0.17-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.2) 已作为稳定性预览版发布（tag/commit `49e966197dd608b687b36eecdaf394274dacfe15`，PR #67/#68；`Preview Build` run `44281162788` 于 2026-11-18T10:18:19Z 发布）。内容为 OwnBox/NetProxy 两路借鉴移植合并后的首个预览：核心稳定性与故障转移、订阅自动更新、智能分流、冷启动秒连、策略组混搭/锁定/优先级、停核可编辑与脱敏诊断包、按网络规则、路由规则指定策略组；APK `10162` / 模块 `2032`。其 Release/tag 已在 preview.3 发布后按单预览策略清理，下载链接不再可用。

### 更早预览

v3.0.17-preview.1、v3.0.16-preview.4、v3.0.16-preview.3、v3.0.16-preview.2、v3.0.16-preview.1、v3.0.15-preview.1–.4 与 v3.0.14-preview.1 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。
