## 1. 批次 1：修复路由优先级、默认广告与 Play 下载规则

- [x] 1.1 扩展 `RouteRulePriorityTest`：构造先出现的国内 geosite 直连 fallback 和后出现的 reject fallback，要求排序结果让 reject 先匹配，同时验证规则实体及 Room 顺序不变；CI 已通过。
- [x] 1.2 增加实际 `ConfigBuilder` 输出测试，覆盖启用 sniff 时先于 Root mixed 探测域名与业务域名规则；自定义 reject 在国内 direct fallback 前；组播 reject 不含 `source_ip_cidr`；`hijack-dns` 后无 port 53/protocol dns 代理兜底；CI 已通过。
- [x] 1.3 增加 ProfileManager/Root 配置测试：默认广告规则用可执行的 `geosite-category-ads-all`；旧 Play route 中 `com.android.providers.downloads` 与 `.ui` 被清理；原 enabled 状态保持；无关 route 不变；迁移实现已通过 CI。
- [x] 1.4 更新 `openspec/specs/android-application/spec.md` 和 change delta/design；`git diff --check` 通过；确认版本文件、`tools/version_code.py`、`buildSrc`、`SINGBOX_VERSION` 未变；未在本地运行 Gradle/Android/Go 编译或测试。
- [x] 1.5 以语义清晰提交并标注上游 commit `aa93dfb2563e3b2b4dff35d2ca40c86942842ae8`；只推送 `port/ownbox-26.11.8.4`；运行 `.github/workflows/verify-android.yml` workflow_dispatch。最终验证提交：`3ac480c40a42447d8013a566d089f785d60b9a65`。
- [x] 1.6 批次 1 CI 门：[`verify-android.yml` run 43487311081](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/43487311081) 成功；Build LibCore/Root module tests、Android build/unit/security、debug instrumentation、release database migrations、manifest 与下载预算检查均通过。此批不创建 PR、GitHub Release 或 tag。真机场景（不阻塞）：未真机验证（按用户明确指示不等待回传）。

## 2. 批次 2：修复分组测速 URL 动态继承

- [x] 2.1 先写可复现测试：空/旧 fallback override 跟随全局 URL、显式组 URL 优先、编辑页 raw override 读写及迁移隔离；Root snapshot 覆盖组级 URLTest/LoadBalance、全局 mixed probe/health-check，并验证单节点 `resolveLink` 使用同一 resolver。最终 CI 均通过。
- [x] 2.2 `GroupSettingsActivity` 初始化/保存 raw per-group override；仅将精确等于旧 `default_group_test_url` 的值迁移为空，保留其他非空 URL；组级 URLTest、LoadBalance、单节点 UI 共用组 URL > 全局 `connectionTestURL` resolver；Root mixed probe/health-check 保持全局 URL。
- [x] 2.3 同步主规范、change delta/design；`git diff --check` 通过；完成自审、规格/标准复审和 Codex 只读检查；只改并推送授权分支，未改版本/内核。
- [x] 2.4 批次 2 CI 门：[`verify-android.yml` run 43507809775](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/43507809775) 成功，验证提交 `3b0ff40f4daab581408d7e7f9c5ef869fb81e350`；Build LibCore、Build Android Debug APK（含单元、安全、debug/release instrumentation）全部通过。此前两个 release instrumentation 失败只涉及跨 APK 测试入口被 R8 裁剪/内联，已用显式构造和 release-test-only 精确 keep 修复，并由此最终 run 验证。未等待真机验证（按用户指示不阻塞），真机场景未执行。

## 3. 批次 3：修复/核验备份格式与旧 wanBox 兼容

