# v3.0.14-preview.1 发行说明

**状态：已发布的稳定性预览版。** 由仓库 `Preview Build` 正式发布；[Release 页面](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14-preview.1)的 tag 指向合并提交 `8b4e60893dc4d783caeab1b0f5ddb6667aa0549d`。本版不替换稳定版 `v3.0.11`。

稳定性预览版，不替换稳定版 `v3.0.11`。正式发布的 arm64-v8a 管理器 APK、普通 Root 模块、带管理器 Root 模块与 `SHA256SUMS` 必须由仓库 `Preview Build` 流程生成；请仅从本 Release 页下载同版整套文件并先核对哈希。本地临时测试签名 APK 不属于发布资产。

## 更新

本版在 preview.4 基础上落实 v3.0.13-preview.4 代码复查确认的剩余六项修复，均为更新器与发布工具链的稳定性、一致性修复，无功能增减；preview.4 的功能内容（更新器生命周期、瘦模式节点延迟探测、QS Tile 原子切换、分应用列表图标缓存等）保持不变：

- **版本规则漂移守卫补齐**：`UpdateRelease.TAG_REGEX` 是版本规则的第三份拷贝且此前无守卫；现 `tools/version_code.py --check-app-tag` 将其纳入与 Kotlin/Python 同源的漂移检查，锚点缺失或范围漂移直接报错。
- **更新包按版本戳绑定**：`UpdateDownload` 在下载校验通过后写入版本戳，`recentReady()` 只导出与当前已装 App 版本一致的更新包；此前仅按修改时间取「最近」一份，可能导出版本不匹配的模块，无版本戳的旧包不再被当作同版导出。
- **导出独立守卫**：关于页导出最近模块改用独立的 `UpdateTask` 守卫，不再与检查/下载共用单飞锁；此前下载进行中点导出会被静默吞掉、无任何反馈。
- **发布 tag 检查按状态码判定**：`tools/check_release_tag.sh` 改用 `gh api -i` 的 HTTP 状态行判定 tag 是否存在，不再匹配 gh 的报错文本，文本变化不再导致误判。
- **代理不可用只认源头标记**：更新传输层在本机 mixed 代理连接被拒时抛出专属的 `UpdateProxyUnavailable` 标记，失败分类只认该标记；此前任意 `ConnectException` 都会被误判为代理不可用。
- **清理已提交字节码**：删除误提交的 14 个 `tools/__pycache__/*.pyc`，`.gitignore` 增加 `__pycache__/` 与 `*.pyc`。

## 安装与版本

- Android 12+、arm64-v8a、Root（Magisk / KernelSU / APatch），瘦管理器不含内核，无普通 VPN 回退。
- 预览名 `3.0.14-preview.1`，Root 模块码 `2025`，APK `versionCode=10126`；按既有 `metadataCode*5+previewSuffix` 计算，高于 preview.4 的 2024/10124。
- 发布候选须提供瘦 APK、普通 Root 模块、with-manager 模块及 SHA256SUMS，管理器和模块必须同页同版成套，不能只更新其中一个。

## 自动化验证与边界

- 六项修复由 PR #25 合入 main（合并提交 `1d769fad65768029374134a37bcb67d163deb42f`），其 Verify CI（run `42976607018`）的 Build LibCore 与 Build Android Debug APK 均成功；本地 Python 工具测试 108 项先行通过。
- preview.4 的同版覆盖重发曾被发布流程拒绝：本说明在 preview.4 发布后已翻为已发布状态，仓库校验不接受同一版本二次发布。且版本规则每线最多 preview.4，故新开 3.0.14 线顺延为 preview.1，修复内容与原计划一致。
- 正式 `Preview Build` run `43003022425` 从获批合并提交 `8b4e60893dc4d783caeab1b0f5ddb6667aa0549d` 生成并发布正式签名资产；发布后四件套、API digest 与 `SHA256SUMS` 均已回读核验，文件名、体积与哈希见[版本对照](docs/current-version.zh-CN.md)。
- 未做 Magisk / KernelSU / APatch 真机矩阵；模拟器、JVM 与静态检查不能替代 Root 真机签核。本次为预览版，不替换 v3.0.11 正式版。

---

## 历史归档：v3.0.13-preview.4 已发布说明

> 以下为 preview.4 当时发布说明摘要与历史验证记录，不是 v3.0.14-preview.1 的功能或测试证据；preview.4 的 Release/tag 已删除，本版 v3.0.14-preview.1 为其复查修复的顺延发布，稳定版 v3.0.11 保留。

# v3.0.13-preview.4 发行说明

稳定性预览版，不替换稳定版 `v3.0.11`。正式发布的 arm64-v8a 管理器 APK、普通 Root 模块、带管理器 Root 模块与 `SHA256SUMS` 必须由仓库 `Preview Build` 流程生成；请仅从本 Release 页下载同版整套文件并先核对哈希。本地临时测试签名 APK 不属于发布资产。

## 更新

