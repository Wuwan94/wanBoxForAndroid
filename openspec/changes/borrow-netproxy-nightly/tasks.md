# 任务：借鉴 NetProxy nightly（N1–N8）

批次：B5（N2+N1）→ B6（N3 与 F1 合并 + N4 + N5）→ B7（N6）→ B8（N7 契约测试补强）。
N8 只做可行性评估（见 proposal.md），不进实现批次，不设任务。
每批后跟验证阶段；没有实际结果不许勾掉验证任务。
本地只做不依赖 Go/Android SDK 的静态校验；编译与真机验证交给 GitHub Actions 与用户真机。

## 1. B5：N2 脱敏诊断包 + N1 停核可编辑

- [x] 1.1 先写红测：诊断包脱敏表逐条覆盖（订阅地址、节点链接、URL 凭据、认证头/敏感 JSON 键、
  SSID 逐条打码、分应用名单、服务器地址/SNI、公网/私网 IP 打码、MAC、损坏 JSON 省略原文、
  单行 1024 rune 截断）；目标非普通文件拒绝；缺失文件只记 README 不失败；停核导出可用。
- [x] 1.2 实现诊断包导出：Root 侧生成 tar.gz（临时文件+fsync+原子重命名，日志尾部 4 MiB/文件），
  App 设置页一键入口 + 导出前确认文案（默认语言与简体中文）+ 完成/失败反馈；远程规则只导出元数据。
- [x] 1.3 逐面核查并修复 N1：节点浏览/编辑、订阅更新、路由/策略组编辑、URL 测速、速度测速在停止态
  是否可用；凡被运行状态锁住的面按缺陷修（复用独立测试 box 实例与现有 Root 编辑器链，
  不另造离线模式开关）；缺口处补回归测试。
- [x] 1.4 同步 `openspec/specs/android-application/spec.md` 与 `openspec/specs/libcore-integration/spec.md`
  主规范；`git diff --check` 通过；确认版本号与 `SINGBOX_VERSION` 未变；Python 辅助脚本如需写只放
  `tools/diagnostics/`、用 `uv run` 跑，禁止 `python -c`。
- [ ] 1.5 以语义清晰提交到 `feat/borrow-netproxy`（不推远程）；提交信息注明上游来源
  NetProxy HEAD `6f2362d`（nightly）与本 change 名。

### B5 验证阶段

- [ ] 1.6 CI 门：`.github/workflows/verify-android.yml`（Build LibCore、Android build/unit/security、
  Root module tests、manifest、下载预算）与 `.github/workflows/verify-core.yml`（适用时）在提交 SHA
  上通过；记录 workflow run 链接与结论。未通过不得开始 B6。
- [ ] 1.7 真机场景（未执行前不得勾选）：停核后编辑节点/更新订阅/跑 URL+速度测速；导出诊断包，
  解包核查脱敏表逐条成立、远程规则无原文、缺失文件在 README；回传证据：真机截图/录屏 +
  脱敏核查记录（包内不得含真实凭据，核查用虚构数据或已脱敏包）。

## 2. B6：N3 与 F1 合并为一套「按网络」规则 + N4 共享接管 + N5 分应用按用户细化

- [x] 2.1 先写红测：统一规则匹配（SSID 精确/空 SSID 不匹配、gateway/CIDR/精确 IP、egress 类型、
  首条胜出、无匹配保持默认、未知网络不猜测、指纹读取失败保持状态）；`suspend` 复用 F1 状态机
  （挂起/恢复/同 fingerprint 抑制/手动停止不恢复/通知按钮）；`direct` 模式切换回读确认、
  API 失败不重载；F1 规则迁移（顺序保留、动作 `suspend`、关闭不清空、迁移失败保留原数据）。
  （已完成：`rootmodule/network_rules_test.go`、`mode_sync_test.go` 新增 direct 切换测试；
  Go 测试本地通过。）
- [x] 2.2 实现统一「按网络」规则表：单规则表 UI（默认语言与简体中文）替代 F1 独立入口；
  Root supervisor 按实际出口指纹评估；`suspend`/`direct`/`keep` 三动作互斥裁决；
  不请求定位权限；phase 广播仍只用固定值且不含网络标识。
  （已完成：`NetworkRules.kt` + `NetworkRulesActivity.kt`、Go `network_rules.go`、
  `mode_sync.go` 的 `SetRuntimeMode`、`health.go` 的 direct 应用；新规则默认 `keep`；
  评估观察沿用既有 5 秒 health tick、动作只在指纹/动作变化时执行，见 design.md §3 实现说明。）
