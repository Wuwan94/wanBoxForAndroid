# v3.0.15-preview.3 发行说明

**状态：已发布的稳定性预览版。** [Release 页面](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.15-preview.3) 的 tag 指向合并提交 `fe193f1134a80ff38fb0cb3ed4677eb2b79c1dce`，由 [Preview Build run `43404430362`](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/43404430362) 发布；本版不替换稳定版 `v3.0.14`，Root 模块码为 `2028`。

本版承接 preview.2 的底层优化，修正两项复审发现：低内存设备的 `memory.high` 必须钳制到 256 MiB，而不是关闭治理；卸载脚本必须与热更新路径使用相同的 pref 9001 清理契约，规则状态未确认时不得报告网络已恢复。无界面功能增减。

---

## ✦ 核心演进

### 1. 回环长轮询通道：管理器与模块的本地长连接（新）
supervisor 内嵌仅监听 `127.0.0.1` 的只读 HTTP 通道，启动时生成随机令牌并写入 `channel.json`（权限 0600），管理器经 `su` 桥新命令 `wanboxctl channel` 取得端点。状态与仪表盘读取改走通道（含按 `state.revision` 唤醒的长轮询），前台观察不再每次衍生 `su` 进程，单次往返由 100–300ms 降至毫秒级；通道与 CLI 共用同一渲染路径、输出逐字节一致，响应继续走管理器全量校验。通道故障自动回退 `su` 桥并冷却 30 秒；所有变更操作仍只走 `su` 桥，通道不承载任何写操作。

### 2. pref 9001 黑洞规则循环净空
热更新守护关闭路径（`defaultRouteGuard`）与卸载脚本使用同一契约：循环删除 `pref 9001`，只有内核明确报告规则不存在才算清空；其他错误或 128 次上限触顶均按 Fail-Closed 处理。卸载仍完成其余清理并以 `exit 0` 结束，但会记录网络恢复状态未知，不再误报已恢复。杜绝残留孤儿黑洞规则导致断网。

### 3. 独立子 cgroup 与内存软限制
supervisor 启动时（核心与插件拉起之前）建立专属 `wanbox` 子 cgroup 并迁入自身，其后拉起的核心与插件天然生于组内。内存治理只作用于该子组：非零 `MemTotal/4` 结果钳制于 [256 MiB, 4 GiB] 并写入子组；用量达阈值九成或 `memory.events` 计数增长时判定为压力，主动触发 GC 并以 30 秒节流。高吞吐时内核回收先行介入，降低被系统 LMK 闪退的概率；治理不可用时静默降级，绝不写根组或他人组、绝不阻塞模块启动。

### 4. 节点故障迁移阈值明示与回归覆盖
`HealthModeAuto` 的连击阈值提为命名常量（默认 3、范围 2–10），并补齐边界、连击清零与失败锁存的回归测试。迁移行为不变：仅经 127.0.0.1 回环 Clash API 切换同组 Selector，不重建 TUN、不断长连接，切换失败锁存 degraded 不乱切。

### 5. 管理器读聚合
状态缓存 TTL 由 2 秒调整为 4 秒（与前台观察节拍对齐），status/dashboard 兄弟视图同锁聚合预取，进一步减少重复取数；开关、切换等决策路径仍绕缓存实时查询。

---

## 📦 资产清单与运行环境

- **运行要求**：Android 12+ (API 31+) · arm64-v8a · Magisk / KernelSU / APatch 已 Root 设备。
- **管理器形态**：纯瘦管理器（APK 约 5.91 MiB），核心仅随 Root 模块分发，不提供普通 VPN 回退。
- **版本对齐**：模块码 `2028`，APK `versionCode=10143`（高于 preview.2 的 10137 与正式版 3.0.14 的 10130，可直接覆盖安装）。

本 Release 提供 4 个资产，必须**成套配对使用**：

