# v3.0.19-preview.4 发行说明

**状态：已发布的稳定性预览版。** 正式版仍为 `v3.0.17`；官方 sing-box 内核与 `SINGBOX_VERSION` 未改。

本版由 [升版 PR #113](https://github.com/Wuwan94/wanBoxForAndroid/pull/113) 发布（合并提交 `cfb1e25c4b02f261dee17baa77a4fa4a1fa60565`），`Preview Build` [run 45890957065](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/45890957065) 四个作业全绿，2026-11-26T20:54:52Z 上架；tag、`targetCommitish`、APK 内 `version-control-info` 与发布 job 的 `APPROVED_SHA` 同为 `cfb1e25c4b02f261dee17baa77a4fa4a1fa60565`。内容是 `v3.0.19-preview.3` 之后合入 `main` 的十二条改动：Root 模块第一次有了能用的网页控制台，AnyTLS 支持客户端证书，VLESS 标签把手动入口分清 XHTTP 与 Reality，XHTTP 长上传在服务端保活下不再断流，界面适配全面屏与预测性返回，另有两处稳定性修复。正式线 `3.0.17 / 2035` 与 `SINGBOX_VERSION` 均未变。

### 【Root 模块有了网页控制台：在 KernelSU / APatch 管理器里直接看状态、启停、导事件】

- **能做什么**：在 KernelSU / APatch 管理器里打开 wanBox 的固定页面，看模块运行状态与最近事件，启停模块，导出最近 40 条事件。
- **怎么连通**：页面只调用固定的 `wanboxctl` 白名单命令，不起本地 HTTP 服务、不加载外部脚本、严格 CSP；重新适配了 KernelSU（以及兼容其 API 的 APatch）的注入桥接，回调、异常和超时都会清理。没有兼容桥接时所有按钮一律禁用，不会假装成功。
- **边界**：页面与桥接只在单测和模拟器上验证过，Magisk / KernelSU / APatch 真机尚未实测。

### 【AnyTLS 支持客户端证书（mTLS），私钥只留在本机】

- **能填什么**：节点设置里可以贴客户端证书与私钥 PEM，写入 sing-box 出站 TLS 的 `client_certificate` / `client_key`；两项留空就还是原来的单向 TLS。
- **不会外泄**：`anytls://` 与通用分享链接都不携带证书和私钥，只清理导出副本、不动本地配置；诊断包会把配置与 Root 快照里的 AnyTLS 私钥/证书替换成 `***`；设置页只显示私钥摘要，不回显内容。
- **兼容**：旧版配置照常读取，新字段走 v2 持久化；HWID、Mieru 与策略组行为未改。
- **边界**：没有真实 mTLS 服务端可连，只在单测与 JVM 测试里验证字段映射与脱敏。

### 【VLESS 标签分清 XHTTP 与 Reality，手动新建多了直达入口】

- **看得清**：节点列表、选择列表、详情页和通知标题按节点实际字段显示 `VLESS + XHTTP`、`VLESS · Reality` 或 `VLESS + XHTTP · Reality`，不再统一写 `VLESS`；普通协议的通知文案保持原样。
- **加得快**：「+ → 手动设置」新增「VLESS + XHTTP」，点进去就是预置好 XHTTP 传输的表单，不用再手改类型；普通 VLESS 仍默认 TCP。
- **没变形**：分享链接、核心配置、策略组拨号与 HWID 外发都没有改动。

### 【XHTTP 更扛得住：服务端保活不再把上传挤断，客户端卡死会按时超时】

- **修好断流**：服务端开启周期保活（padding）时，成功响应原来读到 32 KiB 就被重置，长上传会断在中途；现在会持续排空到响应结束再收尾，上传能完整送达。新加的真实 TLS/HTTP2 回环回归（旧基线 3/3 红、修复版 3/3 绿）盯住这条路径。
- **不再无限等**：客户端连接有了总时长、读、写三种期限，超时按方向失败关闭，不硬撑也不假装成功；单个请求取消不会把共享的 HTTP/2 连接一起判死。
- **服务端保活更规整**：`stream-up` 成功响应按 20–80 秒周期发 padding，响应写期限与上传读空闲期限分开计算，`-1` 可关闭。
- **互通门槛抬高**：官方 Xray v26.11.9 的 12 项互通已成为 PR、预览发布与正式发布的硬门禁，跳过或少跑直接失败。

### 【界面适配全面屏与预测性返回】

- 非浮层页面统一做 edge-to-edge 避让，状态栏、导航栏、挖孔与横屏刘海不再压住内容；返回键改走 AndroidX 分发，支持预测性返回动画，各页面原有返回值不变。
- **边界**：Android 14/15 的手势、三键导航、输入法与横屏实机行为未验证，单测只覆盖尺寸计算与接线。

### 【两处稳定性修复】

- **回切不再被迟到的 TCP 成功骗到**：TCP 恢复门代次变化后，旧的探测或数据面拨号成功不再覆盖 `URLTestHistory`，也不再清掉成员的连续失败计数。
- **部分 OEM 的 Google 拦截可按需修掉（待真机确认）**：Root 模块在开机就绪 60 秒后（或成功热激活后）做一次严格白名单预检，只针对红魔/努比亚/中兴 `zte_fw_gms` 里目标 Google UID 的单 UID 拦截规则，以及一加/OPPO ColorOS 16 上值为 `1` 的目标 UID map 条目做定点删除；固件、UID 共享、策略或 BPF 布局任何一项对不上就整体跳过，不 flush 链、不碰 DNS 与共享规则，也不生成或发送 HWID。
- **升级与回退**：APK `versionCode` `10213 → 10219`、Root 模块码 `2042 → 2043`；同一 Release 的管理器 APK 与 Root 模块请成套安装，不要跨版本混用；回退用正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17)（模块码 `2035`）。
- **总边界**：Root 真机矩阵（Magisk / KernelSU / APatch）、WebUI 实机、真实 mTLS 服务端与 OEM Google 兼容都未实测，本版不晋升正式版。

