# v3.0.15-preview.2 发行说明（候选）

**状态：未发布的稳定性预览版候选。** 本版为源码候选，尚未发布；本版不替换稳定版 `v3.0.14`，与已发布预览版 `v3.0.15-preview.1` 同线演进（模块码仍为 `2027`）。

本版按优化工作词一次性做全四项底层优化：长连接 IPC、9001 净空、健康感知与内存自适应。均为底层稳定性与性能优化，无界面功能增减。

---

## ✦ 核心演进

### 1. 回环长轮询通道：管理器与模块的本地长连接（新）
supervisor 内嵌仅监听 `127.0.0.1` 的只读 HTTP 通道，启动时生成随机令牌并写入 `channel.json`（权限 0600），管理器经 `su` 桥新命令 `wanboxctl channel` 取得端点。状态与仪表盘读取改走通道（含按 `state.revision` 唤醒的长轮询），前台观察不再每次衍生 `su` 进程，单次往返由 100–300ms 降至毫秒级；通道与 CLI 共用同一渲染路径、输出逐字节一致，响应继续走管理器全量校验。通道故障自动回退 `su` 桥并冷却 30 秒；所有变更操作仍只走 `su` 桥，通道不承载任何写操作。

### 2. pref 9001 黑洞规则循环净空
热更新守护关闭路径（`defaultRouteGuard`）与卸载脚本均改为循环删除 `pref 9001`，直至内核报告不存在为止（上限 128 次，触顶按 Fail-Closed 报错）。杜绝多次切换堆叠的同 pref 规则只删一条、残留孤儿黑洞规则导致断网。

### 3. 独立子 cgroup 与内存软限制
supervisor 启动时（核心与插件拉起之前）建立专属 `wanbox` 子 cgroup 并迁入自身，其后拉起的核心与插件天然生于组内。内存治理只作用于该子组：`memory.high` 按整机内存四分之一钳制于 [256 MiB, 4 GiB] 写入子组；用量达阈值九成或 `memory.events` 计数增长时判定为压力，主动触发 GC 并以 30 秒节流。高吞吐时内核回收先行介入，降低被系统 LMK 闪退的概率；治理不可用时静默降级，绝不写根组或他人组、绝不阻塞模块启动。

### 4. 节点故障迁移阈值明示与回归覆盖
`HealthModeAuto` 的连击阈值提为命名常量（默认 3、范围 2–10），并补齐边界、连击清零与失败锁存的回归测试。迁移行为不变：仅经 127.0.0.1 回环 Clash API 切换同组 Selector，不重建 TUN、不断长连接，切换失败锁存 degraded 不乱切。

### 5. 管理器读聚合
状态缓存 TTL 由 2 秒调整为 4 秒（与前台观察节拍对齐），status/dashboard 兄弟视图同锁聚合预取，进一步减少重复取数；开关、切换等决策路径仍绕缓存实时查询。

---

## 📦 资产清单与运行环境

- **运行要求**：Android 12+ (API 31+) · arm64-v8a · Magisk / KernelSU / APatch 已 Root 设备。
- **管理器形态**：纯瘦管理器（APK 约 6.19 MiB），核心仅随 Root 模块分发，不提供普通 VPN 回退。
- **版本对齐**：模块码 `2027`，APK `versionCode=10137`（高于 preview.1 的 10136 与正式版 3.0.14 的 10130，可直接覆盖安装）。

本 Release 提供 4 个资产，必须**成套配对使用**：

```
wanBoxForAndroid-3.0.15-preview.2-arm64-v8a-release.apk  瘦身管理器
wanbox-root-module-arm64-v8a.zip                         Root 核心模块（内核 + wanboxctl）
wanbox-root-module-with-manager-arm64-v8a.zip             模块 + 管理器合一刷机包
SHA256SUMS                                               上述资产校验和
```

---

## 🛡️ 升级、回退与验证边界

1. **升级与热更新**：直接覆盖安装新版 APK，并在 Root 管理器或关于页刷入模块 ZIP；生效后核心自动热重启，无需重启手机系统。
2. **回退方案**：卸载预览版 APK，安装稳定版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 与配套模块（码 `2026`）。
3. **验证边界**：本版已全量通过 Go 单测、Python 工具套件（108 项）与版本快照校验，并经两轮代码审查（硬违规 0）。未做全量真机矩阵，日常主力使用建议留在稳定版 v3.0.14。

---

## 历史归档：v3.0.15-preview.1 已发布说明

**状态：已发布的稳定性预览版。** 本版由仓库 `Preview Build` 正式发布；[Release 页面](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.15-preview.1) tag 指向合并提交 `5aca93bee39a9d07a56d750908e0d9c88ad5f90c`。本版不替换稳定版 `v3.0.14`。

本版重点攻克 Root 模块在 Magisk / KernelSU / APatch 环境下的**免重启热更新加固**与**卸载纯净治理**。均为底层稳定性、数据安全与网络连续性修复，无界面功能增减。

