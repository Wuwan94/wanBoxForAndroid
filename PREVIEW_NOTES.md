# v3.0.17-preview.3 发行说明

**状态：已发布的稳定性预览版。**

本版基于 `main` 的 `6adc5ac0bcbd87bf9ff4b2097fe367761a4bc841`（PR #69，`Verify Android Build` run `44295650161` 全绿后合并）。正式稳定版 `v3.0.14`（`VERSION_CODE=2026`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 均未改变；预览元数据为 `PRE_VERSION_CODE=2033`，派生 APK `versionCode=10168`、Root 模块码 `2033`，均高于已发布 preview.2 的 `10162` / `2032`。

## ✦ 与上一版对比

| 项目 | v3.0.17-preview.2 | v3.0.17-preview.3 |
|---|---|---|
| 内容 | 两路借鉴移植合集首个预览 | 修复 URLTest 预算到期后层级永不回切、诊断包 Android 12+ 不可读取；收口八项 P2 与 Room 11 schema |
| APK `versionCode` | 10162 | 10168 |
| Root 模块码 | 2032 | 2033 |

URLTest 稳定性约束（检测间隔 ≥ 10 秒、空闲超时 ≥ 30 分钟、切换时不中断现有连接）、`default` 保真、候选应用的版本 CAS 与恢复校验链本版均未放宽。

## ✦ 本版更新

### 1. P1：URLTest 预算到期后的层级回切

- 整轮 5 秒探测预算到期时，旧实现直接按上下文错误返回，跳过轮次收尾；层级恢复门的 `consecutiveOK` 永远为 0，成员多且含黑洞节点时一旦切到低层，高层恢复也不再回切。
- 现在只要本轮已有探测结果或新鲜历史，且不是组关闭/调用方取消，仍执行轮次收尾与选择重算；新增回归稳定模拟预算到期，验证连续 3 轮且满 60 秒后回切高层。

### 2. P1：脱敏诊断包在 Android 12+ 可读取

- 模块以 root 写入 Download 时旧实现固定 0600，无属主交接、无媒体扫描，Android 12+ 文件管理器与分享面板无权打开。
- 现在最终文件 0644，并按目标目录 `stat.Uid/Gid` 执行 `os.Chown`；目录 stat 失败或非 root 环境安全降级。App 侧导出成功后调用 `MediaScannerConnection.scanFile` 刷新媒体库。普通文件与拒绝符号链接的 Fail-Closed 校验不变，脱敏规则不变。

### 3. 高价值 P2

- **Room 11 schema**：生成并提交 `app/schemas/io.nekohasekai.sagernet.database.SagerDatabase/11.json`，使后续 11→12 迁移可 diff 评审。
- **非法网络规则留痕**：`readUnifiedPolicy` 错误不再被丢弃；记录限频 `network_policy_invalid`，避免按网络规则静默失效。
- **direct 失败去抖**：`SetRuntimeMode` 失败后锁存，只在动作状态变化时重试，不再每个 health tick 刷 `network_direct_failed`。
- **su 有界等待**：`PackageCache` 的 `su -c` 改为读线程 + 5 秒超时，超时 `destroyForcibly()` 并返回空表，避免未响应 Root 授权锁死 `multiUserMutex` 与配置生成。
- **空白名单显式报错**：分应用名单非空但过滤 `uid < 1000` 后为空时抛 `per_user_app_whitelist_empty`，不再静默变成“不代理任何应用”。
- **停核测速互斥**：Root 短生命周期测试会话加非阻塞互斥，并发时返回 `url_test_busy`，避免同时拉起多个共享模块目录的 rootbox。
- **故障转移与日志**：故障转移按新鲜历史过滤/排序候选，成功后提升当前选择并尊重 `interruptExternalConnections`；订阅更新日志只记 profile ID，不再输出 `displayName()`。
- **死代码与注释对齐**：删除 Go 侧零引用且与 Kotlin 重复的 `rootmodule/per_user_apps.go` 及其用例；`BalancerTierScheduler` 核实为生产在用（ConfigBuilder/设置页），未删除；`SystemExcludedPackages` 与 `ProfileManager` 的注释改为真实行为（集中目录，不自动改写显式规则）。

## 📦 版本、资产与安装