## 四件套（GitHub Release API 回读）

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.19-preview.4-arm64-v8a-release.apk` | 6382731 | `2bb0f76cdba3aba81a9502ff6aaf232fac573552c46b99ce5c89a601268651d7` |
| `wanbox-root-module-arm64-v8a.zip` | 18333507 | `14641d464a269225452d9eb8f41b97ea07c2fc8028e264081b7049502307c581` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23991076 | `3723d597b3f265bbc84eaecae507573032a39fa82000cf38c60399516d167e3d` |
| `SHA256SUMS` | 333 | `1a12188439f4b596124fbe1010ebb2fe1ed2a3be4c77acce58e4c0109125cba1` |

APK + 普通 Root 模块 `24716238` B（23.57 MiB），低于 26 MiB 上限 `27262976` B（余量 `2546738` B，2.43 MiB）；合一包 `23991076` B（22.88 MiB）。APK 内 `lib/` 条目为 0，`versionCode=10219` / `versionName=3.0.19-preview.4`，证书 SHA-256 `80ECEE1C42D861D59CDC460447335FC4BFDF80103D6225A17C1BDF83A33521F6`（与 `v3.0.17` 同源）；模块 `module.prop` 为 `3.0.19-preview.4` / `2043`，含 4 个 `webroot/` 文件，两个 ZIP 均通过 `python3 -m zipfile -t`。本版发布后，`v3.0.19-preview.3` 的 Release 与 tag-ref API 均返回 404（已按单预览策略清理），正式版 `v3.0.17`、`v3.0.14`、`v3.0.11` 保留。Root 真机矩阵仍未执行。

---

## 历史归档

### v3.0.19-preview.3 已发布说明

**状态：已发布的稳定性预览版。** 正式版仍为 `v3.0.17`；官方 sing-box 内核与 `SINGBOX_VERSION` 未改。

本版收录 [PR #96](https://github.com/Wuwan94/wanBoxForAndroid/pull/96) 的 XHTTP `stream-one` 客户端修复与 12 项真实 Xray 互通矩阵，以及 [PR #98](https://github.com/Wuwan94/wanBoxForAndroid/pull/98) 把该矩阵接进 CI 门禁，由 [升版 PR #99](https://github.com/Wuwan94/wanBoxForAndroid/pull/99) 发布。`Preview Build` [run 45573982693](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/45573982693) 于 2026-11-25T08:32:21Z 上架；tag、`targetCommitish`、APK 内 `version-control-info` 与发布 job 的 `APPROVED_SHA` 同为 `7d193fe45aa3060c9e03aa0befbc4f349f6a712d`。

#### 【修好 XHTTP stream-one 互通，互通测试从此每次提交都真跑】

- **修好连 Xray 服务端时的 400**：以前用 `stream-one` 模式连官方 Xray 服务端会被拒（`400 Bad Request`），现在能正常握手；`auto` / `packet-up` / `stream-up` 行为不变。
- **日志更安静**：服务端拒绝异常请求的提示从信息级降到调试级，日常日志不再被这类拒绝刷屏。
- **互通测试真的跑起来了**：每次提交都会用官方 Xray v26.11.9 跑 12 项真实互通测试（双向四模式 + `auto` 双向 TLS + header/cookie 补测），下载包按 SHA-256 `b651f7e1…ed95f82` 校验，跳过或少跑直接判失败。
- **升级与回退**：APK `versionCode` `10207 → 10213`、Root 模块码 `2041 → 2042`；同一 Release 的管理器 APK 与 Root 模块请成套安装，不要跨版本混用；回退用正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17)（模块码 `2035`）。
- **边界**：互通矩阵是本机 loopback + 模拟器 CI，不等同于真机验证；Root 真机矩阵仍未执行，本版不晋升正式版。

### 四件套（GitHub Release API 回读）

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.19-preview.3-arm64-v8a-release.apk` | 6381543 | `4c8ef03eec5534d6b21e5964cb333cfc4799621db58043a394f5199c65aaabfc` |
| `wanbox-root-module-arm64-v8a.zip` | 18308150 | `2665ba1de7ce390fbbe752a0a5edcd5208b022ed32b187ff947766db8f2640dc` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23965116 | `595f35409f91896261babddf96bf112eceb33ce7e5bf36ff79186913d84f0c37` |
| `SHA256SUMS` | 333 | `eff1f1d491ee8932f527e90c2af50f76151ea19f14d2cf169a046d1b5484d215` |