---

## ✦ 核心演进

### 1. 热更新 5 秒 Canary 观察与自动回滚
新核心启动后进入 5 秒健康观察期（以 500ms 为周期双重核验：底层进程存活 + Clash 控制器 `/version` 回环探活）。若新核心在观察期内秒退或控制器未响应，系统立即触发 Fail-Safe 回滚恢复旧版模块并记录审计事件 `module_update_canary_failed_rolled_back`；只有健康核验全绿通过，才安全销毁旧备份并提交热更新。

### 2. 切换间隙瞬态黑洞守护（Fail-Closed）
为防止旧核心退出至新核心接管 TUN 的毫秒级窗口发生直连外溢，旧核心停止前先注入 `pref 9001 blackhole` 策略路由（TUN 自动路由为 `pref 9000`）。切换间隙流量严格落入黑洞而非物理网卡，杜绝真实 IP 泄漏；新核心就绪或回滚完成后自动解除。若热更新与回滚均失败，黑洞保持开启以维持 Fail-Closed 边界。

### 3. Magisk / KernelSU 更新标记自动收口
热更新提交后，主动清理模块目录下的 `update` 标志文件，Root 管理器不再误报“重启后生效”；同时通过事件通道广播 `module_hot_update_committed:<versionCode>`，已在前台运行的管理器 App 无需杀进程重启即可在设置页实时刷新模块版本码。

### 4. 卸载脚本五轨纯净收尾（防断网黑洞）
重构 `uninstall.sh` 为五轨防御机制：
- **轨 1**：优先调用 `wanboxctl stop` 优雅停机；
- **轨 2**：二进制损坏或死锁时，原生 Shell 梯级信号强退（`SIGTERM` → 2s 超时 → `SIGKILL`），杜绝孤儿进程卡死；
- **轨 3**：强制注销 `wanbox-tun` 虚拟网卡，清理 `pref 9000` 与 `pref 9001` 专有策略路由，网络彻底恢复物理直连；
- **轨 4**：清理 `/data/adb/wanbox/run/*` 锁文件与临时 IPC 资产；
- **轨 5**：用户数据安全隔离。任一步骤失败均不中断后续清理，脚本恒定以 `exit 0` 退出。

### 5. 用户数据默认保护
卸载时默认保留 `/data/adb/wanbox/config/` 下的用户节点与订阅配置，避免误点卸载丢失数据；仅在检测到显式全清标记 `/data/adb/wanbox/.purge_user_data` 时才彻底清除目录。

---

## 📦 资产清单与运行环境

- **运行要求**：Android 12+ (API 31+) · arm64-v8a · Magisk / KernelSU / APatch 已 Root 设备。
- **管理器形态**：纯瘦管理器（APK 约 6.19 MiB），核心仅随 Root 模块分发，不提供普通 VPN 回退。
- **版本对齐**：模块码 `2027`，APK `versionCode=10136`（高于正式版 3.0.14 的 10130，可直接覆盖安装）。

本 Release 提供 4 个资产，必须**成套配对使用**：

```
wanBoxForAndroid-3.0.15-preview.1-arm64-v8a-release.apk  瘦身管理器
wanbox-root-module-arm64-v8a.zip                         Root 核心模块（内核 + wanboxctl）
wanbox-root-module-with-manager-arm64-v8a.zip             模块 + 管理器合一刷机包
SHA256SUMS                                               上述资产校验和
```

---

## 🛡️ 升级、回退与验证边界

1. **升级与热更新**：直接覆盖安装新版 APK，并在 Root 管理器或关于页刷入模块 ZIP；生效后核心自动热重启，无需重启手机系统。
2. **回退方案**：卸载预览版 APK，安装稳定版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 与配套模块（码 `2026`）。
3. **验证边界**：本版已全量通过 Go 单测、Python 工具套件（108 项）与版本快照校验。未做全量真机矩阵，日常主力使用建议留在稳定版 v3.0.14。

---

## 历史归档：v3.0.14-preview.1 已发布说明

> 以下为 preview.1 当时发布说明原文；该预览的六项复查修复已由 v3.0.14 正式版全量承接，正式说明见 [RELEASE_NOTES.md](RELEASE_NOTES.md)。稳定版现为 v3.0.14。

### v3.0.14-preview.1 更新摘要
- **版本规则漂移守卫**：`tools/version_code.py --check-app-tag` 纳入三端同源漂移校验。
- **更新包版本戳绑定**：`UpdateDownload` 引入版本戳强绑定，彻底杜绝错版模块导出。
- **导出任务独立守卫**：关于页导出脱离下载单飞锁，避免静默吞并。
- **状态码判定发布 Tag**：`check_release_tag.sh` 改用 HTTP 状态行判定，消除报错文本正则误判。
- **代理错误脱敏与源头判定**：仅认 `UpdateProxyUnavailable` 专属标记，不暴露凭据明文。
