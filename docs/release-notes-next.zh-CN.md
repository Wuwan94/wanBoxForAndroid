# v3.0.19-preview.1 发行说明

**状态：已发布的稳定性预览版。**

本版收口 `v3.0.18-preview.4` 发布后合入的一项新功能：PR #90（合并提交 `f3d8a67a443b2c450ba404683d97ef51f8b26d9f`，7 个文件 +1820/−4）新增 VLESS XHTTP 服务端入站。`3.0.18` 线已满 `preview.4` 上限，本版按版本规则开新线 `3.0.19`。正式线 `3.0.17 / 2035`（APK `10175`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 均未变；预览元数据为 `3.0.19-preview.1 / 2040`，派生 APK `versionCode=10201`、Root 模块码 `2040`（已发布 preview.4 为 `10199 / 2039`）。`Preview Build` run `45338292530` 于 2026-11-24T07:17:56Z 发布（tag `94a9c9b895ccaf757119abdada3de4153e05aad2`，升版 PR #91 合并提交；前两次尝试的冷启动测量作业因 Maven Central 429 限流失败，第 3 次尝试四作业全绿），发布后已清理 `v3.0.18-preview.4`。四件套：APK `6381543` B、普通 Root ZIP `18295938` B、合一 ZIP `23954760` B、`SHA256SUMS` `333` B，APK + 普通模块 `24677481` B（23.53 MiB）→ PASS。

## ✦ 与上一版对比

| 项目 | v3.0.18-preview.4 | v3.0.19-preview.1 |
|---|---|---|
| 内容 | UDP 回包按来源校验、截止判定先认取消；门代次变化仍删失败证据；自启结果可重放且只消费一次、锁外做 Room/IPC；UDP 迟到回包用例 | 新增 VLESS XHTTP 服务端入站（经自定义 JSON 配置启用）；顺带修复 XHTTP 客户端请求头顺序与上传字节计数 |
| APK `versionCode` | 10199 | 10201 |
| Root 模块码 | 2039 | 2040 |

URLTest 间隔 ≥10 秒、空闲 ≥30 分钟、切换不中断现有连接与「连续 3 轮健康 + 60 秒无失败」回切门均未放宽；证据不足时停在当前层级，不回切。

## ✦ 本版更新

1. **VLESS XHTTP 服务端入站**：libcore 在入站注册表覆盖 VLESS 入站，非 XHTTP 传输原样委托官方实现；XHTTP 时启用自有服务端传输，支持 `auto` / `packet-up` / `stream-up` / `stream-one`、HTTP/1.1 与 HTTP/2/h2c、TLS、多种 session/seq/数据放置与 X-Padding 校验、CORS 预检，并设会话数、缓冲字节与并发上行等资源上限。经现有节点自定义 JSON 配置路径启用，不新增向导；官方 sing-box 内核与 `SINGBOX_VERSION` 未改。
2. **XHTTP 客户端两处修复**：出站请求头构造顺序修正（会话头不再被整体替换冲掉）；`uploadWriter` 改为写前记录长度，上传字节计数不再偏小。

## ✦ 升级与验证边界

同一 Release 的瘦管理器 APK 与 Root 模块必须成套安装；回退用正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17) 的 APK 与配套模块（模块码 `2035`）。升级方向：APK `10199 → 10201`、模块 `2039 → 2040`，严格递增且同签名，可直接覆盖安装。

PR 门禁：PR #90 的 `Verify Android Build` run `45251550225`（head `35c9256a`）全绿；升版 PR #91 的 Verify run `45328063751` 首跑红于 3 个组编辑器插桩测试、重跑后全绿（抖动）。新增回归：`vless_xhttp_inbound_test.go` 端到端往返（四模式、TLS+HTTP/2、header/cookie 放置、GET 上行，及普通 VLESS / WebSocket 入站回归）与 `server_test.go` 的校验、填充互通与缓冲上限用例。

未执行 Root 真机矩阵（CI 自标 `runtime_regression: not_verified`）；与真实 Xray 客户端的端到端互通未验证（自动化为本项目客户端↔服务端往返）；服务端入站的 OpenSpec 变更已由 PR #92 补齐并合入主规范。自动化结果不等同于真机验证，本版不晋升正式版。

## 历史归档

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