- [x] 3.1 审查 `PortableBackup`/`BackupRestore`/`BackupHelper`：确认 v2 使用 `profiles` 与可读 JSON route，旧 v1 支持 `proxies`/Base64 Parcel，RootModuleSnapshot/data archive 与用户备份分离；跨版本 fixture 使用虚构节点、凭据和域名。
- [x] 3.2 新增/扩展 `PortableBackupTest`、`PortableBackupCompatibilityTest`：覆盖新版 JSON 导出、稳定 Root 网络默认、显式设置优先、`INPUT_METHOD_DIRECT` 迁移标记不物化、v1/v2 混合 JSON+Base64 Parcel rules、未选择 settings 保持未选择；测试先行并由远端 CI 验证。
- [x] 3.3 修复仅限 App 备份边界：导出/导入按需补 DataStore 默认并统一严格 Parcel 解码；主规范、change delta 和 design 已同步。`git diff --check` 通过，未改版本或 `SINGBOX_VERSION`。双轴代码复审未发现规格缺项；Codex CLI 两个已配置模型均因 Xingchi API 503 无法返回审查结果。
- [x] 3.4 批次 3 最终门：[`verify-android.yml` run 43534159696](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/43534159696) 成功，SHA `b9e5a0cffd50f9371d8023bfeae33099025511b5`；Build LibCore、Build Android Debug APK、cross-version evidence jobs 均成功。API 35 x86_64 emulator 上旧版 `v3.0.13-preview.3` 与新版双向导入/导出共 4 项各 `OK (1 test)`；测试产物记录在 `closeout-emulator-evidence`，不代表 Root 真机验证。按用户指示未等待真机；该 batch 未创建 PR/Release/tag。

## 4. 批次 4：新增独立 QUIC（UDP 443）开关

- [x] 4.1 先行行为测试覆盖默认开启、保存/读取 false、Root snapshot 配置输出；自动规则精确匹配 UDP/443，显式断言不匹配 TCP/443 与 UDP/80；关闭后无自动规则且保留用户自定义规则。Root snapshot 状态在 `finally` 中恢复。
- [x] 4.2 增加默认开启的 `blockQuic` 设置和独立核心设置 UI；更新默认语言及简体中文文案；`ConfigBuilder` 仅在开启时生成 `network=udp`、`port=443`、`action=reject`，并保留用户显式规则；settings 备份默认物化为 true、显式 false 保留。
- [x] 4.3 同步 `openspec/specs/android-application/spec.md`、change delta/design；完成 Standards/Spec 双轴复审与技能指导下的 Codex 写测/复审；`git diff --check` 通过，确认版本文件及 `SINGBOX_VERSION` 未变。提交 `ca0c9a5`、`43a95fe`、`6d1b335`，仅推送授权分支。
- [x] 4.4 批次 4 CI 门：[`verify-android.yml` run 43546395772](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/43546395772) 在 SHA `6d1b335ca30f48461defa40f6b5f5e51891c66cf` 成功；Build LibCore、Android build/unit/security、Root instrumentation、迁移/manifest、Magisk module 与下载预算 job 全通过。当前 run 的 `Module Size Lab` 与 cross-version evidence 输入关闭；备份跨版本互换已在 [`verify-android.yml` run 43541913645](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/43541913645) 成功，四项旧/新版测试各 `OK (1 test)`，新版备份含 `blockQuic=true`。未改发布工作流，未创建 PR/Release/tag。按用户指示未等待真机；HTTP/3 实际回退和 TCP/443、UDP 非 443 真机连通未验证。

## 5. 批次 5：无效出站规则定位与启动告警