- **更新器生命周期**：页面 `onStop()` 只关闭对话框，检查/下载继续；返回时恢复进行中的任务或交付已完成结果。只有 `onDestroyView()` 才取消 view-scope 任务并清理未交接的本次下载目录；快速取消后重试不再被旧 Job 槽位阻塞。
- **代理失败提示**：更新流量只经配置的本地 mixed 代理；本地连接拒绝或 mixed 入站禁用会明确提示核心/代理不可用，不作直连回退。失败弹窗保留打开 GitHub Release 页的手动下载入口。
- **瘦模式节点延迟探测**：Kotlin 只读运行中的 profile-tag 映射，通过 `Proxy.NO_PROXY` 与 `Authorization` 请求绑定在 `127.0.0.1` 的 Clash Controller `/proxies/{tag}/delay`；不改当前节点、不跟随重定向、不经 mixed 代理绕回，瘦 APK 不含核心 JNI。
- **QS Tile**：Connecting/Stopping 状态下忽略点击，服务切换前绕过状态缓存读取 fresh Root 状态，并在 Root 模块共享变更锁内原子决策和执行；回归测试覆盖过渡态、稳定态与并发变更争用。
- **分应用列表**：包名/label 查询留在后台；可见行图标异步按需加载并引入 120 项原生 `LruCache` 内存缓存，复用 ViewHolder 前检查身份并在 Activity 销毁时清空引用，彻底消除快速滚动白块与重复 IPC；保留用户/系统应用过滤、UID 路由及导入导出路径。

## 安装与版本

- Android 12+、arm64-v8a、Root（Magisk / KernelSU / APatch）；瘦 APK 不含原生核心，由配套 Root 模块持有核心，无普通 VPN 回退。
- `PRE_VERSION_NAME=3.0.13-preview.4`、Root 模块码 `2024`、APK `versionCode=10124`；正式线 `3.0.11 / 2017` 不变。版本码由共享固定向量计算。
- 包名、设置键及默认值、Room schema、备份/还原格式和同页同版配对规则保持不变；26 MiB 体积门禁已由远端验证流程通过。

## 自动化验证与边界

- PR #21 的应用代码提交 `46c342fb292c5e040e033bfc6c49742dcc4e7dc0` 通过 PR CI `42938060987` 和完整 verify/closeout `42938103418`。加入发布说明隔离修正后的最终 PR head `ffdfc8e96bbf851558bb81d0b669e8055b79765a` 又通过 PR CI `42943516659` 与完整 verify/closeout `42943570310`；所有结论均对应各自精确 SHA。
- 跨版本备份互通以 `v3.0.13-preview.3`（`77e291a150819a70d87c0af8458f1c603088e432`）为基线，在 Android 35 x86_64 模拟器完成旧版导出、旧版→新版导入、新版导出、新版→旧版导入；最终 closeout run `42943570310` 的四项各 `OK (1 test)`，三份数据文件哈希也通过。此项是模拟器验证，不是真机或 Root 环境验证。
- 正式 `Preview Build` run `42962314460` 从获批合并提交 `e7a6411e03c30ec4bcf4a8a00244362664d070a6`（合入 PR #23）生成并发布正式签名资产；发布后四件套、API digest、`SHA256SUMS`、ZIP 完整性和 APK 签名均已回读核验。
- 未做 Magisk / KernelSU / APatch 真机矩阵；模拟器、JVM 与静态检查不能替代 Root 真机签核。


---

## 历史归档：v3.0.13-preview.3 已发布说明

> 以下为 preview.3 当时发布说明摘要与历史验证记录，不是 preview.4 的功能或测试证据；preview.3 的 Release/tag 已在 preview.4 新资产和哈希核验后清理，稳定版 v3.0.11 保留。

# v3.0.13-preview.3 发行说明

preview.3 使用 `UpdateTransport` 经本地 mixed 代理检查和下载、校验更新包；任务原先在 `onStop()` 取消，已在 preview.4 中修正为离页继续。该版还收紧 Release tag 检查、最终 packaged Manifest 安全检查，并固定 GeoIP / GeoSite 来源和 SHA-256。

版本映射为模块码 `2023` / APK `10118`，正式线 `3.0.11 / 2017` 不变。该版历史记录称本地 JVM 381 项、工具 98 项、模块 11 项、诊断 3 项通过，并记录 CI `42786037533` 与文档 HEAD CI `42790934488`；此前签名瘦 APK + 普通模块 22.76 MiB 是旧源码数据，不可替代本轮实测。旧记录注明当时未做跨版本备份互通与 Root 真机矩阵。

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
- 预览名 `3.0.13-preview.1`，Root 模块码 `2021`，APK `versionCode=10106`；按既有 `metadataCode*5+previewSuffix` 计算，低于后续 preview.4 的 `2024/10124`。版本码规则只允许 `-preview.1` 到 `-preview.4`，所以本版从 3.0.13 预览线开始，而不是 3.0.12-preview.5。
- 包名与签名不变，可覆盖安装；备份格式不变。
- 管理器和 Root 模块必须同页同版成套更新，不能只更新其中一个。

## 自动化验证与边界

- 本地验证：更新器 JVM 测试 13 项、显示设置 JVM 测试 3 项全部通过，新增的 4 项生命周期测试在旧代码上确实失败；tools 下 Python 工具测试全部通过；Kotlin 版本码校验函数单独编译实测，能识别人为制造的规则偏差。
- CI 验证：以 PR #17 的 `Verify Android Build` 全量结果为准（Kotlin 编译、全部 JVM 单元测试、Release 变体、数据库迁移、Magisk 模块）。
- 未做 Magisk、KernelSU、APatch 真机矩阵或实体设备回归；自动化结果不等同于这些 Root 环境的实测。本次为预览版，不替换 v3.0.11 正式版。

---
