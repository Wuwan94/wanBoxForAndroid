## Why

`rootmodule/auto_stop.go` 的 `gatewayCache.read` 在缓存命中与刷新完成两条路径上用 `maps.Clone` 返回网关表。`maps.Clone` 只复制外层 map：每个接口的 `[]net.IP` 切片与 `net.IP` 字节仍与缓存共享。上一轮 P2-3 修复确立的契约是“调用方拿到独立副本、缓存不被外部修改影响”，该契约目前没有被完整保证。当前生产调用路径（物理网络观察 → 指纹与自动启停匹配）只读返回值，尚未确认运行时故障；这是规格/测试缺口与潜在风险，不是已确认 bug。本次按最小改动补齐深拷贝并先写红测锁定契约。

## What Changes

- `gatewayCache.read` 的两个返回出口统一改为深拷贝：复制 map、每个 `[]net.IP` 切片以及每个 `net.IP` 的字节。
- 新增回归测试：对返回值分别执行增删 map 键、替换切片成员、改写 `net.IP` 字节三种变异，再断言缓存内容不变。
- 不改变缓存键、TTL（`gatewayCacheTTL`）、单飞刷新与锁外发现行为；不改变任何调用方。
- 无破坏性 API 或数据迁移；不改变 `SINGBOX_VERSION` 与版本元数据（升版走发布流程的独立批次）。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `libcore-integration`: 网关缓存返回给调用方的网关表 MUST 为独立深拷贝，调用方对返回值的任意修改 MUST NOT 影响缓存内容。

## Impact

- Root 模块：`rootmodule/auto_stop.go`（`gatewayCache.read` 返回路径）、`rootmodule/auto_stop_test.go` 或同包新测试文件。
- 能力规范：`openspec/specs/libcore-integration/spec.md`（归档时合并本变更的 delta）。
- 不涉及 Android UI、配置生成、模块打包与外部接口。
