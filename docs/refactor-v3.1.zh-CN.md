# wanBox 重构方案：模块持有一切 + App 零 Root 轻前端

> 参照基准：NetProxy-Magisk v8.6.1 的「模块是服务与数据唯一真相源、管理器零 Root 只做前端」。
> 本文分两部分：**已落地的故障修复（preview.7）** 与 **后续重构的分阶段清单**。

## 一、故障根因（已定位并修复）

「模块服务暂时不可用」不是模块没起来，而是**凭据从来没交付到 App 能读的位置**，链路断在最后一步：

| 环节 | 事实 | 结论 |
|---|---|---|
| 模块宿主 | `service.sh` → `wanboxctl __internal serve`，先起 HTTPS 再拉起核心 | 正常 |
| 监听地址 | `api.json` 初始 `127.0.0.1:0`，绑定后回写真实端口 | 正常 |
| 鉴权 | 每安装独立 token + 自签证书 SHA-256 钉扎 + 仅回环 | 正常 |
| 凭据交付 | 模块只写 `/data/adb/wanbox/api.json`（0700 root） | **断点**：App 无法遍历该目录 |
| App 读取 | 默认 `FileCredentialBridge` 只读 `noBackupFilesDir/module-api.json` | **断点**：无人写该文件 |
| 结果 | `module_credentials_unavailable` → 所有请求失败 → 仪表盘「未连接」+ 通用错误提示 | 与现场完全一致 |

附带缺陷：`api.json` 记录过的端口若被占用，`net.Listen` 直接失败，管理面整条失效（只记录事件不恢复）。

### 已修复（preview.7）

1. 新增 `rootmodule/api_delivery.go`：`Serve` 在**启动、端口回写后、每 5 分钟**把 `{listen,token,certSha256}` 写到
   `/data/user/0/<pkg>/no_backup/module-api.json`、`/data/user_de/0/<pkg>/no_backup/module-api.json`、`/storage/emulated/0/Android/data/<pkg>/files/module-api.json`；
   私有目录写入后按 App 数据目录属主 `chown`、`chmod 0600`、`restorecon -R`，外置目录改为 0644（FUSE 授权）。
2. `Serve` 的 `net.Listen` 失败时退回 `127.0.0.1:0` 重试一次，成功即回写端口并重新交付。
3. App 侧 `ManagedFilesCredentialBridge` 按 `no_backup → filesDir → 外置私有目录` 顺序查找凭据；
   删除 `SuCredentialBridge`，App 不再有任何 `su` 调用。
4. 传输层区分两类失败：业务错误（`ModuleApiException`，不重试）与传输错误（端口变化/模块重启，丢弃缓存重读凭据重试一次）。
5. `PackageCache` 跨用户 UID 查询默认关闭（`allowRootUidLookup = false`），消除最后一处会弹 Root 授权的调用。

## 二、目标架构

```
┌─────────────────────────── 模块（root，唯一真相源） ───────────────────────────┐
│ service.sh → wanboxctl __internal serve                                      │
│   ├─ HTTPS 管理面（127.0.0.1:<port>，token + 证书钉扎）                       │
│   ├─ store/ (nodes.json, subscriptions.json, config.json, meta.json)         │
│   ├─ runtime/ (state.json, selection.json, 统计)                             │
│   ├─ generations/<rev>/ (已应用的完整 sing-box 配置)                          │
│   └─ supervisor (启动/监督/热切换/开机自启/崩溃重启)                           │
└──────────────────────────────────────────────────────────────────────────────┘
                                ▲ HTTPS + SSE（无 su、无 Binder、无 Room 强推）
┌─────────────────────────── App（零 Root 轻前端） ─────────────────────────────┐
│ 仪表盘：状态/节点名/流量/时长（全部来自 /v1/status 与 /v1/events）             │
│ 节点：列表/选择/测速（选择走 /v1/select，热切换不重启）                        │
│ 订阅：添加/更新（拉取解析在模块侧）                                            │
│ 设置：自启、分应用、DNS/路由预设（以 intent 下发）                             │
└──────────────────────────────────────────────────────────────────────────────┘
```