- [x] 5.1 先增加纯规则引用检查测试，覆盖不存在节点、策略组、有效引用与内置 direct/proxy/block；再增加规则列表错误绑定与 startup modal 的 Android 测试。
- [x] 5.2 由 App 配置/数据边界产生脱敏 issue，覆盖 rule ID/name、缺失出站类型/ID；无效行标红；核心启动前 modal 汇总提醒并允许返回修复或明确继续；不得只静默跳过。不得将凭据、地址或原始 stderr 放入 issue/日志。
- [x] 5.3 更新默认语言与简体中文必要文案及 `openspec/specs/android-application/spec.md`；自审有效引用、空名称、多个错误及继续/取消边界，提交并仅推送授权分支。
- [x] 5.4 批次 5 CI 门：[`verify-android.yml` run 43586600129](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/43586600129) 在 SHA `e579cba6d015f4d2f0dc81f993113d38f96e731c` 全绿。Build LibCore、Release build/tests、debug instrumentation 及 API 35 x86_64/KVM 的 `connectedOssReleaseAndroidTest -PwanboxReleaseTests` 均通过；Release instrumentation 步骤 `Validate release database migrations` 成功。推送前本地 Release app/test APK 构建及 JVM 单测通过；本地模拟器 instrumentation 未能启动用例（Sandbox 无 KVM且 PackageManager/system_server 不稳定），不计为通过，由上述 CI runner 完成验证。未真机验证；真机场景为删除被引用节点后查看红色规则及启动提醒/修复路径。

## 6. 批次 6：Root 网络智能挂起/恢复代理

- [x] 6.1 先逐片增加并执行红测：IPv4/IPv6/CIDR/`gateway:` 解析、分隔符与 5 条上限、主机名/非法前缀/appPackage/userId 拒绝、Apply cleanup_required 失败屏障、异步有序广播及命令超时、应用/渠道通知可用性门禁、无物理网络不命中、IPv4/IPv6 默认路由解析、同 fingerprint 手动恢复抑制及切网重置、挂起状态/stop/resume 语义、固定 phase broadcast 参数和 Android 通知契约。Root/Android 本地全测通过；link-local 修复、通知 controller 与设置失败弹窗均有真实红→绿记录。
- [x] 6.2 在高级设置实现规则编辑/校验与持久化；不请求位置权限。Root module 增加网络匹配状态机：命中时优雅停止当前实例核心并确认核心拥有的 TUN/auto-route 清理；离开匹配网络自动恢复；手动恢复对同 fingerprint 抑制重复挂起；手动停止不自动重启。不得全局 flush iptables/nftables 或改动其他实例路由。
- [x] 6.3 通过 RootModulePort/RootModuleClient/wanboxctl 暴露配置、状态、事件与恢复/停止操作；挂起通知保持“恢复”“停止”按钮。Root 只向快照中校验过的应用包名发送固定 phase 广播，App 通过显式 receiver 更新通知；核心退出未确认时报告清理失败，不得谎报已挂起。
- [x] 6.4 同步 `openspec/specs/android-application/spec.md` 与 `openspec/specs/libcore-integration/spec.md`；同步默认语言和简体中文文案；自审短暂断网、同网恢复、网络切换、服务手动停止、Root 重启、核心退出超时和并发按钮边界。按用户最新要求，推送前必须用 Sandbox 系统 Go 1.26.10 通过 Go 单测，并用本地已安装 Android SDK 通过相关 Android build/unit tests。若本地模拟器无法启动 instrumentation，须记录环境阻塞，且远程 CI instrumentation 通过后才可完成批次门禁。Python 辅助脚本仅放 `tools/diagnostics/` 并通过 `uv run` 执行。
- [ ] 6.5 语义提交、仅推送授权分支，运行 `verify-android.yml` 的 Root module tests、libcore/AAR build、Android unit/build/instrumentation 与通知/Root 契约测试。
- [ ] 6.6 批次 6 CI 门：预期状态机、命令、清理、权限和 Android 契约测试全部通过；回传 run URL、SHA、Go/Android CI 报告。真机场景（不阻塞）：配置本地 IP/CIDR/gateway 规则，验证匹配时挂起通知和恢复/停止按钮、离开网络自动恢复、手动恢复同网不再挂起、iptables/route 清理不影响其他网络；未执行必须标明“未真机验证”。

## 7. 最终边界检查

