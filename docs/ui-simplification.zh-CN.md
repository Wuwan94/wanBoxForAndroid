# UI 简化改造方案（2026-10-18）

作者：@author 雾晚
基线：`v3.0.10-preview.4`（`2ec65c8c2`）

## 一、目标与边界

把前台收敛为「轻量管理前端」：只负责状态展示、节点与订阅管理、少量设置；服务生命周期、配置生成、状态守护、路由应用仍在模块侧。

不改动的部分：包名、签名、Room 数据库、备份格式、模块 HTTPS 接口、节点热切换链路、`ConfigBuilder`。

## 二、Root 标识清理

| 位置 | 原文 | 现状 |
| --- | --- | --- |
| `layout_dashboard.xml` 顶部小标题 | 硬编码 `android:text="Root"` | 删除，外层加 12dp 顶部内边距 |
| 服务开关无障碍描述 | 「Root 模块管理」 | 「服务开关」 |
| `root_module_mode` | 「Root 模块（Magisk / KernelSU）」 | 「模块服务」 |
| `root_module_action_failed` | 「请授予 Root 权限，安装并启用配套 wanBox 模块后重试」 | 「请确认配套 wanBox 模块已安装并启用后重试」 |
| `root_tun_settings` | 「Root TUN 设置」 | 「网络设置」 |
| `root_module_data_retry` | 「检查 Root 权限和模块状态」 | 「检查模块状态」 |
| `root_module_data_pending` | 追加「这一状态不表示缺少 Root 权限」 | 删除该句 |
| 文档页徽章 | 「仅支持: Root 模块」 | 「仅支持: 模块服务」 |
| 文档页说明 | 「需…授予 Root 权限」 | 「需先安装并启用对应架构的配套模块」 |

说明：设备侧 Root 仍是运行前提，本次只清理 **App 界面与提示中的 Root 表述**，不改变模块能力，也不引入 VPN 模式。`RootModuleClient` 的类名与 `root_required` 错误码属于内部标识，未改，避免波及 300 余处调用点。

## 三、设置页信息架构（8 组 → 6 组）

| 一级入口 | 包含的分区 | 说明 |
| --- | --- | --- |
| 运行 | `categoryRuntime` | 端口、分应用代理、TUN 参数、性能优先级 |
| 网络 | `categoryDns` + `categoryRouting` | DNS 与路由规则合并 |
| 测速 | `categorySpeedTest` | 测速模式与超时 |
| 外观 | `categoryAppearance` | 主题、卡片样式、语言 |
| 高级设置 | `categoryHttpTls` + `categoryLogsDiagnostics` | TLS 分片、日志级别等非常用项 |
| 其他 | `categoryOther` | 备份、关于、重置 |

- 分应用代理保留为顶部独立入口。
- 设置首页右上角的「工具」「文档」两个二级入口移除。
- 合并只改变一级入口的可见性，`SettingsPreferenceFragment` 仍按 section 渲染，**偏好键与存储结构未变**，覆盖升级不丢设置。

## 四、状态刷新改为 SSE 优先

`RootTunService` 此前每 2 秒执行一次 `su -c wanboxctl dashboard`，节点切换时并发 su 会加剧抖动。现在：

1. 绑定后启动 `ModuleApiClient.events()` 订阅 `/v1/events`；
2. 收到事件即调用 `applyStatus()`，与轮询共用同一应用点，行为一致；
3. `sseActive` 为真时跳过轮询；轮询间隔从 2 秒放宽到 5 秒，仅作兜底；
4. SSE 连接失败（模块未升级、凭据缺失、流断开）静默退出，轮询接管，不影响既有行为。

这样节点切换后的状态由模块推送，不再依赖下一次 `su` 采样，也减少了切换期间与 `node select` 抢占 su 的概率。

## 五、改动文件

| 文件 | 改动 |
| --- | --- |
| `ui/SettingsHomeCompose.kt` | 枚举 8 组改为 6 组 |
| `ui/SettingsFragment.kt` | 移除右上角菜单；标题映射收敛 |
| `ui/SettingsPreferenceFragment.kt` | `network` / `advanced` 映射到多个 category |
| `ui/DocsFragment.kt` | 两处 Root 表述改为中性 |
| `res/layout/layout_dashboard.xml` | 删除 "Root" 标题 |
| `res/values{,-zh-rCN}/root_module.xml` | 模块文案去 Root |
| `res/values/minimal.xml` | 开关无障碍描述 |
| `res/values{,-zh-rCN}/strings.xml` | 新增 `settings_group_network` |
| `androidTest/MinimalUiTest.kt` | 分组断言 `dns` → `network` |
| `bg/RootTunService.kt` | 抽出 `applyStatus`/`sampleStatus`，新增 SSE 订阅 |
| `bg/ModuleApiClient.kt` | 事件流 `flowOn(Dispatchers.IO)` |

## 六、验证

**已完成**

- [x] `:app:testOssDebugUnitTest` 313 项通过
- [x] Kotlin 编译通过，资源合并通过
- [x] 全仓库复检：界面层（layout/xml/kt）不再有用户可见的 Root 字样

**待验证**

- [ ] 仪器测试 `MinimalUiTest` 需在模拟器运行（分组已改为 `network`，断言同步更新）
- [ ] 真机：设置页六个入口均可进入，网络组内 DNS 与路由都可见
- [ ] 真机：节点切换时 SSE 生效、按钮不闪；模块为旧版时能回退轮询
- [ ] 真机：覆盖安装后设置项与备份不受影响

## 七、未做与风险

- **未接入 `/v1/nodes`、`/v1/subscriptions`**：节点与订阅仍走原有 Room + snapshot 路径，本次只改状态刷新通道。
- **`RootModuleClient` 未替换**：`start/stop/apply` 仍走 su，`ModuleApiClient` 只承担状态流。彻底切换需先补配置写入接口。
- **SSE 依赖模块已升级**：若设备上仍是 preview.3 及更早的模块（`service.sh` 未改为 `serve`），事件流连不上，会自动回退，但也就拿不到推送收益。
- **仪器测试未本地运行**：本机无模拟器，`MinimalUiTest` 的断言改动只做了静态检查。