- [x] 2.3 先写红测：N4 共享路径（默认关闭、无下游接口拒绝、预检失败不自动切数据平面、
  `default_interface=false` 时接口变默认上游暂停/恢复、TUN 下不支持）；N5 按用户名单
  （同包名多用户分别列出、按用户查 UID 合并、空白名单 exclude-all-range、仅共享路径不查 UID、
  旧 UID 迁移与不可反查条目保留）。
  （已完成：`rootmodule/shared_per_user_test.go`、`cmd/wanboxctl/main_test.go` 新增共享命令测试；
  Go 测试本地通过。Kotlin 侧 `RootEbpfInboundTest` 扩展与 `NetworkRulesTest` /
  `SharedInterceptionTest`（含 `PerUserAppsTest`）已写，未本地编译，待 CI 验证。）
- [x] 2.4 实现 N4：eBPF shared 路径开关/接口选择/数据平面二选一/dns_mode/default_interface，
  复用 eBPF 能力预检；TUN 下共享接管明确不支持（LAN 网关文案不混淆）。
  实现 N5：`<userId>:<package>` 键、按用户分组 UI、Root 侧按用户查 UID、旧名单迁移。
  （已完成。待拍板默认值：①接口选择用 Root 枚举候选列表勾选，不手输；②旧字段本轮不设清理版本，
  只读保留。见 design.md §4/§5 与提交说明。）
- [x] 2.5 同步两份主规范与 change delta/design；`git diff --check` 通过；确认版本号、
  `SINGBOX_VERSION` 与 F1 既有 Fail-Closed 屏障未变；提交到 `feat/borrow-netproxy`（不推远程）。
  （已完成：主规范两份已同步；design.md 补 7 处决定记录；`git diff --check` 通过；
  版本号与 `SINGBOX_VERSION` 未动；F1 旧函数与通知/抑制逻辑全部保留。）

### B6 验证阶段（未验证，见 2.6/2.7）

- [ ] 2.6 CI 门：`verify-android.yml` 与 `verify-core.yml`（适用时）在提交 SHA 上通过；
  记录 workflow run 链接与结论。未通过不得开始 B7。**未验证：Kotlin 侧未本地编译，待 CI 验证。**
- [ ] 2.7 真机场景（未执行前不得勾选）：公司/家庭 Wi-Fi 下 `suspend` 与 `direct` 各跑一次
  （命中/离开/同网手动恢复）；热点开启后下游设备流量是否被接管、下游接口变默认上游是否暂停；
  工作资料/分身应用分别勾选后 UID 筛选是否区分；回传证据：真机操作记录 + 模块状态/日志
  （已按 N2 脱敏规则脱敏）。**未验证：未跑真机。**

## 3. B7：N6 路由规则指定策略组

- [x] 3.1 先写红测：规则目标策略组生成（tag 正确、插入位置在本地快捷规则后/内置分流前、
  无法确认位置时走 JSON 编辑器）；缺失组列表标红 + 启动前 modal；删除被引用组阻断；
  节点与组目标互斥拒绝；多条件/logical/invert 保留原样。
  （已完成：`RouteGroupTargetTest` 23 项测试——tag 契约/互斥/表单门/排序/预检/删除
  阻断/备份 JSON 往返；本地无 Java/Gradle 环境，未能运行，待 CI 验证。）
- [x] 3.2 实现 N6：`RuleEntity` 新增可空策略组目标（稳定实体 ID），编辑 UI、出站分区事务内
  引用保护复用现有缺失引用预检；默认语言与简体中文文案。
  （已完成：`policyGroupId` + Room 11 迁移；`RouteGroupTarget` 纯判定；`ConfigBuilder`
  为被引用非主组建 selector 出站、规则写组 tag；`OutboundReferenceValidator`
  增 POLICY_GROUP；`GroupManager.deleteGroup` 事务内阻断；编辑 UI 为独立策略组行 +
  选择器；design.md §10 原句。Kotlin 未本地编译，待 CI 验证。）
- [x] 3.3 同步两份主规范与 change delta/design；`git diff --check` 通过；确认版本号与
  `SINGBOX_VERSION` 未变；提交到 `feat/borrow-netproxy`（不推远程）。
  （已完成：主规范 android-application 增 N6 要求并修正“不新增按组 ID 规则”旧句；
  libcore-integration 主规范与 delta libcore 规格无 N6 落点（无 Root/Go 改动），无改动；
  delta android 规格 phase 1 已含 N6 要求，与实现一致，无改动；design.md §6 补实现落点；
  `git diff --check` 通过；版本号与 `SINGBOX_VERSION` 未动。）

### B7 验证阶段

- [ ] 3.4 CI 门：`verify-android.yml` 在提交 SHA 上通过；记录 workflow run 链接与结论。
  未通过不得开始 B8。**未验证：Kotlin 侧未本地编译，待 CI 验证。**
