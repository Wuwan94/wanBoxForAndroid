## 1. 批次 1：修复路由优先级、默认广告与 Play 下载规则

- [x] 1.1 扩展 `RouteRulePriorityTest`：构造先出现的国内 geosite 直连 fallback 和后出现的 reject fallback，要求排序结果让 reject 先匹配，同时验证规则实体及 Room 顺序不变；CI 已通过。
- [x] 1.2 增加实际 `ConfigBuilder` 输出测试，覆盖启用 sniff 时先于 Root mixed 探测域名与业务域名规则；自定义 reject 在国内 direct fallback 前；组播 reject 不含 `source_ip_cidr`；`hijack-dns` 后无 port 53/protocol dns 代理兜底；CI 已通过。
- [x] 1.3 增加 ProfileManager/Root 配置测试：默认广告规则用可执行的 `geosite-category-ads-all`；旧 Play route 中 `com.android.providers.downloads` 与 `.ui` 被清理；原 enabled 状态保持；无关 route 不变；迁移实现已通过 CI。
- [x] 1.4 更新 `openspec/specs/android-application/spec.md` 和 change delta/design；`git diff --check` 通过；确认版本文件、`tools/version_code.py`、`buildSrc`、`SINGBOX_VERSION` 未变；未在本地运行 Gradle/Android/Go 编译或测试。
- [x] 1.5 以语义清晰提交并标注上游 commit `aa93dfb2563e3b2b4dff35d2ca40c86942842ae8`；只推送 `port/ownbox-26.11.8.4`；运行 `.github/workflows/verify-android.yml` workflow_dispatch。最终验证提交：`3ac480c40a42447d8013a566d089f785d60b9a65`。
- [x] 1.6 批次 1 CI 门：[`verify-android.yml` run 43487311081](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/43487311081) 成功；Build LibCore/Root module tests、Android build/unit/security、debug instrumentation、release database migrations、manifest 与下载预算检查均通过。此批不创建 PR、GitHub Release 或 tag。真机场景（不阻塞）：未真机验证（按用户明确指示不等待回传）。

## 2. 批次 2：修复分组测速 URL 动态继承

- [ ] 2.1 先写可复现测试：空/旧 fallback override 跟随变化的全局 URL、显式组 URL 优先、编辑页读写 raw override，并以真实 Root snapshot 覆盖 URLTest 与 LoadBalance。
- [ ] 2.2 调整 `GroupSettingsActivity` 初始化为 raw per-group override；仅将精确等于旧 `default_group_test_url` 的固定 fallback 迁移为空，保留其他非空值；统一 resolver 顺序为组 URL > `connectionTestURL` > 安全默认，并供 URLTest/LoadBalance/Root snapshot/resolveLink 共用。
- [ ] 2.3 同步 `openspec/specs/android-application/spec.md`；做静态检查、自审、语义提交并仅推送授权分支。
- [ ] 2.4 批次 2 CI 门：运行 `verify-android.yml` unit/build/instrumentation；预期 group URL 和 Root snapshot 测试通过、显式地址未改变。回传 run URL、SHA、job 结果。真机场景（不阻塞）：修改全局地址、重建 Root 配置，确认未设组 URL 的组跟随、显式组保持覆盖；如未执行须报告。

## 3. 批次 3：修复/核验备份格式与旧 wanBox 兼容

- [ ] 3.1 审查 `PortableBackup`/`BackupRestore`/`BackupHelper` 与真实 fixtures：新导出 `profiles`、可读 route JSON、核心路由/DNS 默认值；导入旧 `proxies`、Base64 Parcel rule、旧 wanBox 备份。Root module snapshot/data archive 不属于用户备份。
- [ ] 3.2 对未覆盖行为先加可复现 fixture 测试：新导出解码、旧 wanBox 往返、JSON+Base64 mixed rule、设置缺省补齐与显式值保留。现有实现/测试完整时不重复改写，只保留核验结论。
- [ ] 3.3 若有测试暴露缺口，只改 App 备份边界；同步 `openspec/specs/android-application/spec.md`，静态自审、语义提交、仅推送授权分支。
- [ ] 3.4 批次 3 CI 门：运行 `verify-android.yml` 中 `PortableBackupTest`、`PortableBackupCompatibilityTest`、`BackupCrossVersionTest` 所属 unit/instrumentation jobs；预期旧新格式往返和默认设置测试通过。回传 run URL、SHA、fixture 与 job 结果。真机场景（不阻塞）：导入旧 wanBox 备份再导出/导入新版，核对数据保持；未执行须标明。

## 4. 批次 4：新增独立 QUIC（UDP 443）开关

