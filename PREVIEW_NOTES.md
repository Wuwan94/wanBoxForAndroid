# v3.0.16-preview.1 发行说明（候选）

**状态：未发布的稳定性预览版候选。**

本版是 v3.0.15-preview.4 的复审修复版：针对智能启停的耗电、网络指纹耦合与三处健壮性问题做定向修复，不新增功能。正式稳定版 `v3.0.14`（`VERSION_CODE=2026`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 不变；预览元数据码 `PRE_VERSION_CODE=2029`，派生 APK `versionCode=10146`，高于 preview.4 的 `10144`。

## ✦ 与上一版对比

| 项目 | v3.0.15-preview.4 | v3.0.16-preview.1 |
|---|---|---|
| 内容 | 六项移植（路由、测速继承、备份兼容、QUIC 开关、无效出站告警、智能启停）+ 清理确认流 | preview.4 复审修复：智能启停省电、指纹解耦、网关读取健壮性、规则解析容错、确认锁超时释放、恢复令牌修正 |
| APK `versionCode` | 10144 | 10146 |
| Root 模块码 | 2028 | 2029 |

preview.4 的功能基座本版未改：智能启停、QUIC 开关、无效出站告警、路由与默认规则、分组测速继承、备份兼容；以及 preview.1 起的 5 秒 Canary 热更新观察与自动回滚、切换间隙 `pref 9001` 黑洞守护、`127.0.0.1` 只读长轮询状态通道、独立子 cgroup 的 `memory.high`（`MemTotal/4` 钳制于 256 MiB–4 GiB）。

## ✦ 本版更新

### 1. 智能启停省电：禁用时不再每 5 秒 fork `ip`
自动启停未启用时，监控循环不再每 5 秒启动 `ip` 进程做物理网络观察；默认网关只在规则实际包含 `gateway:` 条件时读取，并按接口集合指纹做 60 秒节流缓存（`gatewayCacheTTL`），网络不变时不重复读取。

### 2. 网络指纹解耦
全局 `network_changed` 判断与重建预算改用只含物理接口与全局单播地址的指纹；含网关与 IPv6 链路本地地址的完整指纹只用于自动启停规则匹配与恢复抑制。网关或链路本地地址变化不再误触发核心重建。

### 3. 网关读取健壮性
读取默认网关优先使用 `/system/bin/ip` 绝对路径，回退到 PATH 查找，与既有 `/system/bin/am` 的调用方式一致，避免精简环境下 PATH 缺失导致网关规则静默失效。

### 4. 管理器规则解析容错
智能启停规则解析失败不再阻断整份状态快照下发：改为记录警告并降级为空规则列表，单条规则写坏不会让管理界面整体失去响应。

### 5. 确认弹窗锁超时释放
等待用户确认的操作增加 2 分钟有界超时：确认界面被划掉或长时间未处理时，锁只在 CAS 赢得与确切待确认状态的比较后释放，磁贴与通知操作不再被永久拒绝；单次 CAS 确认令牌纪律保持不变。

### 6. 手动恢复令牌修正
手动恢复的抑制令牌恢复为按含网关的真实当前网络指纹计算，不再受自动启停启用状态影响，也不再因策略文件损坏而平白失败；恢复后在同一网络重新启用自动启停时，恢复意图不会被立即吞掉。

## 📦 版本、资产与安装

- **运行要求**：Android 12+（API 31+）、arm64-v8a、Magisk / KernelSU / APatch 等 Root 环境。
- **版本对齐**：APK `versionName=3.0.16-preview.1` / `versionCode=10146`；Root 模块码 `2029`。瘦管理器和 Root 模块必须来自同一预览 Release、同版成套使用。
- **同版资产**：`wanBoxForAndroid-3.0.16-preview.1-arm64-v8a-release.apk`、`wanbox-root-module-arm64-v8a.zip`、`wanbox-root-module-with-manager-arm64-v8a.zip`、`SHA256SUMS`；字节数与哈希在发布后回填[版本对照](docs/current-version.zh-CN.md)。
- **回退**：使用正式稳定版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 与配套 Root 模块（模块码 `2026`）；不要混用不同 Release 的 APK 和模块。

## 🛡️ 验证边界与真机待测

候选阶段证据：RootModule Go 全套测试本地通过，Python 工具 108 项与版本文档一致性检查通过；Gradle 单测与 APK 构建以 GitHub `Verify Android Build` 为门禁，发布时以 `Preview Build` 对精确提交构建并校验资产。本地 Gradle 在当前维护机上因虚拟机内存受限无法运行（daemon 通信中断，三度复现），不计为通过。

**未做的真机验证**：Magisk / KernelSU / APatch 设备矩阵，以及智能启停禁用/启用下的实际耗电、网关规则命中、确认超时后的磁贴/通知恢复、手动恢复后同网络重新启用的抑制行为。自动化结果不等同于真实 Root 设备的网络恢复验证。

---

## 历史归档

v3.0.14-preview.1 与 v3.0.15-preview.1–.3 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。