三条不可违背的边界：
- **数据边界**：节点/订阅/配置的真值只在模块 `store/`；App 的 Room 降级为草稿与备份。
- **生命周期边界**：App 进程死亡不影响核心；模块重启后 App 靠 SSE/轮询自愈。
- **权限边界**：App 不申请 Root、不执行 `su`、不持有可执行文件。

## 三、API 契约（v1，全部统一包裹）

响应包裹：成功 `{"schema":1,"ok":true,...}`，失败 `{"schema":1,"ok":false,"error":"<a-z_1..80>"}`。
鉴权：`Authorization: Bearer <token>`，仅接受回环 Host，证书按 `certSha256` 钉扎。
**全部接口在核心停止时可用**（数据来自文件，不依赖 sing-box 进程）。

| 方法 | 路径 | 请求 | 响应要点 |
|---|---|---|---|
| GET | `/v1/health` | — | 免鉴权，`{ok:true}`，用于探活 |
| GET | `/v1/status` | — | `state{phase,profileId,profileName,revision,runningRevision,stats{tx,rx,uptime}}` |
| GET | `/v1/events` | — | SSE，`data:{"type":"state","state":{...}}`，含 `node_hot_switched` |
| POST | `/v1/select` | `{profileId}` | 只切运行态选择，不重启核心；`{state}` |
| POST | `/v1/service/start` \| `stop` | — | `{state}` |
| POST | `/v1/service/reload` | — | 按 store 现有数据重生成配置并热应用 |
| POST | `/v1/service/autostart` | `{enabled}` | 只写 boot 标记，不重启核心 |
| GET | `/v1/nodes` | — | `{nodes:[{id,type,name,group,subscriptionId,outbound,updatedAt}]}` |
| POST | `/v1/nodes` | 节点体（id 省略） | `{node}` |
| PUT | `/v1/nodes/{id}` | 节点体 | `{node}` |
| DELETE | `/v1/nodes/{id}` | — | `{state}`；删除当前节点前先切走 |
| GET | `/v1/subscriptions` | — | `{subscriptions:[{id,name,url,nodeCount,updatedAt,error}]}` |
| POST | `/v1/subscriptions` | `{name,url}` | `{subscription}` |
| POST | `/v1/subscriptions/{id}/update` | — | 模块侧拉取解析写回，`{subscription}` |
| DELETE | `/v1/subscriptions/{id}` | — | 同时删除其节点 |
| GET | `/v1/config` | — | `{config:{router,inbounds,dns,experimental,autostart,performancePriority}}` |
| PUT | `/v1/config` | 配置意图 | 模块合并节点生成完整配置 → 校验 → generation → Apply |
| POST | `/v1/data/prepare` \| `finish` \| `rollback` \| `selection-finish` | — / `{id}` | 安装期数据事务 |

约定：
- `outbound` 是 sing-box outbound 原样对象，模块不解释协议字段，只做合并与校验。
- `PUT /v1/config` 的 **intent 不含节点**；节点只经 `/v1/nodes` 维护，杜绝「App 生成整份快照强推」。
- 冲突用 `409` + 具体错误码（`revision_conflict`、`nodes_empty`、`profile_missing`…）。
- 后续新增：`GET /v1/logs?lines=200`、`GET /v1/uids`（跨用户 UID，替代 App 侧 su）。

## 四、App 信息架构（只保留四页）

| 页面 | 只保留 | 明确删除 |
|---|---|---|
| 仪表盘 | 连接开关、当前节点名、上传/下载/总流量、运行时长、模块状态徽标 | 内存/连接数占位符、规则模式三段选择（移入设置） |
| 节点 | 节点列表（按订阅分组）、当前选中标记、点击热切换、单节点测速、搜索 | 批量测速面板、列数/字号/间距密度调节、TCP 测速多选 |
| 订阅 | 订阅列表、添加、更新（模块侧拉取）、节点数、最后更新时间、错误提示 | 批量更新多选、筛选规则编辑器（下沉到模块） |
| 设置 | 自启、分应用代理、DNS/路由预设、备份恢复、关于 | 主题外观编辑器、日志诊断二级页（改为模块 `/v1/logs` 简易查看器） |