- [ ] 7.1 确认六批均有真实 CI 结果且失败只在对应批次修复；未运行或未通过的 CI 验证项不得勾选。
- [ ] 7.2 最终双轴复审和全部验证通过后，按用户授权准备新预览版 PR/合并/发布；保护稳定版 `v3.0.11`、`SINGBOX_VERSION` 与预览号范围，不删改无关 tag。若新预览版要求超出既有版本限制，先向用户说明冲突并取得明确版本授权；列出未真机验证项及证据。

### Batch 6 Astra scoped remediation validation (local gate only)

User authorization is the scoped exception to the default remote-build policy in `repository-governance`: this task MUST run local Go/Android validation before pushing. The local-remediation substep remains uncommitted and unpushed until those gates pass; afterward, Batch 6 follows task 6.5 for a semantic commit and push to the existing feature branch. This task MUST NOT publish or change versions or `SINGBOX_VERSION`. Fixed-point review baseline: `e579cba6d015f4d2f0dc81f993113d38f96e731c`. Broader Batch 6 and remote/CI gates remain unchecked.

- Notification cleanup contract: red on broad `cancelAll`, green after canceling only `ServiceNotification.notificationId`; legacy channels remain unchanged, smart ID 1041/channel `smart_auto_stop` are preserved.
- Suspended Apply contract: red before excluding `Suspended` from the extra start, green afterward; stopped/other applicable states retain existing behavior.
- Broadcast delivery behavior: red for retry count, stale failed acknowledgement, and swallowed exhaustion error; green for failure-then-success, latest-phase coalescing, and three-attempt exhaustion followed by a future successful worker. New tests use a per-runtime command seam, without mutating global hooks. Retry delay is 10 ms and context-cancelable.
- Latest Android local gate in the ordinary Sandbox: `GRADLE_USER_HOME=/tmp/wanbox-batch6-gradle-home ./gradlew --offline :core-domain:test :app:testOssDebugUnitTest :app:assembleOssDebug` passed (`BUILD SUCCESSFUL`, 31s, 53 actionable tasks). An earlier attempt inside the isolated Codex environment failed before tasks because Gradle could not select a wildcard IP; rerunning in the ordinary Sandbox passed, so that isolation-only failure is not a remaining local Android build/test blocker.
- Final Android gate after rebuilding the native AAR: `GRADLE_USER_HOME=/tmp/wanbox-batch6-gradle-home ./gradlew --offline :core-domain:test :app:testOssDebugUnitTest :app:assembleOssDebug` passed (`BUILD SUCCESSFUL`, 27s, 53 actionable tasks). The targeted `SmartAutoStopControllerTest` and `SmartAutoStopSettingsContractTest` run also passed (8s); XML reports confirm four controller behavior tests and one settings failure-dialog contract test executed.
- RootModule with Go 1.26.10: `go test -race ./...` and `go vet ./...` passed on the final source tree (`wanbox/module` and `wanbox/module/cmd/wanboxctl`).
- `libcore` CI test matrix passed with the pinned local sing-box source: tagged package tests, internal/urltest/loadbalance tests, and race tests for urltest/loadbalance, protect, log, and tun_source. `./run lib core` passed after adding the already-installed `$GOPATH/bin` to `PATH`, producing `app/libs/libcore.aar` and Rootbox libraries for all four ABIs. `bash rootmodule/build.sh` passed, producing the 6.8 MB arm64 CLI and 17 MB module ZIP.
- Static gates passed via `uv run`: 11 RootModule Python tests, 103 tools tests, 4 CI-probe tests, `check_android_security.py ossDebug previewDebug`, `check_core_gate.py`, and `tools/diagnostics/roo_check_repo_governance.py` (31 Python tools/layout); `git diff --check` passed. Version metadata and `SINGBOX_VERSION` are unchanged.
- Local API 35 instrumentation was not run: `/dev/kvm` is unavailable and `emulator -accel-check` reports VT/KVM is unavailable. This is an environment limitation, not a test pass or code failure; remote `verify-android` instrumentation remains required before closing Batch 6.
