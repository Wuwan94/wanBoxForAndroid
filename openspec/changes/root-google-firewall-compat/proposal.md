# 提案：Root 模块 OEM Google 联网兼容（PR-C）

## Why

wanBox Root 模块主线目前没有针对部分厂商 Google 防火墙策略的兼容路径。参考已固定上游提交后，缺口可精确界定为专用链中的单 UID 拦截项，以及 ColorOS 16 特定 BPF map 中的 Google UID 拒绝项；不能用清空共享链或整个 map 的方式绕过。

## What Changes

- 在 Root 模块 `sys.boot_completed=1` 后延迟 1 分钟调度独立、固定的 Root-only 子进程；模块热激活成功后，在安装锁释放并以新活动目录切换完成后立即调度。
- 仅对明确识别的 RedMagic、努比亚、中兴专用链规则，或满足全部设备/系统/策略/BPF 结构门禁的 OnePlus/OPPO ColorOS 16 map 条目做定点删除。
- 从 Android 包管理器解析 user 0 的包 UID；非白名单共享 UID 整体跳过；模块 disable/remove 标记在运行前复核。
- 未知品牌、ROM、策略值、规则形态或 BPF 布局 fail-closed；不可选修复失败不阻断正常启动或已提交的模块更新。
- 同步 Root 模块中文文档和 `root-module-runtime` OpenSpec。

## Non-goals

- 本 PR 只处理 Root 模块联网兼容，不包含 PR-D 的 Root WUI、设置开关、状态行或诊断界面。
- 不改变核心自启偏好、App VPN/节点/订阅行为、应用配置 schema 或构建依赖。
- 不生成、持久化或发送 HWID/任何设备标识；不修改 `nb4a.properties`、Release/Tag 或 `Wuwan94.github.io`。

## Capabilities

### New Capabilities

- `root-module-runtime`：定义 Root 模块厂商 Google 联网修复的支持范围、精确规则边界、生命周期调度、安全门禁及待设备验证项。

### Modified Capabilities

- 无其他能力变更；PR-D 的 UI/诊断留在独立变更中。

## Impact

仅影响 `rootmodule/` Go 代码/测试、Root 模块中文 README 和 OpenSpec。无新增 Go 依赖、配置迁移或持久化状态。`Verify Android Build` 负责 Root Go race/vet/Python 测试、Xray v26.11.9 互通矩阵及 Android ARM64 构建；CI 通过仍不代表 Root OEM 真机通过。

## 回退

可通过 revert 本 PR 并重新安装此前模块版本停用后续兼容逻辑。代码回滚不会自动重建本功能已删除的精确厂商拦截项；如需恢复 OEM 限制，应通过厂商设置重新应用或重启后核对。不得用 flush 共享防火墙链或 BPF map 的方式回退。