状态驱动规则：
- 绑定后订阅 `/v1/events`，事件到达即更新；SSE 断开时 5 秒轮询兜底。
- 热切换只更新 `profileId/profileName`，**不触发** `changeState`，控制区不重建、不闪烁。
- 任何失败只显示错误码，不重启界面。

## 五、必须删除的旧实现

| 类别 | 对象 | 原因 |
|---|---|---|
| 传输 | `RootModuleClient.call()` 的命令字符串分支、`wanboxctl` 路径常量 | 已无 su 通道，保留即死代码 |
| 快照 | `RootModuleSnapshot.build()` 整份配置生成、`192MB` 大小校验 | 配置生成下沉模块 |
| 数据 | Room 作为「已应用」真值、`RootModuleDataUpdate` 的 Room 事务链路 | 真值在模块；Room 仅草稿/备份 |
| 配置 | App 侧 `ConfigBuilder` 生成的 outbounds 段 | 模块合并节点 |
| 权限 | `PackageCache.queryMultiUserUids()` 的 su 实现（改为模块 `/v1/uids`） | 零 Root |
| 页面 | 内存/连接数卡片、密度调节、批量测速面板、主题编辑器 | 精简 IA |

## 六、关键文件

模块侧：`api_server.go`（宿主与鉴权）、`api_delivery.go`（**新增**，凭据交付）、`api_nodes.go`（节点/订阅/配置端点）、
`store.go`（文件持久层）、`subscription.go`（拉取解析）、`config_intent.go`（意图 → 完整配置 → Apply）、
`node_select.go`（热切换）、`supervisor.go`（生命周期）、`runtime.go`（状态与事件）。

App 侧：`ModuleApiClient.kt`（唯一传输层）、`RootModuleClient.kt`（薄门面 + `Status` 解析）、
`RootTunService.kt`（SSE 优先 + 轮询兜底）、`ModuleConfigIntent.kt`（intent 构造）、`RootModuleDataUpdate.kt`（数据事务）。

## 七、测试检查清单

**服务可用**
- [ ] 装好模块后 App 首启即可读到凭据（无 Root 弹窗），仪表盘显示真实状态
- [ ] `adb shell su -c 'cat /data/adb/wanbox/api.json'` 与 App 读到的 token 一致
- [ ] `adb shell su -c 'curl -k -H "Authorization: Bearer <token>" https://127.0.0.1:<port>/v1/health'` 返回 `ok:true`
- [ ] 端口被占用时，模块换端口重启管理面并重新交付凭据

**零 Root**
- [ ] App 全程无超级用户授权弹窗；`grep -rn 'ProcessBuilder("su"' app/src/main` 无结果
- [ ] 拒绝 Root 授权的设备上，App 仍可查看状态与切换节点

**核心停止可管理**
- [ ] `POST /v1/service/stop` 后 `/v1/status`、`/v1/nodes`、`/v1/config` 仍 200
- [ ] 核心启动失败（`phase=failed`）时仍可改节点与配置

**热切换不闪烁**
- [ ] 切换节点时控制区不重建，按钮不闪、节点名不跳
- [ ] SSE 收到 `node_hot_switched` 只更新节点名，不触发 `changeState`

**生命周期独立**
- [ ] 杀 App 后 `/v1/status` 仍 `connected`，流量继续增长
- [ ] 重启 App 后自动恢复状态，不重复启动核心

**覆盖安装**
- [ ] 覆盖安装后模块 `store/`、Room、备份文件均保留，节点与订阅不丢
- [ ] App 数据被清除后，模块 5 分钟内自动补交付凭据

## 八、分阶段实施

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0（preview.7，已完成） | 凭据交付、端口退避、App 零 Root 读取、传输重试、跨用户 UID 关闭 su | 装好即连，无 Root 弹窗 |
| P1 | 删除 `RootModuleSnapshot` 与快照强推；`PUT /v1/config` 全量接管配置生成 | 改规则/分应用后配置由模块生成，App 不再上传完整 config |
| P2 | 订阅拉取与筛选全部下沉；`/v1/logs`、`/v1/uids` 补齐；App 删掉 su 残留 | 订阅更新不依赖 App 进程 |
| P3 | 页面精简到四页 IA，删除密度/批量测速/主题编辑器；Room 降级为草稿 | 冷启动到可操作 < 1.5s，页面数量收敛 |
