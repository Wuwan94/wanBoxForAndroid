# 设计：UI 按模块功能对齐（批次 3）

## 对照表（模块能力 ↔ 界面，2026-11-15 盘点）

| 模块能力 | 界面 | 结论 |
|---|---|---|
| 启停/恢复（start/stop/resume） | 首页连接按钮 | 已对齐 |
| 状态与阶段（status/dashboard/channel） | 首页 Hero + 设置 Root 区状态行 | 已对齐 |
| 遥测（内存/连接数/运行时长/速率） | 首页统计区 | 已对齐 |
| 节点选择（node select/tags） | 节点页（ConfigurationFragment） | 已对齐 |
| 节点故障转移（node failover） | 首页故障转移按钮 + 健康检查区「立即转移」 | 已对齐 |
| 智能自动停（策略+规则） | 设置：开关 + 规则编辑（解析校验）+ 通知权限入口；首页挂起态与恢复 | 已对齐 |
| 健康检查与回滚（health） | 设置健康检查模式（notify/auto/off）；首页 degraded 提示 | 已对齐 |
| 热更新（hot update） | 设置 Root 区模块版本码（moduleVersionFlow 实时跟随） | 已对齐 |
| 配置下发与校验（config apply/validate） | 服务流程 + 缺失出站警告弹窗 | 已对齐 |
| 安装数据选择（install data） | 安装选择流程（data selection-finish） | 已对齐 |
| 混合入站（mixed inbound） | 设置混合端口/认证 + 局域网分享 | 已对齐（ConfigBuilder 实际生成） |
| 规则资产更新（data update） | AssetsActivity（rulesProvider/GeoIP/GeoSite 均有消费） | 已对齐 |
| 路由模式同步（mode sync） | 首页模式按钮（规则/全局/直连） | 已对齐 |
| 音量键菜单（volume key） | 模块安装期交互，非运行时功能 | 不需界面 |
| cgroup 内存治理 | 模块不上报状态 | 无从呈现，记录在案 |
| **退出诊断（lastExit/lastReason/lastExitAt/retryCount/events）** | **仅首页失败时引用一句 lastReason，其余从未呈现** | **本批补齐** |

设置项全量核查：global_preferences 59 个键均有消费方（类别键除外）；
底部四栏导航与全部 Activity 均可达。无可证明的死入口，本批不删。

## 实现

- 设置页 SettingsPreferenceFragment 的 `section == "root"` 区已有
  `RootModuleClient.call("status")` 调用（现只取 connected 与版本码）：
  保留其 Status，新增诊断呈现——纯格式化函数（新文件
  `bg/ModuleDiagnostics.kt`）把 Status 映射为有序的诊断行
  （健康状态、最近退出原因、退出码、退出时间、重试次数、最近事件），
  Fragment 只负责把行渲染为 Preference，各行按 formatter 返回顺序分配递增 order。
  页面打开时保持普通 `call("status")`；在已有 moduleVersionFlow 的 collect 处
  通过 `callFreshStatus()` 跳过缓存聚合重取新鲜状态，并重新渲染诊断行，
  避免版本变化时仍命中 4 秒有效期内的旧 status。刷新触发点为页面打开与版本流变化，不承诺实时轮询，
  不新增轮询或其他订阅；重取失败时保留上次成功的诊断，协程取消继续传播。
- 诊断行规则：字段为空/为零/缺失则该行不出；事件最多取最近 5 条、
  最新在前，同秒事件按源列表索引倒序选择，每条含时间与事件码原文（事件码是诊断码，与日志同级呈现）；
  退出时间按本地时区格式化。
- lastExitAt：Go 状态含 `lastExitAt`（epoch 秒）；Kotlin Status 若未解析，
  在解析处补可选字段（默认 0），不改协议必填项。
- 文案进字符串资源，同步 values 与 values-zh-rCN。

## 测试

- ModuleDiagnostics 格式化纯函数单测：全字段呈现与排序、缺字段隐藏、
  事件截断为 5 条且最新在前、旧模块（全空）返回空列表。
- Status 解析单测（若新增 lastExitAt 解析）：含该字段与不含该字段两种负载。
