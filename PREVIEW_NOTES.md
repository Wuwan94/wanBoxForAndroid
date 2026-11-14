# v3.0.15-preview.4 发行说明

**状态：已发布的稳定性预览版。**

本版在 wanBox 的 Root-only 架构中完成六项功能与修复适配。正式稳定版 `v3.0.14`（`VERSION_CODE=2026`）不变；`SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 不变。预览元数据码保持 `2028`，`3.0.15-preview.4` 按仓库规则派生 APK `versionCode=10144`，高于 preview.3 的 `10143`。

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
- **同版资产**：`wanBoxForAndroid-3.0.15-preview.4-arm64-v8a-release.apk`、`wanbox-root-module-arm64-v8a.zip`、`wanbox-root-module-with-manager-arm64-v8a.zip`、`SHA256SUMS`。
- **回退**：使用正式稳定版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 与配套 Root 模块（模块码 `2026`）；不要混用不同 Release 的 APK 和模块。

## 🛡️ 验证边界

发布证据：[GitHub Release v3.0.15-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.15-preview.4)，`Preview Build` run `43628453020` 在提交/tag `d6914e70d730c63ee3c3cbb2b00fe2e754b6737e` 成功，四个 job 全部通过。`verify-android` run `43625363368` 在集成提交 `69de6eac6a23d0af65525991c6fff3cbf1a6e620` 成功，instrumentation、迁移、manifest 与体积门禁通过。下载资产的 SHA256SUMS 校验通过，精确字节数和哈希见[版本对照](docs/current-version.zh-CN.md)。未执行 Magisk / KernelSU / APatch 真机矩阵，该验证仍未完成；自动化结果不等同于真实 Root 设备的网络恢复验证。

---

## 历史归档：v3.0.15-preview.3 已发布说明（Release/tag 已清理）

preview.3 Release/tag 已在 preview.4 发布后按单预览策略清理；历史证据由 `Preview Build` run `43404430362` 与原 commit `fe193f1134a80ff38fb0cb3ed4677eb2b79c1dce` 保留。该版承接 preview.2 的底层优化，修正低内存设备 `memory.high` 下限和卸载时 `pref 9001` 规则清理状态误报。Root 模块码为 `2028`，APK `versionCode=10143`；历史资产快照见[版本对照](docs/current-version.zh-CN.md)。

---

## 历史归档：v3.0.15-preview.2 已发布说明（Release/tag 已清理）

**状态：曾发布的稳定性预览版；Release/tag 已在 preview.3 发布并验证后按单预览保留策略清理。** 当时 tag 指向合并提交 `2a57973623db59cb635a869b5d552dd971f8235c`，由 `Preview Build` run `43393988473` 发布；稳定版 `v3.0.14` 未变。该版包含回环长轮询 IPC、热更新守护与卸载清理、故障迁移阈值、独立子 cgroup 和内存软限制等底层优化。

该版 Root 模块码为 `2027`、APK `versionCode=10137`，发布瘦身 APK、普通模块、带管理器模块及 `SHA256SUMS` 四件套。低于 256 MiB 的计算值与卸载侧清理触顶状态处理问题已由 preview.3 修正；preview.2 的历史资产摘要见[版本对照](docs/current-version.zh-CN.md)。

---

## 历史归档：v3.0.15-preview.1 已发布说明（Release/tag 已清理）

**状态：曾发布的稳定性预览版；Release/tag 已在 preview.2 发布后按单预览保留策略清理。** 当时仓库 `Preview Build` tag 指向合并提交 `5aca93bee39a9d07a56d750908e0d9c88ad5f90c`；本版不替换稳定版 `v3.0.14`。

本版重点为 Root 模块免重启热更新加固与卸载治理：新核心经 5 秒 Canary 健康观察并可自动回滚；切换间隙使用 `pref 9001` 黑洞守护；收拢 Magisk / KernelSU 更新标记；卸载流程清理 TUN、策略路由、锁和 IPC，并默认保留用户配置。Root 模块码为 `2027`、APK `versionCode=10136`；历史资产摘要见[版本对照](docs/current-version.zh-CN.md)。

---

## 历史归档：v3.0.14-preview.1 已发布说明（已由正式版承接）

> v3.0.14-preview.1 当时发布说明原文及六项复查修复已由 v3.0.14 正式版全量承接；正式说明见 [RELEASE_NOTES.md](RELEASE_NOTES.md)，历史发布脉络见 [CHANGELOG.md](CHANGELOG.md)。稳定版现为 v3.0.14。
