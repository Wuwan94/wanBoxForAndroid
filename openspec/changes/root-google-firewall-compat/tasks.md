# 任务：Root 模块 OEM Google 联网兼容（PR-C）

## 1. 精确、best-effort 的 Root 修复（唯一实现批次）

- [x] TDD 实现专用链规则精确匹配/删除、user 0 包 UID 解析与共享 UID 排除、固件及 ColorOS/BPF 门禁。
- [x] 新增双栈读取全量预检：任一存在族读取失败时零删除；删除执行遇错立即停止后续规则。
- [x] 新增 ColorOS 所有已存在目标 pin/program/map 全量结构预检；未知结构时全局零修改，缺失 pin 可跳过；map 操作遇错即停止后续 map。
- [x] 每条防火墙规则及每个 BPF map 项实际删除前，重新检查模块启用/卸载标记。
- [x] 接入 boot-ready 1 分钟延迟调度、成功热激活锁外立即调度、固定 Root-only 内部命令及参数校验；失败/回滚不调度，调度失败不阻断事务。
- [x] 覆盖规则、双栈零删除、BPF 全量预检/缺失 pin、删除失败停止、逐项标记守卫、UID/固件/策略、生命周期、模块标记和 CLI 授权等 Go 测试；新增安全用例先确认失败再实现。
- [x] 同步 Root 模块中文 README、OpenSpec 主规范与本变更 proposal/design/spec delta。
- [x] PR-C 专项 Go 回归：`go test -count=1 -run 'Test(GoogleFirewall|GooglePackage|BootSchedulesGoogleRepairIndependentOfCoreAutostart|SuccessfulHotActivationSchedulesGoogleRepairAfterInstallLockRelease|RolledBackHotActivationDoesNotScheduleGoogleRepair|GoogleRepairSchedulerFailureDoesNotBlockBootOrCommittedUpdate|ColorOSGoogle|GoogleFirewallModuleActiveRequiresInstalledExecutableAndNoMarkers|GoogleFirewallDelayArgsAreFixedAndBounded|GoogleFirewallPreflightsBothFamiliesBeforeDeleting|ColorOSGooglePreflightsAllPresentPinsBeforeDeleting|ColorOSGooglePreflightSkipsAbsentPinsAndRepairsVerifiedPins|GoogleFirewallMutationGuardRunsImmediatelyBeforeDelete|ColorOSGoogleEntryMutationGuardRunsBeforeEachDelete|GoogleFirewallMutationGuardRechecksDisableAndRemoveMarkers)' ./...` 通过；后续新增的删除失败停止用例另行聚焦通过。
- [x] `go vet ./...` 通过。
- [x] Python 单测通过：`python3 -m unittest discover -s rootmodule -p 'test_*.py'`（28 项）；`python3 -m unittest discover -s tools -p 'test_*.py'`（132 项）；`python3 -m unittest discover -s tools/diagnostics -p 'test_*.py'`（13 项）。
- [x] 治理门禁 `uv run tools/diagnostics/roo_check_repo_governance.py` 通过（检查 34 个 Python 工具和 OpenSpec 布局）。
- [x] Android ARM64 本地交叉编译通过：`GOOS=android GOARCH=arm64 go test -c -o /tmp/wanboxmodule-android-arm64.test . && GOOS=android GOARCH=arm64 go build -o /tmp/wanboxctl-android-arm64 ./cmd/wanboxctl`。
- [x] 已格式化 Go 文件，`git diff --check` 通过；确认未修改 `nb4a.properties`、Release/Tag 或 `Wuwan94.github.io`。
- [x] 最终本地授权 Race 门禁：`go test -race ./...` 通过（最后一次实现/测试改动后重跑；终端输出两个 Go 包均为 `ok`）。
- [x] 协调者完成 Standards/Spec 两轴独立复核，未发现新增阻断；两份异步补充审查仍在运行，若返回新发现须在 PR 中修正。

## 1.1 CI / 设备验证阶段（本实现批次之后）

- [ ] GitHub Actions `Verify Android Build` 全绿：Root Go `-race`/vet/Python 测试通过，Android ARM64 Root 模块编译成功。
- [ ] 同一 workflow 的 Xray v26.11.9 XHTTP 互通门禁必须实际跑满 12 个子用例；不能 SKIP 或少于 12。
- [ ] 真机待测：Magisk/KernelSU 至少各覆盖一台目标机；RedMagic/努比亚/中兴核对 `zte_fw_gms` 目标规则删除及 DNS/共享链保留；OnePlus/OPPO ColorOS 16 核对用户/策略门禁、三个 BPF map 的 tag/关联/结构、仅目标 UID value `1` 删除及其他项保留。
- [ ] 真机证据应记录设备型号与 ROM 版本、Root 管理器版本、修复前后目标规则/map 条目以及非目标规则/map 保持不变；不要记录或上传 HWID/账号/代理凭据。
- [ ] CI 通过不等同于 Root 真机支持已证实；无设备时明确保留待验状态。