APK + 普通 Root 模块 `24689693` B（23.55 MiB），低于 26 MiB 上限 `27262976` B；合一包 `23965116` B（22.85 MiB）；APK 内 `lib/` 条目为 0，模块 `module.prop` 为 `3.0.19-preview.3` / `2042`。preview.3 发布后，preview.2 的 Release/tag 已清理（Release API 与 tag-ref API 均返回 404）；本页保留其历史说明与快照。Root 真机矩阵仍未执行。

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
- [Xray 互通记录](testing/xhttp-xray-interop.md) 的四模式、TLS `auto` 双向及 Xray→wanBox header/cookie 用例，均在后续本地修复分支上执行；`stream-one` 客户端修复不在已发布 preview.2 资产中，也不代表真机验证。
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

本版收口 `v3.0.18-preview.4` 发布后合入的一项新功能：PR #90（合并提交 `f3d8a67a443b2c450ba404683d97ef51f8b26d9f`，7 个文件 +1820/−4）新增 VLESS XHTTP 服务端入站。`3.0.18` 线已满 `preview.4` 上限，本版按版本规则开新线 `3.0.19`。正式线 `3.0.17 / 2035`（APK `10175`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 均未变；预览元数据为 `3.0.19-preview.1 / 2040`，派生 APK `versionCode=10201`、Root 模块码 `2040`（已发布 preview.4 为 `10199 / 2039`）。`Preview Build` run `45338292530` 于 2026-11-24T07:17:56Z 发布（tag `94a9c9b895ccaf757119abdada3de4153e05aad2`，升版 PR #91 合并提交；前两次尝试的冷启动测量作业因 Maven Central 429 限流失败，第 3 次尝试四作业全绿），发布后已清理 `v3.0.18-preview.4`。四件套：APK `6381543` B、普通 Root ZIP `18295938` B、合一 ZIP `23954760` B、`SHA256SUMS` `333` B，APK + 普通模块 `24677481` B（23.53 MiB）→ PASS。

### ✦ 与上一版对比

