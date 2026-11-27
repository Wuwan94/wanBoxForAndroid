# 2026-11 上游同步评估记录（NetProxy-Magisk / OwnBoxForAndroid）

作者：雾晚

> 记录范围：本文补记上一轮同步评估的逐项结论，只写能从上游 Release 说明、wanBox PR 历史或代码现状查到的事实。推断一律标「推断」；没查到依据的写「未单独评估」。本文只是文档，不改代码，也不改任何已有结论的口径。
>
> 核对基线：wanBox `origin/main` `303aa996567969ce0801ce5c7c30740f5404ae31`（PR #116 合并提交）；对照的 PR 范围是 #96–#113。

## 一、NetProxy-Magisk v8.7.0 → v8.7.2

来源：[v8.7.1 Release](https://github.com/Fanju6/NetProxy-Magisk/releases/tag/v8.7.1)（2026-11-19）、[v8.7.2 Release](https://github.com/Fanju6/NetProxy-Magisk/releases/tag/v8.7.2)（2026-11-22），以及 `v8.7.0...v8.7.2` 的 15 个提交。

拆项方法：两版 Release 说明共 12 条。其中两组各属同一主题，合并为一项，于是得到 10 项：MIUIX 同步（v8.7.1「统一采用 MIUIX OS4 Glass」与 v8.7.2「同步 MIUIX Glass 0.9.5-beta03」），以及代理页策略组标签（v8.7.1「标签可左右滚动」与 v8.7.2「直接显示原生标签」）。

| # | 上游变更（版本） | wanBox 现状 | 结论 | 落点 |
|---|---|---|---|---|
| 1 | 管理器统一用 MIUIX OS4 Glass，后续同步 MIUIX Glass 0.9.5-beta03（8.7.1 / 8.7.2） | App 的 Gradle 与版本目录里没有 MIUIX 依赖，UI 栈不同 | 不适用 | — |
| 2 | 代理页策略组标签可横向滚动，新增/编辑/删除集中到「管理策略组」菜单；之后改为显示原生标签、少量组时均分（8.7.1 / 8.7.2） | PR #96–#113 没有相关改动 | 未单独评估 | — |
| 3 | 修复前后台切换时服务卡片反复收起、选中标记闪烁；重新确认核心状态前不发节点切换（8.7.1） | PR #96–#113 没有相关改动 | 未单独评估 | — |
| 4 | 应用代理改用右上角搜索；JSON 快捷工具栏可横向滚动并避让键盘；日志页操作收进菜单，清空前要确认（8.7.1） | PR #96–#113 没有相关改动 | 未单独评估 | — |
| 5 | 新建路由规则可「指定策略组」（8.7.1；上游提交 `6f2362d9`） | 已由 PR #68（B7 / N6）落地：`RuleEntity` 新增可空策略组目标，规则写入原生 `route.rules`。见 `openspec/changes/borrow-netproxy-nightly/proposal.md`，核对时上游 HEAD 就是 `6f2362d98bb0…` | 已借鉴 | PR #68；`openspec/changes/borrow-netproxy-nightly/` |
| 6 | 更新内置广告拦截、中国 IP、非中国地域规则集（8.7.1） | wanBox 的规则资产由 `tools/prepare_geo_assets.py` 自行准备；PR #96–#113 没有改动 | 未单独评估 | — |
| 7 | 默认配置只保留 `Proxy` 和 `Auto` 两个策略组，`Proxy` 默认选 `Auto`（8.7.2） | PR #96–#113 没有相关改动 | 未单独评估 | — |
| 8 | 「按订阅创建」独立策略组，不复制节点、不改路由（8.7.2） | PR #96–#113 没有相关改动 | 未单独评估 | — |
| 9 | 修复冷启动或切回时仪表盘误跳到代理页（8.7.2） | PR #96–#113 没有相关改动 | 未单独评估 | — |
| 10 | 手动测速结果重进管理器后恢复显示，sing-box 重启后不沿用旧结果（8.7.2） | PR #96–#113 没有相关改动 | 未单独评估 | — |

### 附：v8.7.3 中与已知结论相关的条目

工作词把「诊断包脱敏与体积限制」「默认 TUN 接管」列为已知事实。按上游 Release 说明，这两项出自 [v8.7.3](https://github.com/Fanju6/NetProxy-Magisk/releases/tag/v8.7.3)（2026-11-25），不在 8.7.0→8.7.2 范围内，所以单独列出，以免和上表的 10 项混在一起。同版的 Google 联网兼容也一并记在这里。

| 上游变更（8.7.3） | wanBox 现状 | 结论 | 落点 |
|---|---|---|---|
| 完善诊断包 TLS 私钥脱敏，并限制诊断归档大小 | 部分承接。PR #111 在 `rootmodule/diagnostics.go` 增加 PEM 私钥块、`client_key` / `client_certificate` 行内和 JSON 键脱敏；体积方面，PR #68（B5 / N2，提交 `6a42503`）已有单份日志尾部上限 `MaxDiagnosticsLogBytes = 4 MiB`。上游「归档总大小」限制是否与之等价，未单独评估 | 已借鉴（部分） | PR #111、PR #68 |
| 全新安装默认用 TUN 接管本机流量 | 没有引入；与 wanBox 既定形态冲突 | 不借鉴 | — |
| Google 联网兼容（ColorOS 16、红魔/努比亚/中兴） | 已由 PR #106 按严格白名单落地，对照上游提交 `0c29f0c5`；见 `openspec/changes/root-google-firewall-compat/design.md` | 已借鉴 | PR #106 |
| 停服时标出并可更换 Selector 默认节点 | 与 PR #68 的 N1「停核可编辑」相关（推断：方向相近，但未逐项比对）；「默认节点标注」本身未单独评估 | 未单独评估 | — |
| 内核换成 reF1nd `reF1nd-testing-next` 快照 `77c0dbbe` | wanBox 固定 `SINGBOX_VERSION`，不依赖 fork | 不借鉴 | — |
| 修复配置事务恢复会重启已停服务；状态文件损坏不再误报停服 | PR #96–#113 没有相关改动 | 未单独评估 | — |
| MIUIX Glass 0.9.5-beta04；减少策略组刷新与订阅更新的重复工作 | MIUIX 部分同上表第 1 项；重复工作优化未单独评估 | 不适用 / 未单独评估 | — |

## 二、OwnBoxForAndroid v26.11.24.6-preview

来源：[v26.11.24.6-preview Release](https://github.com/Own716/OwnBoxForAndroid/releases/tag/v26.11.24.6-preview)，Release 说明共 5 条。

| # | 上游变更 | wanBox 现状 | 结论 | 落点 |
|---|---|---|---|---|
| 1 | 控制中心磁贴随所选图标实时联动，并按 58% 画面占比居中渲染单色剪影 | 没有单独落地。现状：`bg/TileService.kt` 已能在用户应用自定义磁贴图标时（`CustomIconManager.isTileApplied`）显示该图，并经 `ACTION_REFRESH_ICON` 刷新；这套机制在仓库起始提交 `790f33e` 就已存在，没有按上游比例重做。PR #96–#113 没有改磁贴文件 | 未单独落地 | — |
| 2 | 灵动岛与通知栏剪影图标放大（画布利用率提升到 79%） | 没有单独落地。PR #96–#113 里只有 PR #109 动过 `ServiceNotification.kt`，改的是协议标签文案，不涉及图标资源 | 未单独落地 | — |
| 3 | 双猫图标抠成纯白底 | 上游品牌图形资产，没有复制 | 不借鉴（品牌资产） | — |
| 4 | 图标选择弹窗前三项恢复原版四柱图形 | 上游品牌图形资产（四柱），没有复制；wanBox 有自己的图标选择（`ui/CustomIconFragment.kt`） | 不借鉴（品牌资产） | — |
| 5 | 版本号升到 v26.11.24.6（Build 536），升级后自动重置为四柱默认图标 | 上游自身版本与默认图标的迁移；wanBox 版本码只由 `tools/version_code.py` 生成 | 不适用 | — |

## 三、复核记录

交付前逐个对照了 PR #96–#113 的标题与改动文件清单（GitHub API `pulls/{n}` 与 `pulls/{n}/files`）：

- 与上表相关的只有三个 PR：#106（Google 联网兼容）、#109（`ServiceNotification.kt` 的协议标签）、#111（`rootmodule/diagnostics.go` 的脱敏）。
- 其余 PR 是 XHTTP、互通门禁、`urltest`、edge-to-edge、WebUI、VLESS 入口、升版和文档，均不影响上表结论。
- PR #97 未合并。