- [ ] 3.5 真机场景（未执行前不得勾选）：规则指向 `streaming` 组后分流是否走该组；删除被引用组
  是否阻断；缺失组规则标红与启动提醒；回传证据：真机操作记录。**未验证：未跑真机。**

## 4. B8：N7 热更新契约测试补强（只补测试，不动实现）

- [x] 4.1 盘点缺口：对照上游 `tests/customize_hot_update_test.sh` 的覆盖面（三种快照模式、
  热切换互斥/锁顺序、快照失败/重命名失败/服务失败/校验失败/canary 失败），列出 wanBox
  `hot_update_test.go` / `install_test.go` / `install_handoff_test.go` 已覆盖与缺失的用例；
  缺口清单写入本任务的提交说明，不得边盘点边改实现。
  （已完成：缺口清单见 B8 提交说明——上游 20 项逐项对照：已覆盖 12 项、部分覆盖 4 项
  （管理器安装成功路径、最新数据中途变更、锁争用时序、服务停止失败本身）、
  不适用 2 项（标题间距、原生 Catalog 锁）、未覆盖 2 项（两处目录重命名失败分支，
  无故障注入手段、宿主测试无法触发）；另有 syncDir/retireStaging/备份删除失败分支与
  FinishInstallMode 安装器身份核对因设备绑定未覆盖，均已记为遗留缺口，未改实现。）
- [x] 4.2 补 Go 契约测试：fixture 双目录 + 桩外部依赖 + 逐模式断言快照精确内容 + 失败恢复断言；
  fixture 内容全部虚构，不得含真实订阅/凭据。另加薄 shell 测试覆盖 `customize.sh` /
  `installer-options.sh` 委托语义（校验失败 abort、不替换当前模块；`schedule-install` 失败 abort）。
  （已完成：`rootmodule/hot_update_contract_test.go` 13 项 Go 测试（三种模式记账与快照
  精确内容、选择生命周期、data-reset 挂起、损坏快照先败、缺失快照映射差异、文件权限、
  守卫释放失败仍提交、回滚重启失败保留守卫、接管等待失败记 deferred 事件、校验失败无残留、
  数据锁 inode 保留、管理器 APK 三态）+ `rootmodule/test_customize_delegate.py` 10 项
  shell 委托测试（BOOTMODE/ARCH/API 校验 abort、三种模式透传、`schedule-install` 失败
  abort 且不替换当前模块、APK 安装失败只告警仍调度、取消选择不调度）。12 项 Go 测试与
  10 项 shell 测试本地全绿；1 项红测 `TestHotInstallBackupCreationFailureReleasesGuard`
  暴露实现缺陷（备份创建失败时 pref 9001 守卫泄漏），已保留红测、缺陷单独上报，
  本批未改实现。）
- [x] 4.3 同步 `openspec/specs/libcore-integration/spec.md` 主规范与 change delta；
  `git diff --check` 通过；确认版本号与 `SINGBOX_VERSION` 未变；提交到 `feat/borrow-netproxy`
  （不推远程）。**若测试暴露实现缺陷：停下、单独上报（缺陷描述、复现测试、影响范围），
  不得在本批内擅自改实现。**
  （已完成：主规范增 N7 热更新契约测试要求及 4 个场景（含新增的非 preserve 模式记账与
  接管等待失败 deferred 场景）；change delta 同步补两个新场景；design.md §7 补 B8 落点；
  `git diff --check` 通过；版本号与 `SINGBOX_VERSION` 未动；缺陷见 4.2 注与 B8 提交说明，
  修复另立 change。）

### B8 验证阶段

- [ ] 4.4 CI 门：`verify-android.yml`（Root module tests）与 `verify-core.yml`（适用时）
  在提交 SHA 上通过；记录 workflow run 链接与结论。
- [ ] 4.5 真机场景（未执行前不得勾选）：本批为测试补强，真机热更新行为不由本批验证；
  若用户后续在真机执行热更新，须按既有热更新回归（canary 回滚、route guard）回传结果。
  未执行前不得勾选本项。

## 5. 最终边界检查

- [ ] 5.1 确认 B5–B8 各批均有真实 CI 结果；未运行或未通过的 CI 验证项未标记通过。
- [ ] 5.2 确认版本号（正式版 3.0.14 / 预览 3.0.17-preview.1 / 版本码 2026/2031）与
  `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 全程未变；确认未推 main、未创建 PR/Release/tag、
  未推远程；确认 N8 无实现任务落入任何批次。
- [ ] 5.3 确认 proposal.md 的许可证登记无新增复制片段；如实现中确需复制，登记已追加，
  否则不得复制。
