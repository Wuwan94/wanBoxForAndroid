# v3.0.13-preview.3 发行说明

**状态：稳定性预览版。** 安装以本 Release 页面四件套及 SHA256SUMS 为准；源码中的发行说明本身不构成已发布证据。不替换 v3.0.11 正式版，未做真机 Root 回归，不据此晋升正式版。

## 更新

- 更新检查与 APK / 模块下载统一使用 `UpdateTransport`，遵守配置的本地 mixed 代理及可选认证。代理不可用时明确失败，不悄悄回退直连；保留 HTTPS、证书及主机名验证、超时、SHA-256 和取消处理。
- 更新任务归属页面生命周期；离开页面取消下载与检查，销毁视图后不操作旧 UI，重复点击不启动多个在途更新。
- 发布 Tag 检查只接受明确 HTTP 404 为不存在，权限、限流、网络与其他错误失败关闭。
- Android 安全检查覆盖实际变体的最终 packaged Manifest，校验导出组件、权限和 action 白名单。
- GeoIP / GeoSite 使用固定版本与 SHA-256，记录来源和构建 provenance，不随 latest 隐式变化。
- 默认构建和验证统一为瘦管理器，CI 同时执行 25 MiB 绝对下载预算及相对回归门禁；测试专用规则保留 Release instrumentation 所需 facade，普通发布 APK 不受此规则影响。

## 安装与版本

- Android 12+、arm64-v8a、Root（Magisk / KernelSU / APatch）；瘦 APK 不含原生核心，由配套 Root 模块持有核心，无普通 VPN 回退。
- `PRE_VERSION_NAME=3.0.13-preview.3`、模块码 `2023`、APK `versionCode=10118`，均高于 preview.2 的 `2022 / 10112`；正式线元数据 `3.0.11 / 2017` 不变。
- 包名、签名配置、设置键、Room schema、备份格式不变。管理器与 Root 模块必须从同一 Release 页面同版成套更新，四件套为瘦 APK、普通模块、带管理器模块与 `SHA256SUMS`。
- Kotlin 网络、规则资源和 TCP 测速保留。已有 JNI URL / 带宽测速、STUN 在瘦模式下仍隐藏，本轮未将这些功能迁移到模块，不宣称恢复。

## 验证与边界

- 本地：Android JVM 381 项、工具 98 项、模块 11 项、诊断 3 项通过；锁定 sing-box 的 Go 测试与 rootmodule race / vet、瘦 Debug APK 构建及安全检查通过。发布版本元数据更新后须再次构建验证。
- 源码 CI `42786037533` 和最新文档 HEAD CI `42790934488` 成功；前次 API 35 瘦 Debug / Release 各 34 项、0 失败、2 跳过，独立完整核心诊断 2 项通过。旧源码签名瘦 APK 加普通模块为 22.76 MiB，不能冒充新版本资产实测；本版以发布工作流重新构建和校验的资产为准。
- 本机没有 `/dev/kvm` 和正式签名材料，CI Release 模拟器及签名检查不是本地设备测试。跨版本备份互通、Magisk / KernelSU / APatch 真机安装矩阵未验证；自动化不替代真机签核。
- 新预览发布并完整回读核验后，清理旧预览 Release 和 tag，只保留最新预览；正式版始终保留。

---

## 历史归档：v3.0.13-preview.2 已发布说明

> 以下保留 preview.2 发布时的原文、测试数及历史语境，不作为 preview.3 验证结果。该版在新预览成功核验后按渠道策略清理。

# v3.0.13-preview.2 发行说明

**状态：稳定性预览版。** 本版由仓库 `Preview Build` 工作流从 main 上经审查的提交构建并发布，不替换 v3.0.11 正式版。已发布正式版为 v3.0.11，上一版预览 v3.0.13-preview.1 的 Release 与 tag 已按预览渠道策略清理；发布前的候选阶段记录保留在提交历史与中文候选报告中，安装请以本 Release 页四件套为唯一来源。未做真机回归，不据此晋升正式版。

## 更新

- **更新渠道与版本身份**：机器版本身份只取校验后的 `tag_name`，`name`（含中文展示标题与数字描述）仅用于展示；正式检查只选正式版、预览检查只选预览版，预览渠道不隐式迁移到更新的正式版。候选按严格语义版本排序，排除 draft、无效 tag、超大数字与不匹配渠道，并排除发布时间与 API 数组顺序的影响；分页有硬边界。资产不全时明确提示并给发布页入口，不说成「已经最新」，也不跨 Release 拼接文件。
- **更新包持有与交接**：已校验的更新包仍按「保留 1 小时、最多 2 份」的既有策略回收，不再表述为对消费者的永久持有保证；补上系统时间回拨、删除失败、并发完成顺序的边界记账。新增受限 FileProvider（只暴露 `cache/updates/`）与「保存 Root 模块 ZIP」「导出最近校验的 Root 模块」入口，升级重启后用户仍能把同版模块交给 Root 管理器，不再只给私有 cache 绝对路径。不静默安装、不开放整个缓存目录。
- **发布文档一致性**：对齐 README 中英快照、版本对照、发布清单、本文件与下一版说明；新增可离线运行的当前版本文档一致性检查及注入漂移的回归测试，挂入现有 Python 验证流程。历史 SHA、测试数量与旧版数值保留归档语境，不改名成本轮结果。