| 项目 | v3.0.18-preview.4 | v3.0.19-preview.1 |
|---|---|---|
| 内容 | UDP 回包按来源校验、截止判定先认取消；门代次变化仍删失败证据；自启结果可重放且只消费一次、锁外做 Room/IPC；UDP 迟到回包用例 | 新增 VLESS XHTTP 服务端入站（经自定义 JSON 配置启用）；顺带修复 XHTTP 客户端请求头顺序与上传字节计数 |
| APK `versionCode` | 10199 | 10201 |
| Root 模块码 | 2039 | 2040 |

URLTest 间隔 ≥10 秒、空闲 ≥30 分钟、切换不中断现有连接与「连续 3 轮健康 + 60 秒无失败」回切门均未放宽；证据不足时停在当前层级，不回切。

### ✦ 本版更新

1. **VLESS XHTTP 服务端入站**：libcore 在入站注册表新增 VLESS 注册项，非 XHTTP 传输原样委托官方实现；XHTTP 时启用自有服务端传输，支持 `auto` / `packet-up` / `stream-up` / `stream-one`、HTTP/1.1 与 HTTP/2/h2c、TLS、多种 session/seq/数据放置与 X-Padding 校验、CORS 预检，并设会话数、缓冲字节与并发上行等资源上限。经现有节点自定义 JSON 配置路径启用，不新增向导；官方 sing-box 内核与 `SINGBOX_VERSION` 未改。
2. **XHTTP 客户端两处修复**：出站请求头构造顺序修正（会话头不再被整体替换冲掉）；`uploadWriter` 改为写前记录长度，上传字节计数不再偏小。

### ✦ 升级与验证边界

同一 Release 的瘦管理器 APK 与 Root 模块必须成套安装；回退用正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17) 的 APK 与配套模块（模块码 `2035`）。升级方向：APK `10199 → 10201`、模块 `2039 → 2040`，严格递增且同签名，可直接覆盖安装。

PR 门禁：PR #90 的 `Verify Android Build` run `45251550225`（head `35c9256a`）全绿；升版 PR #91 的 Verify run `45328063751` 首跑红于 3 个组编辑器插桩测试、重跑后全绿（抖动）。新增回归：`vless_xhttp_inbound_test.go` 端到端往返（四模式、TLS+HTTP/2、header/cookie 放置、GET 上行，及普通 VLESS / WebSocket 入站回归）与 `server_test.go` 的校验、填充互通与缓冲上限用例。

未执行 Root 真机矩阵（CI 自标 `runtime_regression: not_verified`）；与真实 Xray 客户端的端到端互通未验证（自动化为本项目客户端↔服务端往返）；服务端入站的 OpenSpec 变更已由 PR #92 补齐并合入主规范。自动化结果不等同于真机验证，本版不晋升正式版。

### v3.0.18-preview.4 已发布说明

[v3.0.18-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.4) 已作为稳定性预览版发布：main `a8cec2fa6676ec0c9a5fb3b7186a9c84e3e44893`（升版 PR #88 合并提交，轻量 tag），`Preview Build` run `45175762344` 四作业全绿、2026-11-23T12:55:32Z 上架，12:55:40Z 清理 `v3.0.18-preview.3`；预览元数据 `2039`，APK `10199` / 模块 `2039`。内容为收口 preview.3 后三批复审修复（PR #84 / #86 / #87）：UDP 回包按来源校验与截止判定、自启结果 StateFlow 可重放、锁粒度收窄与 UDP 迟到回包用例。四件套：APK `6381547` / 模块 `18151369` / 合一包 `23807498` / `SHA256SUMS` `333` 字节，APK+模块 `24532916` 字节（23.40 MiB）→ PASS。其 Release/tag 将在 v3.0.19-preview.1 发布后按单预览策略清理。

### v3.0.18-preview.3 已发布说明

