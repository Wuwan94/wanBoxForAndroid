# v3.0.15-preview.4 发行说明

**状态：已发布的稳定性预览版。**

本版把 OwnBox `v26.11.8.4` 的六项改动移植进 wanBox 的 Root-only 架构。正式稳定版 `v3.0.14`（`VERSION_CODE=2026`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 不变；预览元数据码 `PRE_VERSION_CODE=2028`，派生 APK `versionCode=10144`。

## ✦ 与上一版对比

| 项目 | v3.0.15-preview.3 | v3.0.15-preview.4 |
|---|---|---|
| 内容 | 长轮询通道、cgroup 软限制与热更新/卸载治理收尾 | 六项移植（路由、测速继承、备份兼容、QUIC 开关、无效出站告警、智能启停）+ 清理确认流 |
| APK `versionCode` | 10143 | 10144 |
| Root 模块码 | 2028 | 2028 |
| APK | 6,200,158 字节 / 5.91 MiB | 6,231,390 字节 / 5.94 MiB |
| 普通模块 ZIP | 17,932,526 字节 / 17.10 MiB | 17,952,780 字节 / 17.12 MiB |
| 合一 ZIP | 23,441,379 字节 / 22.36 MiB | 23,490,404 字节 / 22.40 MiB |

preview.1 起的既有基座本版未改：5 秒 Canary 热更新观察与自动回滚、切换间隙 `pref 9001` 黑洞守护、`127.0.0.1` 只读长轮询状态通道、独立子 cgroup 的 `memory.high`（`MemTotal/4` 钳制于 256 MiB–4 GiB）。

## ✦ 本版更新

### 1. 智能启停代理（仅 Root）
高级设置可配置最多 5 条网络指纹规则，按本地 IP、CIDR 网段或 `gateway:` 网关匹配，不申请定位权限。命中时安全挂起核心并确认核心拥有的 TUN/路由清理，通知保留「恢复」「停止」操作；离开匹配网络后自动恢复。手动恢复会抑制同一网络指纹的重复挂起，手动停止不会被自动重启；Apply/清理失败不会误报为已挂起，也不全局清空其他模块的 iptables/nftables 规则。

### 2. 独立 QUIC 拦截开关
核心设置新增默认开启的「拦截 QUIC（UDP 443）」开关。关闭后放行 HTTP/3/QUIC；该规则只针对 UDP 443，不把 TCP 443 或其他 UDP 端口一并拦截。

### 3. 无效出站规则提醒
路由规则引用的节点或策略组不存在时，规则列表会标红；启动前汇总提醒，避免继续静默忽略。用户可以返回修复，或明确选择继续。

### 4. 路由与默认规则修正
域名规则前置 sniff；非智能分流模式下，自定义 reject 优先于内置国内直连 fallback。移除组播规则误加的 `source_ip_cidr` 和多余 DNS 53 代理兜底；默认广告规则统一为 `geosite-category-ads-all`。同时移除强制把 `com.android.providers.downloads` 注入 Play 代理规则的逻辑，避免国内应用下载被强制送入代理。

### 5. 分组测速地址动态继承
分组未单独设置测速地址时，持续跟随全局「连接测试网址」；保留用户显式设置，并将精确匹配旧硬编码 fallback 的值迁移为空，避免全局地址变化后分组仍使用旧地址。

### 6. 备份格式兼容
导出统一使用 `profiles`，导入仍兼容旧版 `proxies`；路由规则导出为可读 JSON，并继续导入旧版 Base64 Parcel。补全核心路由/DNS 默认设置，同时保留旧 wanBox 备份导入能力。

## 📦 版本、资产与安装

- **运行要求**：Android 12+（API 31+）、arm64-v8a、Magisk / KernelSU / APatch 等 Root 环境。
- **版本对齐**：APK `versionName=3.0.15-preview.4` / `versionCode=10144`；Root 模块码 `2028`。瘦管理器和 Root 模块必须来自同一预览 Release、同版成套使用。
- **同版资产**：`wanBoxForAndroid-3.0.15-preview.4-arm64-v8a-release.apk`（6,231,390 字节）、`wanbox-root-module-arm64-v8a.zip`（17,952,780 字节）、`wanbox-root-module-with-manager-arm64-v8a.zip`（23,490,404 字节）、`SHA256SUMS`；APK 与普通模块合计 23.06 MiB，低于 25 MiB 门禁。
- **回退**：使用正式稳定版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 与配套 Root 模块（模块码 `2026`）；不要混用不同 Release 的 APK 和模块。

## 🛡️ 验证边界与真机待测

发布证据：[GitHub Release v3.0.15-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.15-preview.4)，`Preview Build` run `43628453020` 在提交/tag `d6914e70d730c63ee3c3cbb2b00fe2e754b6737e` 成功，四个 job 全部通过；`verify-android` run `43625363368` 在集成提交 `69de6eac6a23d0af65525991c6fff3cbf1a6e620` 通过 instrumentation、迁移、manifest 与体积门禁；下载资产的 SHA256SUMS 校验通过，精确字节数与哈希见[版本对照](docs/current-version.zh-CN.md)。

本地完成了 RootModule Go race/vet、arm64 模块构建、libcore Go 测试与 Android JVM 测试；本机无 KVM，本地 API 35 instrumentation 未执行，不计为通过。

**未做的真机验证**：Magisk / KernelSU / APatch 设备矩阵，以及智能挂起/恢复（含 `gateway:` 与 CIDR 命中、通知「恢复」「停止」按钮）、QUIC 关闭后的 HTTP/3 实际回退、删除被引用节点后的红标与启动确认。自动化结果不等同于真实 Root 设备的网络恢复验证。

---

## 历史归档

v3.0.14-preview.1 与 v3.0.15-preview.1–.3 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。
