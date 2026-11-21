# v3.0.18-preview.3 发行说明（候选）

**状态：未发布的稳定性预览版候选。**

## 本版修复

- 在「设置 → 连接与核心 → UDP 回显端点」填写自行控制的数字 IP 与端口、重新应用配置后，各优先级层的子组会独立执行 UDP 回显验证。默认留空，不会向任何默认第三方端点发包；不支持 UDP 回显的服务不能作为端点。
- 仅支持 UDP 的上层成员可凭有效 UDP 回显进入候选，不再依赖 TCP 延迟记录。回切仍要求连续三轮有效的新观测和至少 60 秒无失败；TCP 和 UDP 的记录互不替代。
- 首次应用配置前，模块状态也会回读已经保存的开机自启设置。没有有效配置时不会启动核心；损坏配置仍按不可用处理。
- 开机自启等待确认期间，连续点击按最后一次选择处理，旧回调不会覆盖新选择。

## 安装与验证边界

适用于 arm64-v8a；APK 版本码为 `10193`，Root 模块版本码为 `2038`，均高于 preview.2（`10187 / 2037`）。正式版 `v3.0.17` 和锁定的 sing-box 版本不变。APK、普通 Root 模块、独立 Root 模块及 `SHA256SUMS` 随预览发布；按需选择模块，不要同时安装两种模块。

回归覆盖本地 UDP 回显和自启配置/请求顺序；本次未执行真实代理服务端 UDP 往返或 Root 真机重启验证。

## 历史归档

### v3.0.18-preview.2 已发布说明

**状态：已发布的稳定性预览版。**

本版收口 `v3.0.18-preview.1` 源码复审与代码复审提出的六项缺陷（PR #80，合并提交 `ad032285b3ff181677ed6c0ea9c88a7bb4e482a6`，22 个文件 +2887/−167，`Verify Android Build` run `44717598549` 在 head `58ed0c7` 全绿）。正式线 `3.0.17 / 2035` 与 sing-box pin `v1.15.0-alpha.13-reF1nd` 未变；预览元数据为 `3.0.18-preview.2 / 2037`，派生 APK `versionCode=10187`、Root 模块码 `2037`（preview.1 为 `10181 / 2036`）。

[v3.0.18-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.2) 以 `main` 的 `e7f1f9366316974d8318e8708dcaaa669bc896a0`（升版 PR #81 合并提交，轻量 tag）发布：`Preview Build` run `44728368529` 四个作业全绿、2026-11-20T11:51:54Z 上架（发布 job 断言 `APPROVED_SHA=e7f1f936` 并回读 tag）；四件套 APK `6371663` / 普通模块 `18149031` / 合一包 `23794995` / `SHA256SUMS` `333` 字节，APK+模块 `24520694` 字节（23.38 MiB）余量 `2742282` → PASS，`sha256sum -c` 三件 OK。发布后 11:52:04Z 由 `prune_prereleases.py` 清理 `v3.0.18-preview.1`，正式版 `v3.0.17` / `v3.0.14` / `v3.0.11` 保留。

#### 与上一版对比

| 项目 | v3.0.18-preview.1 | v3.0.18-preview.2 |
|---|---|---|
| 内容 | 层级状态按 TCP/UDP 拆分；App UI 对齐模块功能 | 观测按（成员, 网络）分账、UDP 只认真实回显、降级作废旧回切资格、组关闭停拨备用；自启下发→回读→落库闭环、`boot.json` 三态 |
| APK `versionCode` | 10181 | 10187 |
| Root 模块码 | 2036 | 2037 |

URLTest 间隔 ≥10 秒、空闲 ≥30 分钟、切换不中断现有连接与「连续 3 轮健康 + 60 秒无失败」回切门均未放宽；证据不足时停在当前层级，不回切。

#### 本版更新

1. **观测按（成员, 网络）分账**：`urlTestRound` 的观测改为 `tcp` / `udp` 两张表，两层恢复门只读自己那份；跳过与取消都不计入健康轮次；嵌套组 summary 按网络各算一份。旧实现下「TCP 成功把 UDP 门推到 3」「TCP 失败清掉 UDP 历史」两条黑盒用例先红。
2. **UDP 探测只认真实往返回显**：发出探测数据报，只有收到目标原样回显才 `verified`；`rejected` 清零、`unverified` / `skipped` / `canceled` 中性；目标只来自用户配置 `udp_probe`（不默认第三方服务、不加合成 DNS），单成员一条、超时缺省 3 秒且不超过整轮 5 秒预算。应用侧暂无配置入口，默认零流量，这是刻意的 fail-closed。
3. **降级即作废旧回切资格**：`promoteFailover` 同一写锁内清空失败层、作废所有更高层连续健康计数、`currentTier` 只下移且只作用于被拨网络；门代次（`epoch`）递增使跨降级的迟到成功观测不再授予回切资格。
4. **组关闭停拨备用**：容灾序列绑定组生命周期（`bindGroupLifecycle`），`Close` 即取消在途尝试，迟到连接与 `PacketConn` 经 `wrapConn` / `wrapPacketConn` 释放并返回统一错误。
5. **开机自启闭环**：模块 `State` 新增只读 `autoStart`（停止态也可下发与回读）；`AutoStartSync` 下发后必须回读确认才落库，失败/超时重试一次、仍失败回滚提示，快速连续切换由最后一次落地，返回设置页不一致显示「待同步」。
6. **`boot.json` 三态**：`bootSettingsFor` 让 `Status()` 与 `Boot()` 同一种读法——不存在回落快照、损坏/缺字段为 `boot_settings_invalid`、权限或 IO 为 `boot_settings_unreadable`，后两者 `autoStart=null` 且 `Boot()` 以同一错误码拒绝启动，坏文件不被改写或删除；`SetAutoStart` 仍是原子写并作为唯一修复路径。