- **运行要求**：Android 12+（API 31+）、arm64-v8a、Magisk / KernelSU / APatch 等 Root 环境。
- **版本对齐**：APK `versionName=3.0.17-preview.3` / `versionCode=10168`；Root 模块码 `2033`。瘦管理器和 Root 模块必须来自同一 Release、同版成套使用。
- **回退**：使用正式稳定版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 与配套 Root 模块（模块码 `2026`）；不要混用不同 Release 的 APK 和模块。

## 🛡️ 验证与边界

- PR #69 的 `Verify Android Build` run `44295650161` 全绿；发布 `Preview Build` run `44306599880` 的 Native Build (LibCore)、Build OSS APK、Candidate cold-start measurement 与 Publish Preview Release 四个 job 全部成功，发布 job 断言 `APPROVED_SHA=6adc5ac0`。本地 Windows 无法执行 `-race`（需要 cgo）与 Linux cgroup/channel 用例，已用目标回归与平台无关子集验证。
- 未执行 Magisk / KernelSU / APatch 真机矩阵：Android 12+ 诊断包实际可见性、Root 测试会话并发与层级回切仍需真机确认；任何自动化结果都不代替真机验证，本版未晋升正式版。

---

## 历史归档

### v3.0.17-preview.2 已发布说明

[v3.0.17-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.2) 已作为稳定性预览版发布（tag/commit `49e966197dd608b687b36eecdaf394274dacfe15`，PR #67/#68；`Preview Build` run `44281162788` 于 2026-11-18T10:18:19Z 发布）。内容为 OwnBox/NetProxy 两路借鉴移植合并后的首个预览：核心稳定性与故障转移、订阅自动更新、智能分流、冷启动秒连、策略组混搭/锁定/优先级、停核可编辑与脱敏诊断包、按网络规则、路由规则指定策略组；APK `10162` / 模块 `2032`。资产快照见[版本对照](docs/current-version.zh-CN.md)；其 Release/tag 已在 preview.3 发布后按单预览策略清理，下载链接不再可用。

### v3.0.17-preview.1 已发布说明

[v3.0.17-preview.1](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.1) 已作为稳定性预览版发布（tag/commit `33391d1`，PR #65；`Preview Build` run `44076008956` 于 2026-11-17T12:01:45Z 发布）。内容为两处修复：悬空出站引用下「放弃未完成编辑」不再死循环，配置构建的规则告警改为主线程分发；APK `10156` / 模块 `2031`。资产快照见[版本对照](docs/current-version.zh-CN.md)；其 Release/tag 已在 preview.2 发布后按单预览策略清理，下载链接不再可用。

### v3.0.16-preview.4 已发布说明

[v3.0.16-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.16-preview.4) 已作为稳定性预览版发布（tag/commit `9283ccb`，PR #62；`Preview Build` run `44013891166` 于 2026-11-17T04:18:56Z 发布）。内容为 preview.3 的收口修复：策略组成员以节点归属为唯一真相、订阅节点与组重名时自动加后缀、选中节点移出主组时显式回落、恢复失败时可放弃未完成编辑、组设置页与编辑器统一锁定「中断现有连接」；APK `10154` / 模块 `2030`。该版暴露的「悬空出站引用下放弃编辑死循环」已由 preview.1 修复。资产快照见[版本对照](docs/current-version.zh-CN.md)；其 Release/tag 已在 preview.1 发布后按单预览策略清理，下载链接不再可用。

### v3.0.16-preview.3 已发布说明

[v3.0.16-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.16-preview.3) 已作为稳定性预览版发布（tag `fa6b95b`，首发 `Preview Build` run `43906481811`；资产于 2026-11-17 由 main `000d302` 的候选 run `43939217515` 经受审查流程替换）。内容为四批次汇总：图形化策略组编辑、策略组浏览/选择/测速、统一资源页与重名消歧、备份与迁移规范化（「兼容旧版」导出未开放：尚无经真实旧版读取器验证的写入器，入口禁用并明确说明）、模块退出诊断上屏、网关缓存深拷贝；APK `10148` / 模块 `2029`。未执行 Root 真机矩阵。资产快照见[版本对照](docs/current-version.zh-CN.md)；其 Release/tag 已按单预览策略清理。

### 更早预览

v3.0.14-preview.1、v3.0.15-preview.1–.3、v3.0.15-preview.4 与 v3.0.16-preview.1、v3.0.16-preview.2 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。
