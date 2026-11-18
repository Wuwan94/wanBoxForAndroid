# Libcore Integration — port-ownbox-26-11-8-20 Delta

## Purpose

移植 OwnBox v26.11.8.5–v26.11.8.20-preview 的 Go 侧改动（O2a、O3、O1 适配版、O4 防护、O6 规则集校验）。
本 delta 不引入 `libcore/protocol/autoselect/`（O8 结论：不采，本轮只评估）；
策略组 Go 侧改动只做在既有 `libcore/protocol/urltest/`、`libcore/protocol/loadbalance/`、
`libcore/dns_box.go`、`libcore/ruleset.go` 与配置生成层。

## Requirements

### Requirement: DNS ExchangeContext.Success 必须发送完成信号

`ExchangeContext.Success()` MUST 在写入解析地址后调用 `c.done()`，与 `RawSuccess`、
`ErrorCode`、`ErrnoCode` 的行为一致；DNS 解析流程 MUST NOT 因缺少完成信号而挂起直至超时。

- **GIVEN** 平台 DNS 回调走 `Success(result)` 返回地址列表
- **WHEN** `Success` 返回
- **THEN** 等待该次解析的调用方收到完成信号并继续，不再挂起至超时

### Requirement: 探测失败惩罚写入续期时间戳，惩罚必须饱和封顶

`URLTest` 批量探测失败时，对已有历史的惩罚写入 MUST 续期 `Time` 为当前时间
（wanBox 既有测试 `TestMemberTimeoutStillPenalizesRecentHistory` 明确要求
`got.Time.After(before.Time)`；与上游 8.13“保持旧 Time”不同，按“自家约束优先”
保留续期——B1 实施时发现此冲突并按既有测试修正，见 design.md B1 实施记录），
惩罚延迟 MUST 用 uint32 求和后饱和封顶 65000（`penalizeURLTestDelay` 等价语义），
MUST NOT 发生 uint16 回绕。单次超时或网络瞬态抖动 MUST NOT 直接抹除历史；
仅当历史已超过 `3 * interval` 时才可删除。

- **GIVEN** 节点历史延迟为 65000 且探测失败
- **WHEN** 写入惩罚历史
- **THEN** 延迟仍为 65000（不回绕为小值），`Time` 续期为当前时间
- **GIVEN** 节点历史延迟为 100 且探测失败
- **WHEN** 写入惩罚历史
- **THEN** 延迟为 1100 以内（单次惩罚增量），`Time` 续期为当前时间

### Requirement: 连续两次探测失败清理故障史

同一成员连续两次探测失败时，`URLTest` MUST 清理其故障历史，防止假死节点被重新选回。
单次失败 MUST 只记惩罚，不得清史；失败计数 MUST 在探测成功后复位。

- **GIVEN** 节点连续两次探测失败
- **WHEN** 第二次失败处理完成
- **THEN** 该节点故障史被清理，下一次选择不再把它当作“近期可用”候选
- **GIVEN** 节点失败一次后探测成功
- **WHEN** 成功结果写入
- **THEN** 连续失败计数复位为 0

### Requirement: 建连单步超时与故障转移

`URLTest.DialContext` 与 `ListenPacket` 对单个候选的尝试 MUST 带单步超时：
取值取 3 秒（用户 2026-11-17 已拍板，与 wanBox 现有探针超时对齐，不用上游的
动态 2–4.5 秒；B1 落地为固定 3 秒，仅用于“还有后备候选可试”的尝试，最后
一个候选不限时）。超时 MUST 只掐断当次尝试，MUST 保留
`interrupt_exist_connections = false`，现有连接 MUST NOT 被打断；
超时失败 MUST 计入该候选的连续失败并触发备用候选转移。

- **GIVEN** 主选节点建连黑洞（不响应）
- **WHEN** 单步超时到达
- **THEN** 当次尝试被掐断并转移到备用候选，前台流量不在该节点上无限等待
- **GIVEN** 建连正在转移
- **WHEN** 检查既有连接
- **THEN** 既有连接不受影响

### Requirement: 冷启动按缓存延迟预排序并在第 0 毫秒预选（B4 已落地）

`URLTestGroup` 启动时 MUST 按 `HistoryStorage` 缓存延迟确定初始选中节点：
已知健康低延迟节点在前、失败节点在后；第 0 毫秒即可预选最佳可用节点，不得等待首轮整组探针。
种子 MUST 只读缓存，不得触发新探测、不得改写 `history.Time`；
缓存延迟过期（超过 `3 * interval`）MUST 视为无种子，按原列表顺序处理。
idle 后的首次连接 MUST 重新按缓存延迟预选，不得沿用过期选择。
（B4 核查结论：Go 侧 `HistoryStorage` 每核新建、冷启动必为空，故不在
`NewURLTestGroup` 里播种；跨重启的缓存是应用层 DB，0 毫秒预选由 B1 的
`ConfigBuilder` 成员预排序 + `selectLocked` 回退首位承载；首轮探针进行中
已有新鲜选择时首连跳过等待，无新鲜选择时仍等首轮探针。）

- **GIVEN** 组内节点 A 缓存延迟 80ms、节点 B 缓存延迟 900ms、节点 C 无有效缓存
- **WHEN** 核心冷启动并首次建连
- **THEN** 首选 A，不等待首轮探针跑完
- **GIVEN** 全部缓存延迟均已超过 `3 * interval`
- **WHEN** 核心冷启动
- **THEN** 不做种子预选，按原列表顺序处理