#### 升级与验证边界

同一 Release 的瘦管理器 APK 与 Root 模块必须成套安装；回退用正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17) 的 APK 与配套模块（模块码 `2035`）。PR 门禁 `Verify Android Build` run `44717598549` 全绿（`go test -race`、`go vet`、Python 工具 125 项、`rootmodule` 28 项、文档门禁、JVM 单测与插桩）；升版 PR #81 的 run `44723295827` 首跑红在 plugins.gradle.org 解析 `kotlin-gradle-plugin:2.0.21` 瞬时不可用，重跑后全绿。发布 `Preview Build` run `44728368529` 四作业全绿，四处同源均为 `e7f1f936`；合一包内 `manager.apk` 与外部 APK 逐字节相同；签名证书仍为 `80ecee1c…21f6`。未执行 Root 真机矩阵（CI 自标 `runtime_regression: not_verified`）；UDP 探测为本机可控回显端点，非真实代理服务端往返；真机 `boot.json` 权限与开机行为未验证。自动化结果不等同于真机验证。

---

### v3.0.18-preview.1 已发布说明

[v3.0.18-preview.1](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.1) 已作为稳定性预览版发布：main `0a30baaf939a97b1941210609ce377e00d386157`（轻量 tag），`Preview Build` run `44516333684` 四作业全绿、2026-11-19T11:55:25Z 上架；预览元数据 `2036`，APK `10181` / 模块 `2036`。内容为 PR #77（层级状态按 TCP/UDP 拆分）与 PR #78（UI 对齐模块功能）。已知边界：一次探测的观测仍按 tag 共享、嵌套组 summary 与网络无关。本版 Release 已于 11:52:04Z 在 preview.2 发布时由 `prune_prereleases.py` 清理（现为 404）。

### v3.0.17-preview.4 已发布说明

基于 main `906a6ea3f48779cc96c7a4116cfd1faeb63aa354`（PR #72，`Verify Android Build` run `44372679147`），`Preview Build` run `44378329740` 于 2026-11-18T19:38:07Z 发布；APK `10174` / 模块 `2034`。内容为故障转移限定生效层、过期候选兜底、direct 有界重试、`su` 超时与空白名单分离，另收口三项。Release/tag 已在 `v3.0.18-preview.1` 发布后清理（11:55:35Z），下载链接现为 404。

v3.0.17-preview.3 的已发布说明：tag/commit `6adc5ac0`，PR #69，`Verify Android Build` run `44295650161`，`Preview Build` run `44306599880` 于 2026-11-18T13:08:01Z 发布；APK `10168` / 模块 `2033`，Release/tag 已在 preview.4 发布后（19:38:15Z）按单预览策略清理。

v3.0.17-preview.2 的已发布说明：tag/commit `49e96619`，`Preview Build` run `44281162788` 于 2026-11-18T10:18:19Z 发布；APK `10162` / 模块 `2032`，Release/tag 已在 preview.3 发布后按单预览策略清理。

v3.0.17-preview.1 的已发布说明：tag/commit `33391d1`，`Preview Build` run `44076008956` 于 2026-11-17T12:01:45Z 发布；内容为悬空出站引用下放弃编辑不再死循环与配置构建 Toast 改主线程分发，APK `10156` / 模块 `2031`；其 Release/tag 已在 preview.2 发布后按单预览策略清理。v3.0.16-preview.4 的已发布说明：tag/commit `9283ccb`，`Preview Build` run `44013891166` 于 2026-11-17T04:18:56Z 发布；内容为组成员单一真相、订阅撞名避让、选中节点显式回落、恢复失败逃生口与设置口径统一，APK `10154` / 模块 `2030`。v3.0.16-preview.3 的已发布说明：tag `fa6b95b`，首发 `Preview Build` run `43906481811`，资产由 main `000d302` 经受审查流程替换。v3.0.14-preview.1、v3.0.15-preview.1–.4 与 v3.0.16-preview.1、v3.0.16-preview.2 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](../CHANGELOG.md)。
