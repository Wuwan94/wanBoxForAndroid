# v3.0.15-preview.4 发行说明

**状态：已发布的稳定性预览版。**

本版把 OwnBox `v26.11.8.4` 的六项改动移植进 Root-only 架构，面向已 Root 的 Android 12+ arm64 设备。稳定版 `v3.0.14` / Root 模块码 `2026` 与 sing-box pin `v1.15.0-alpha.13-reF1nd` 均未改变；预览名为 `3.0.15-preview.4`，APK `versionCode=10144`，Root 模块码仍为 `2028`。

## 与上一版对比

| 项目 | v3.0.15-preview.3 | v3.0.15-preview.4 |
|---|---|---|
| 内容 | 长轮询通道、cgroup 软限制与热更新/卸载治理收尾 | 六项移植（路由、测速继承、备份兼容、QUIC 开关、无效出站告警、智能启停）+ 清理确认流 |
| APK `versionCode` | 10143 | 10144 |
| Root 模块码 | 2028 | 2028 |
| APK | 6,200,158 字节 / 5.91 MiB | 6,231,390 字节 / 5.94 MiB |
| 普通模块 ZIP | 17,932,526 字节 / 17.10 MiB | 17,952,780 字节 / 17.12 MiB |
| 合一 ZIP | 23,441,379 字节 / 22.36 MiB | 23,490,404 字节 / 22.40 MiB |

preview.1 起的既有基座本版未改：5 秒 Canary 热更新观察与自动回滚、切换间隙 `pref 9001` 黑洞守护、`127.0.0.1` 只读长轮询状态通道、独立子 cgroup 的 `memory.high`（`MemTotal/4` 钳制于 256 MiB–4 GiB）。

## 本版更新

1. **智能启停代理**：高级设置支持最多 5 条本地 IP、CIDR 或 `gateway:` 指纹规则；不申请定位权限。命中后在 Root 模块内安全挂起核心，通知栏保留「恢复」「停止」；离开网络自动恢复，手动恢复会抑制同指纹重复挂起，且不全局 flush 其他模块的路由规则。
2. **QUIC 控制**：默认开启的独立开关只拦截 UDP 443；关闭后放行 HTTP/3/QUIC，不影响 TCP 443 或其他 UDP 端口。
3. **无效出站告警**：引用不存在节点/策略组的规则标红；启动前集中提醒，用户可返回修复或明确继续。
4. **路由与默认项**：sniff 前置域名分流；适用模式下自定义 reject 前置内置国内直连 fallback；修正组播与 DNS 53 兜底规则；广告列表统一 `geosite-category-ads-all`，不再强制将 Play 下载组件导入代理规则。
5. **分组测速继承**：没有专属地址的分组动态跟随全局连接测试网址，显式分组设置继续优先。
6. **备份兼容**：统一导出 `profiles`，兼容导入旧 `proxies`；路由规则以可读 JSON 导出并可导入旧 Base64 Parcel；补齐核心路由/DNS 默认设置，保留旧 wanBox 备份导入。

## 升级与验证边界

同一 Release 的瘦管理器 APK 与 Root 模块必须成套安装。回退时使用正式版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 和配套模块。已发布：[GitHub Release v3.0.15-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.15-preview.4)，`Preview Build` run `43628453020` 在提交/tag `d6914e70d730c63ee3c3cbb2b00fe2e754b6737e` 四个 job 全部成功；`verify-android` run `43625363368` 在集成提交 `69de6eac6a23d0af65525991c6fff3cbf1a6e620` 的 instrumentation、迁移、manifest 与体积门禁通过。已下载资产通过 SHA256SUMS 校验，字节数与哈希详见[版本对照](current-version.zh-CN.md)。未执行 Magisk / KernelSU / APatch 真机矩阵，智能挂起/恢复与 QUIC 回退等真机场景仍未验证。

---

## 历史归档

v3.0.14-preview.1 与 v3.0.15-preview.1–.3 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](../CHANGELOG.md)。
