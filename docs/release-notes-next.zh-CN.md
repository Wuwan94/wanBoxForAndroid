# v3.0.15-preview.4 发行说明（候选）

**状态：未发布的稳定性预览版候选。**

本版在 Root-only 架构中适配六项功能与修复，面向已 Root 的 Android 12+ arm64 设备。稳定版 `v3.0.14` / Root 模块码 `2026` 与 sing-box pin `v1.15.0-alpha.13-reF1nd` 均保持不变；预览名为 `3.0.15-preview.4`，APK `versionCode=10144`，Root 模块码仍为 `2028`。

## 本版更新

1. **智能启停代理**：高级设置支持最多 5 条本地 IP、CIDR 或 `gateway:` 指纹规则；不申请定位权限。命中后在 Root 模块内安全挂起核心，通知栏保留「恢复」「停止」；离开网络自动恢复，手动恢复会抑制同指纹重复挂起，且不全局 flush 其他模块的路由规则。
2. **QUIC 控制**：默认开启的独立开关只拦截 UDP 443；关闭后放行 HTTP/3/QUIC，不影响 TCP 443 或其他 UDP 端口。
3. **无效出站告警**：引用不存在节点/策略组的规则标红；启动前集中提醒，用户可返回修复或明确继续。
4. **路由与默认项**：sniff 前置域名分流；适用模式下自定义 reject 前置内置国内直连 fallback；修正组播与 DNS 53 兜底规则；广告列表统一 `geosite-category-ads-all`，不再强制将 Play 下载组件导入代理规则。
5. **分组测速继承**：没有专属地址的分组动态跟随全局连接测试网址，显式分组设置继续优先。
6. **备份兼容**：统一导出 `profiles`，兼容导入旧 `proxies`；路由规则以可读 JSON 导出并可导入旧 Base64 Parcel；补齐核心路由/DNS 默认设置，保留旧 wanBox 备份导入。

## 升级与验证边界

同一 Release 的瘦管理器 APK 与 Root 模块必须成套安装。回退时使用正式版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 和配套模块。本地 Go race/vet、Root arm64 构建、libcore Go 测试、Android JVM 测试与 APK/AndroidTest APK 编译通过；Sandbox 无 KVM，本地模拟器 instrumentation 未执行。Preview Build 发布门禁仍须通过精确 SHA、资产与 CI 模拟器检查；未做真实 Magisk / KernelSU / APatch 设备矩阵。

---

## 历史归档：v3.0.15-preview.3 已发布说明

preview.3 由 PR #39 合并提交 `fe193f1134a80ff38fb0cb3ed4677eb2b79c1dce` 发布，官方 `Preview Build` run `43404430362` 成功，稳定版 `v3.0.14` 未变。该版修正低内存设备 `memory.high` 下限与卸载时 `pref 9001` 规则清理状态误报；Root 模块码为 `2028`、APK `versionCode=10143`。其资产摘要见[版本对照](current-version.zh-CN.md)。

---

## 历史归档：v3.0.15-preview.2 已发布说明（Release/tag 已清理）

preview.2 曾由 `Preview Build` run `43393988473` 发布，tag 原指向 `2a57973623db59cb635a869b5d552dd971f8235c`；preview.3 发布验证后按单预览策略清理。该版包含回环长轮询、热更新守护与卸载清理、故障迁移阈值、独立子 cgroup 和内存软限制。模块码 `2027`、APK `versionCode=10137`；低内存阈值和卸载清理触顶问题随后由 preview.3 修复。

---

## 历史归档：v3.0.15-preview.1 已发布说明（Release/tag 已清理）

preview.1 曾由 `Preview Build` run `43203156796` 发布，tag 指向 `5aca93bee39a9d07a56d750908e0d9c88ad5f90c`；随后按单预览策略清理。该版加强 Root 模块免重启热更新健康观察、自动回滚、`pref 9001` 切换守护及卸载清理；模块码 `2027`、APK `versionCode=10136`。

---

## 历史归档：v3.0.14-preview.1 已发布说明（已由正式版承接）

> v3.0.14-preview.1 的历史说明和复查修复已由 v3.0.14 正式版承接；正式说明见 [RELEASE_NOTES.md](../RELEASE_NOTES.md)，历史发布脉络见 [CHANGELOG.md](../CHANGELOG.md)。
