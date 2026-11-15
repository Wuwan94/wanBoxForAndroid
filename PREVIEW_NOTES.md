# v3.0.16-preview.2 发行说明

**状态：已发布的稳定性预览版。**

本版是 v3.0.16-preview.1 的复审修复版：修复手动恢复抑制令牌与自动启停监控的网络指纹口径不一致，并加固网关缓存并发、确认定时器与弹窗超时反馈，不新增功能。正式稳定版 `v3.0.14`（`VERSION_CODE=2026`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 不变；预览元数据码 `PRE_VERSION_CODE=2029` 不变，派生 APK `versionCode=10147`，高于 preview.1 的 `10146`。

## ✦ 与上一版对比

| 项目 | v3.0.16-preview.1 | v3.0.16-preview.2 |
|---|---|---|
| 内容 | preview.4 复审修复：智能启停省电、指纹解耦、网关读取健壮性、规则解析容错、确认锁超时释放、恢复令牌修正 | preview.1 复审修复：恢复/监控指纹口径统一、网关缓存锁外执行、确认定时器序号、弹窗超时反馈 |
| APK `versionCode` | 10146 | 10147 |
| Root 模块码 | 2029 | 2029 |

preview.1 的修复基座本版未改：智能启停省电与指纹解耦、网关读取健壮性、管理器规则解析容错、确认锁超时释放、恢复令牌按真实网络计算；以及更早确立的智能启停、QUIC 开关、无效出站告警、路由与默认规则、分组测速继承、备份兼容，5 秒 Canary 热更新观察与自动回滚、切换间隙 `pref 9001` 黑洞守护、`127.0.0.1` 只读长轮询状态通道、独立子 cgroup 的 `memory.high`（`MemTotal/4` 钳制于 256 MiB–4 GiB）。

## ✦ 本版更新

### 1. 手动恢复指纹口径统一
手动恢复的抑制令牌与自动启停监控改用同一份含网关的物理网络观察。此前监控侧在规则只有 IP/CIDR 时不读网关、恢复侧却始终读，同一网络两侧指纹不一致：手动恢复后，抑制会在首个监控周期被误判为「网络已变化」而清空，命中规则时立即重新挂起。本版观测只按自动启停启用状态门控（禁用时仍不做任何观察、零 `ip` fork），60 秒网关缓存节流保留，省电收益不变。新增回归测试钉住该场景：精确 IP 与 CIDR 两种规则下，恢复令牌经监控路径后不被清空、不触发挂起。

### 2. 网关缓存并发健壮性
网关发现（最长约 1 秒的外部读取）移到缓存锁外执行，并发刷新合并为一次，命令线程不再被缓存锁串行阻塞；缓存读取返回副本，调用方修改返回值不再污染缓存内容。

### 3. 确认定时器精确匹配
确认等待的超时调度引入单调递增序号：发布续期等待与检查到期序号在同一把锁内完成，旧定时器不能再提前清掉一轮结构相同的新等待；单次 CAS 确认令牌纪律保持不变。

### 4. 弹窗超时反馈
出站引用告警弹窗点击「继续」时，若该确认已超时失效，改为给出明确提示，不再静默无响应。

## 📦 版本、资产与安装

- **运行要求**：Android 12+（API 31+）、arm64-v8a、Magisk / KernelSU / APatch 等 Root 环境。
- **版本对齐**：APK `versionName=3.0.16-preview.2` / `versionCode=10147`；Root 模块码 `2029`。瘦管理器和 Root 模块必须来自同一预览 Release、同版成套使用。
- **同版资产**：`wanBoxForAndroid-3.0.16-preview.2-arm64-v8a-release.apk`、`wanbox-root-module-arm64-v8a.zip`、`wanbox-root-module-with-manager-arm64-v8a.zip`、`SHA256SUMS`。
- **回退**：使用正式稳定版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 与配套 Root 模块（模块码 `2026`）；不要混用不同 Release 的 APK 和模块。

## 🛡️ 验证边界

发布证据：[GitHub Release v3.0.16-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.16-preview.2)，`Preview Build` run `43684289336` 在提交/tag `c5701a0979ef2a8ee4d28020f513f7f4aac07358` 发布成功。`verify-android` run `43679580768`（修复 PR #47）与 run `43682068186`（升版 PR #48）成功，instrumentation、迁移、manifest 与体积门禁通过。RootModule Go 全套测试（含新增指纹口径回归测试）与 Python 工具 108 项在本地通过，代码经独立复审逐项核对；本地 Gradle 因维护机虚拟机环境限制无法运行（多次复现），不计为通过。下载资产的 SHA256SUMS 校验通过，精确字节数和哈希见[版本对照](docs/current-version.zh-CN.md)。未执行 Magisk / KernelSU / APatch 真机矩阵，手动恢复后同网络下 IP/CIDR 规则的抑制保持、网关命中、确认超时恢复与实际耗电仍未验证；自动化结果不等同于真实 Root 设备的网络恢复验证。

---

## 历史归档

v3.0.14-preview.1、v3.0.15-preview.1–.3、v3.0.15-preview.4 与 v3.0.16-preview.1 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。