- [ ] 4.1 先为开关默认值、Core settings preference 持久化和配置输出写回归测试：开时生成 UDP/443 reject；关时不生成自动 reject；UDP 非 443 与 TCP 443 不受自动规则影响；Root snapshot 传递一致。
- [ ] 4.2 增加默认开启的 `blockQuic` 设置和独立核心设置 UI；更新默认语言及简体中文文案；`ConfigBuilder` 仅在开启时生成 `network=udp`、`port=443`、`action=reject`。保留用户显式自定义规则。
- [ ] 4.3 同步 `openspec/specs/android-application/spec.md`，自审并检查语言资源键、设置默认值与 `SINGBOX_VERSION` 不变；语义提交并仅推送授权分支。
- [ ] 4.4 批次 4 CI 门：`verify-android.yml` unit/build/instrumentation 和 Root snapshot tests 通过，确认没有 Release/tag 操作。回传 run URL、SHA、测试结果。真机场景（不阻塞）：独立开关开/关测试 HTTP/3 回退及 UDP 非 443/TCP 443 连通；未执行须报告。

## 5. 批次 5：无效出站规则定位与启动告警

- [ ] 5.1 先增加纯规则引用检查测试，覆盖不存在节点、策略组、有效引用与内置 direct/proxy/block；再增加规则列表错误绑定与 startup modal 的 Android 测试。
- [ ] 5.2 由 App 配置/数据边界产生脱敏 issue，覆盖 rule ID/name、缺失出站类型/ID；无效行标红；核心启动前 modal 汇总提醒并允许返回修复或明确继续；不得只静默跳过。不得将凭据、地址或原始 stderr 放入 issue/日志。
- [ ] 5.3 更新默认语言与简体中文必要文案及 `openspec/specs/android-application/spec.md`；自审有效引用、空名称、多个错误及继续/取消边界，提交并仅推送授权分支。
- [ ] 5.4 批次 5 CI 门：运行 unit/build/instrumentation 和 ConfigBuilder/Root snapshot checks；预期 missing node/group 标红并在启动前呈现，合法 route 不误报。回传 run URL、SHA、报告。真机场景（不阻塞）：故意删除被引用节点后打开路由页/启动，确认红色条目、提醒内容与修复路径；未执行须报告。

## 6. 批次 6：Root 网络智能挂起/恢复代理

- [ ] 6.1 先为最多 5 条 IP/CIDR/`gateway:` 规则解析/匹配、无定位权限、挂起/恢复状态机、同网络手动恢复抑制与切网重置写纯 Go 测试；为 RootModulePort/client/通知交互写契约测试。
- [ ] 6.2 在高级设置实现规则编辑/校验与持久化；不请求位置权限。Root module 增加网络匹配状态机：命中挂起核心并清理仅本模块拥有的 iptables/路由；离开匹配网络自动恢复；手动恢复对同 fingerprint 抑制重复挂起；手动停止不自动重启。
- [ ] 6.3 通过 RootModulePort/RootModuleClient/wanboxctl 暴露配置、状态、事件与恢复/停止操作；挂起通知保持“恢复”“停止”按钮；清理与恢复必须幂等，失败不得谎报成功，不得残留断网规则。
- [ ] 6.4 同步 `openspec/specs/android-application/spec.md` 与 `openspec/specs/libcore-integration/spec.md`；同步默认语言和简体中文文案；自审短暂断网、同网恢复、网络切换、服务手动停止、Root重启、iptables 部分失败和并发按钮边界。禁止本地 Go/Android 编译。
- [ ] 6.5 语义提交、仅推送授权分支，运行 `verify-android.yml` 的 Root module tests、libcore/AAR build、Android unit/build/instrumentation 与通知/Root 契约测试。
- [ ] 6.6 批次 6 CI 门：预期状态机、命令、清理、权限和 Android 契约测试全部通过；回传 run URL、SHA、Go/Android CI 报告。真机场景（不阻塞）：配置本地 IP/CIDR/gateway 规则，验证匹配时挂起通知和恢复/停止按钮、离开网络自动恢复、手动恢复同网不再挂起、iptables/route 清理不影响其他网络；未执行必须标明“未真机验证”。

## 7. 最终边界检查

- [ ] 7.1 确认六批均有真实 CI 结果且失败只在对应批次修复；未运行或未通过的 CI 验证项不得勾选。
- [ ] 7.2 确认未改 main、版本号、`SINGBOX_VERSION`、正式版 `v3.0.11`；未创建 PR、Release 或 tag；列出未真机验证项及用户后续可选的验证证据。