### Requirement: 批量探针流式就绪（B4 已落地）

`URLTestOutbounds` 批量探针中，单个成员成功 MUST 立即写回 history 并通知更新，
无需等待整批跑完；同轮发现更优节点时 MUST 按容差（`tolerance`）平滑切换。
流式就绪 MUST NOT 缩短用户设定的检查间隔；下次检查倒计时 MUST 按整轮结束重置，
不得按单个节点就绪重置。检测间隔 MUST NOT 被压到 10 秒以下。
（B4 落地：成功分支在释放批量锁后、归属组活跃（`isActive`，未 idle）时调
`performUpdateCheck()`；流式就绪不碰 ticker 与 `lastActive`。）

- **GIVEN** 批量探针中快节点 200ms 返回成功，慢节点仍在等待 3 秒超时
- **WHEN** 快节点成功返回
- **THEN** 立即就绪并可被择优选中，不必等慢节点跑满超时
- **GIVEN** 流式就绪触发了一次切换
- **WHEN** 查看下次检查倒计时
- **THEN** 倒计时仍按整轮结束时刻计算，不被提前重置

### Requirement: URLTest 必须继续实现 OutboundGroup（O4 防护）

wanBox 的 `URLTest` MUST 继续实现 `adapter.OutboundGroup`，不得照抄上游 8.15
对 `AutoSelect` 的移除。wanBox 无 autoselect 出站，不存在“解包绕过学习/记忆/故障转移”的
同类缺陷；移除将破坏 sing-box 路由对 URLTest 组的既有选路契约。

- **GIVEN** 任意 `URLTest` 出站实例
- **WHEN** 做 `adapter.OutboundGroup` 类型断言
- **THEN** 断言成立，且路由仍经由组接口选路

### Requirement: 规则集缓存按实际条数校验，0 规则占位无效

`.srs` 规则集缓存 MUST 在加载与刷新时校验实际规则条数；0 规则占位文件 MUST NOT 被当作有效缓存。
`local:` 本地规则集支持以 B3 核查结论为准。

- **GIVEN** 磁盘上存在 0 规则的 `.srs` 缓存文件
- **WHEN** 核心加载该规则集
- **THEN** 该缓存被判定无效并触发重新拉取或明确报错，不得静默当作有效规则集使用

### Requirement: GeoSite/GeoIP 读取器关闭与流式过滤（B1 核查项）

GeoSite 底层读取器 MUST 在使用后关闭释放；GeoIP 提取 MUST 直接按目标国家码流式过滤，
MUST NOT 全量构建全球国家映射导致额外内存峰值。B1 核查确认 wanBox 已满足时，
本条以回归测试关闭，不改实现。
B1 核查结论（2026-11-17）：两处缺陷真实存在，已修——`geoip.go` 全量建表且无
`networks.Err()` 检查，`geosite.go` 的 `loadGeoSiteRules` 从不关闭读取器。

- **GIVEN** 加载 GeoSite/GeoIP 规则集
- **WHEN** 提取完成
- **THEN** 底层读取器已关闭，且未出现全量国家映射构建的内存峰值

### Requirement: 内嵌资源解压失败不得静默吞掉（B1 核查项）

内嵌资源解压（`Unxz` / assets）MUST 正确关闭文件句柄、正确拼接版本文件清理路径，
解压失败 MUST 返回错误，不得静默吞掉。B1 核查确认 wanBox 已满足时，
本条以回归测试关闭，不改实现。
B1 核查结论（2026-11-17）：三处缺陷真实存在，已修——`Unxz` 不关闭输出文件且
丢弃关闭错误；`assets_android.go` 日志写死 `geoipDat`、`os.RemoveAll(version)`
缺 `dir +` 前缀、`.xz` 解压错误被忽略；`RawUpdater` 的共享 OkHttpClient
`close()` 是有文档的 no-op，不适用上游的关闭修复，无改动。

- **GIVEN** 内嵌资源解压失败
- **WHEN** 解压流程返回
- **THEN** 调用方收到明确错误，而不是静默成功

### Requirement: 切网时重置路由器 DNS/HTTP 连接池（B1 已落地）

（补记：本条在 B1 实施期间已写入主规范 `openspec/specs/libcore-integration/spec.md`，delta 漏记，2026-11-18 发布后回填；正文与主规范一致。）

`networkChangeResetConnections=false` 时，切网 MUST 保留现有代理长连接
（不通知 NetworkManager 拆出站），但 MUST 仍重置路由器的 DNS/HTTP 连接池，
避免 DoH 等持久化 DNS 连接继续挂在旧物理网卡上卡死；首次出现接口
（曾 Lost/pause 后恢复）仍 MUST 通知回调以 NetworkWake。

- **GIVEN** 设置关闭了“切网重置连接”，当前接口为 wlan0
- **WHEN** 默认接口切到 rmnet0
- **THEN** 不通知出站回调拆连接，但路由器的 DNS/HTTP 连接池被重置一次
- **GIVEN** 同一接口重复上报
- **WHEN** 再次收到相同接口事件
- **THEN** 不重复重置、不重复通知