保留 preview.1 的显示偏好重绑、节点列表代数/修订防旧值覆盖、URLTest 五秒总预算与共享在途结果，作为回归保护；本轮不对这些模块做无证据重构。工具链与内核不变：Gradle 8.13 / AGP 8.11.1 / Kotlin 2.2.21 / KSP 2.2.21-2.0.4 / compileSdk 36 / Room 2.7.2；targetSdk 35、minSdk 31、JDK 17、Build Tools 35.0.1；内核固定 `reF1nd/sing-box v1.15.0-alpha.13-reF1nd`。

## 安装与版本

- Android 12+、arm64-v8a、Root（Magisk / KernelSU / APatch），瘦管理器不含内核，无普通 VPN 回退。
- 本版元数据 `PRE_VERSION_NAME=3.0.13-preview.2`、`PRE_VERSION_CODE=2022`，按 `metadataCode*5+previewSuffix` 得 APK `10112`，高于上一版预览的 2021/10106；正式线 `VERSION_NAME=3.0.11`、`VERSION_CODE=2017` 不改动。实际发布产物的版本码已由工作流与资产回读核验。
- 包名、签名、设置键及默认值、Room schema 和备份格式不变；管理器和 Root 模块必须同一发布页同版成套更新。
- **成套下载**：请从本 Release 页下载 arm64-v8a 瘦 APK、普通 Root 模块、带管理器 Root 模块与 `SHA256SUMS`，先核对哈希；管理器和 Root 模块必须同页同版成套更新，不要只更新其中一个。

## 自动化验证及边界

- 本地已实跑：Oss / PreviewDebug JVM 单测各 373 项、Python 工具测试 80 项、两套 debug APK 构建、版本码共享向量、Android 安全检查与 `git diff --check`。
- 仓库 `Verify Android Build` 运行 `42582172641` 全绿；`Preview Build` 运行 `42584446640` 的原生构建、OSS APK、模拟器冷启动测量与发布作业全部成功，四件套资产已回读核验；发布 tag 指向 main 提交 `320f8173e59576d7891ac824c262db68bccf936c`。
- 未做 Magisk、KernelSU、APatch 真机矩阵或实体设备回归；模拟器、JVM 与静态检查不能替代 Root 真机验证。

---

## 历史归档：v3.0.13-preview.1 已发布说明

> 以下为 v3.0.13-preview.1 已发布说明原文（该 Release 与 tag 已按预览渠道策略清理），保留当时语境、版本码与测试数量，不作为本轮 v3.0.13-preview.2 的结果。

# v3.0.13-preview.1 发行说明

稳定性修复预览，不替换 v3.0.11 正式版。未做真机回归。

## 更新内容

本版落实 v3.0.12-preview.4 复审中确认的问题，均为稳定性与一致性修复，无功能增减：

- 更新器不再删掉正在使用的更新包。此前新一次下载开始时会删除上一次已校验就绪的 APK 与 Root 模块 ZIP，而系统安装器或 Root 管理器可能仍在读取；安装 APK 会重启应用，模块要在之后才刷入。现已就绪的更新包保留 1 小时，最多保留最新 2 份；下载失败只清理本次目录；安装入口不可用时立即释放。新增 5 项 JVM 回归测试，覆盖持有期间并发下载、失败只清自身、释放、过期、数量上限与应用重启后的残留。
- 切换「始终显示地址」后返回配置页，已有节点卡片立即按新设置显示。此前只有布局或列数变化才会重绑卡片，地址开关要等滚动后才生效。新增 3 项 JVM 测试。
- 版本码规则改为共享固定向量对照。新增 `tools/version_code_vectors.txt`，Python 规则测试与 Gradle 构建期对 Kotlin 实现的实际计算结果使用同一组向量（正式与预览、预览序号、无效后缀、非正数、溢出边界），任一侧不符即失败；Kotlin 侧补齐此前只有 Python 才有的正整数与溢出校验。

工具链与内核不变：Gradle 8.13 / AGP 8.11.1 / Kotlin 2.2.21 / KSP 2.2.21-2.0.4 / compileSdk 36 / Room 2.7.2；targetSdk 35、minSdk 31、JDK 17、Build Tools 35.0.1；内核固定 `reF1nd/sing-box v1.15.0-alpha.13-reF1nd`。

## 安装与版本

- Android 12+、arm64-v8a、Root（Magisk / KernelSU / APatch），瘦管理器不含内核，无普通 VPN 回退。
- 预览名 `3.0.13-preview.1`，Root 模块码 `2021`，APK `versionCode=10106`；按既有 `metadataCode*5+previewSuffix` 计算，高于 preview.4 的 2020/10104。版本码规则只允许 `-preview.1` 到 `-preview.4`，所以本版从 3.0.13 预览线开始，而不是 3.0.12-preview.5。
- 包名与签名不变，可覆盖安装；备份格式不变。
- 管理器和 Root 模块必须同页同版成套更新，不能只更新其中一个。

## 自动化验证与边界

- 本地验证：更新器 JVM 测试 13 项、显示设置 JVM 测试 3 项全部通过，新增的 4 项生命周期测试在旧代码上确实失败；tools 下 Python 工具测试全部通过；Kotlin 版本码校验函数单独编译实测，能识别人为制造的规则偏差。
- CI 验证：以 PR #17 的 `Verify Android Build` 全量结果为准（Kotlin 编译、全部 JVM 单元测试、Release 变体、数据库迁移、Magisk 模块）。
- 未做 Magisk、KernelSU、APatch 真机矩阵或实体设备回归；自动化结果不等同于这些 Root 环境的实测。本次为预览版，不替换 v3.0.11 正式版。

---