```
wanBoxForAndroid-3.0.15-preview.3-arm64-v8a-release.apk  瘦身管理器
wanbox-root-module-arm64-v8a.zip                         Root 核心模块（内核 + wanboxctl）
wanbox-root-module-with-manager-arm64-v8a.zip             模块 + 管理器合一刷机包
SHA256SUMS                                               上述资产校验和
```

---

## 🛡️ 升级、回退与验证边界

1. **升级与热更新**：直接覆盖安装新版 APK，并在 Root 管理器或关于页刷入模块 ZIP；生效后核心自动热重启，无需重启手机系统。
2. **回退方案**：卸载预览版 APK，安装稳定版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 与配套模块（码 `2026`）。
3. **验证边界**：本地 Go、POSIX Shell 与 Python 工具测试通过；官方 [Preview Build run `43404430362`](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/43404430362) 通过 Root 模块与 APK 构建、模拟器冷启动、候选资产校验、发布后回读及 SHA256SUMS 校验。未做全量 Root 真机矩阵，日常主力使用建议留在稳定版 v3.0.14。

---

## 历史归档：v3.0.15-preview.2 已发布说明（Release/tag 已清理）

**状态：曾发布的稳定性预览版；Release/tag 已在 preview.3 发布并验证后按单预览保留策略清理。** 当时 tag 指向合并提交 `2a57973623db59cb635a869b5d552dd971f8235c`，由 `Preview Build` run `43393988473` 发布；稳定版 `v3.0.14` 未变。该版包含回环长轮询 IPC、热更新守护与卸载清理、故障迁移阈值、独立子 cgroup 和内存软限制等底层优化。

该版 Root 模块码为 `2027`、APK `versionCode=10137`，发布瘦身 APK、普通 Root 模块、带管理器模块及 `SHA256SUMS` 四件套。低于 256 MiB 的计算值与卸载侧清理触顶状态处理问题已由 preview.3 修正；preview.2 的历史资产摘要见 [版本对照](docs/current-version.zh-CN.md)。

---

## 历史归档：v3.0.15-preview.1 已发布说明（Release/tag 已清理）

**状态：曾发布的稳定性预览版；Release/tag 已在 preview.2 发布后按单预览保留策略清理。** 当时仓库 `Preview Build` tag 指向合并提交 `5aca93bee39a9d07a56d750908e0d9c88ad5f90c`。本版不替换稳定版 `v3.0.14`。

本版重点攻克 Root 模块在 Magisk / KernelSU / APatch 环境下的**免重启热更新加固**与**卸载纯净治理**：
- **热更新 5 秒 Canary 观察与自动回滚**：新核心启动后经 5 秒健康期双重核验（底层进程存活 + Clash 控制器 `/version` 探活）；秒退或未响应立即触发 Fail-Safe 回滚。
- **切换间隙瞬态黑洞守护（Fail-Closed）**：旧核心停止前注入 `pref 9001 blackhole`，杜绝切换毫秒隙真实 IP 外溢。
- **Magisk / KernelSU 更新标记自动收口**：清除模块目录 `update` 标志，事件通道广播 `module_hot_update_committed:<versionCode>`，前台 App 实时刷新模块版本码。
- **卸载脚本五轨纯净收尾**：优雅停机 → 原生信号强退 → 注销 TUN 并强清 pref 9000/9001 路由 → 清理运行时锁与 IPC → 默认保护用户配置资产，始终 `exit 0`。
- **版本对齐**：Root 模块码 `2027`、APK `versionCode=10136`；历史资产摘要见 [版本对照](docs/current-version.zh-CN.md)。

---

## 历史归档：v3.0.14-preview.1 已发布说明（已由正式版承接）

> v3.0.14-preview.1 当时发布说明原文及六项复查修复（版本规则漂移守卫、更新包版本戳绑定、导出任务独立守卫、状态码判定发布 Tag、代理错误脱敏）已由 v3.0.14 正式版全量承接，正式说明见 [RELEASE_NOTES.md](RELEASE_NOTES.md)；历史发布脉络见 [CHANGELOG.md](CHANGELOG.md)。稳定版现为 v3.0.14。