[v3.0.18-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.3) 已作为稳定性预览版发布：main `8881dd1c59481b3d23ab3766fb21e3b651fa1d65`（PR #83 合并提交，轻量 tag），`Preview Build` run `44882104257` 四作业全绿、2026-11-21T08:13:01Z 上架，08:13:09Z 清理 `v3.0.18-preview.2`；预览元数据 `2038`，APK `10193` / 模块 `2038`。内容为「设置 → 连接与核心 → UDP 回显端点」自管端点落地、各层子组独立 UDP 回显验证、UDP-only 上层成员凭回显进候选、首次应用配置前回读自启设置、自启连点按最后一次选择落地。四件套：APK `6374839` / 模块 `18149655` / 合一包 `23797772` / `SHA256SUMS` `333` 字节，APK+模块 `24524494` 字节（23.39 MiB）→ PASS。其 Release/tag 已在 preview.4 发布后（2026-11-23T12:55:40Z）按单预览策略清理。

### v3.0.18-preview.2 已发布说明

[v3.0.18-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.2) 已作为稳定性预览版发布：main `e7f1f9366316974d8318e8708dcaaa669bc896a0`（升版 PR #81 合并提交），`Preview Build` run `44728368529` 四作业全绿、2026-11-20T11:51:54Z 上架；预览元数据 `2037`，APK `10187` / 模块 `2037`。内容为收口 preview.1 六项缺陷（PR #80）：观测按（成员, 网络）分账、UDP 只认真实回显、降级作废旧回切资格、组关闭停拨备用、自启下发→回读→落库闭环、`boot.json` 三态。其 Release/tag 已在 preview.3 发布后（2026-11-21T08:13:09Z）清理。

### v3.0.18-preview.1 已发布说明

[v3.0.18-preview.1](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.1) 已作为稳定性预览版发布：main `0a30baaf939a97b1941210609ce377e00d386157`（轻量 tag），`Preview Build` run `44516333684` 四作业全绿、2026-11-19T11:55:25Z 上架；预览元数据 `2036`，APK `10181` / 模块 `2036`。内容为 PR #77（层级状态按 TCP/UDP 拆分）与 PR #78（UI 对齐模块功能）。已知边界：一次探测的观测仍按 tag 共享、嵌套组 summary 与网络无关。本版 Release 已于 11:55:35Z 在 preview.2 发布时由 `prune_prereleases.py` 清理（现为 404）。

### v3.0.17-preview.4 已发布说明

基于 main `906a6ea3f48779cc96c7a4116cfd1faeb63aa354`（PR #72，`Verify Android Build` run `44372679147`），`Preview Build` run `44378329740` 于 2026-11-18T19:38:07Z 发布；APK `10174` / 模块 `2034`。内容为故障转移限定生效层、过期候选兜底、direct 有界重试、`su` 超时与空白名单分离，另收口三项。Release/tag 已在 `v3.0.18-preview.1` 发布后清理（11:55:35Z），下载链接现为 404。

v3.0.17-preview.3 的已发布说明：tag/commit `6adc5ac0`，PR #69，`Verify Android Build` run `44295650161`，`Preview Build` run `44306599880` 于 2026-11-18T13:08:01Z 发布；APK `10168` / 模块 `2033`，Release/tag 已在 preview.4 发布后（19:38:15Z）按单预览策略清理。

v3.0.17-preview.2 的已发布说明：tag/commit `49e96619`，`Preview Build` run `44281162788` 于 2026-11-18T10:18:19Z 发布；APK `10162` / 模块 `2032`，Release/tag 已在 preview.3 发布后按单预览策略清理。

v3.0.17-preview.1 的已发布说明：tag/commit `33391d1`，`Preview Build` run `44076008956` 于 2026-11-17T12:01:45Z 发布；内容为悬空出站引用下放弃编辑不再死循环与配置构建 Toast 改主线程分发，APK `10156` / 模块 `2031`；其 Release/tag 已在 preview.2 发布后按单预览策略清理。v3.0.16-preview.4 的已发布说明：tag/commit `9283ccb`，`Preview Build` run `44013891166` 于 2026-11-17T04:18:56Z 发布；内容为组成员单一真相、订阅撞名避让、选中节点显式回落、恢复失败逃生口与设置口径统一，APK `10154` / 模块 `2030`。v3.0.16-preview.3 的已发布说明：tag `fa6b95b`，首发 `Preview Build` run `43906481811`，资产由 main `000d302` 经受审查流程替换。v3.0.14-preview.1、v3.0.15-preview.1–.4 与 v3.0.16-preview.1、v3.0.16-preview.2 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](../CHANGELOG.md)。
