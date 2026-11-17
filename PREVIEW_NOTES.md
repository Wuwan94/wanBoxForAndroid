# v3.0.16-preview.4 发行说明（候选）

**状态：未发布的稳定性预览版候选。**

本版是 preview.3 的收口修复：消除策略组成员的「双真相」、修复订阅节点与组重名导致的连接失败，并让源码 tag 与发布二进制重新同源。正式稳定版 `v3.0.14`（`VERSION_CODE=2026`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 不变；预览元数据码升为 `PRE_VERSION_CODE=2030`，派生 APK `versionCode=10154`、Root 模块码 `2030`，均高于 preview.3 的 `10148` / `2029`，已安装 preview.3 的用户可同时收到 APK 与模块更新。

## ✦ 与上一版对比

| 项目 | v3.0.16-preview.3 | v3.0.16-preview.4 |
|---|---|---|
| 内容 | 四批次汇总：策略组浏览选择测速、模块诊断上屏、图形化组编辑、统一资源页与重名消歧、备份迁移规范化、网关缓存深拷贝 | 收口修复：组成员单一真相、订阅撞名不再断连、选中节点显式回落、恢复失败逃生口、设置与编辑器口径一致 |
| APK `versionCode` | 10148 | 10154 |
| Root 模块码 | 2029 | 2030 |
| 源码与二进制 | 资产由 main `000d302` 重建替换，tag 仍指向 `fa6b95b` | 由同一 main 提交出版，tag 与资产同源 |

URLTest 稳定性约束（检测间隔 ≥ 10 秒、空闲超时 ≥ 30 分钟、切换时不中断现有连接）、`default` 保真、候选应用的版本 CAS 与恢复校验链本版均未放宽。

## ✦ 本版更新

### 1. 策略组成员只有一个真相
节点归属（数据库中的所属分组）成为组成员的唯一依据，编辑器保存的组草稿只负责成员顺序与 URLTest 参数。保存编辑时，新加入的节点会在同一事务内移入该组，从普通分组移除的节点移到「未分组」；订阅组的成员由订阅决定，可调整顺序与参数，增删节点请在订阅或节点页操作。之后订阅更新新增的节点自动追加到有序成员末尾，被移走的节点自动退出。组列表显示的节点数、运行配置中的组成员与导出配置的组成员三者一致（导出不再静默丢弃组草稿）。

### 2. 订阅节点与组重名不再导致连接失败
构建配置时先为所有分组与组草稿预留名称，订阅新带来的同名节点自动加后缀（如「香港-1」），组与节点各自保留独立标签，连接不再因 `duplicate_tag` 失败。若仍出现重名，错误提示改为「组名与节点名称重复，请重命名冲突条目后重试」，不再是笼统的「操作失败」。

### 3. 选中节点被移出主组时显式回落
当前选中节点不在主组成员中时，Selector 显式回落到第一个成员，并在模块确认生效后同步界面选中项，避免界面显示 A 节点、流量却走首个成员。

### 4. 恢复失败时可放弃未完成编辑
若「恢复编辑前的组配置」因保存的基线失效无法完成，策略组页提供次级操作「放弃未完成编辑」：把本机组配置恢复到编辑前、清除恢复标记，并按当前配置重建 Root 模块（模块未运行时只清除标记），不会再被永久锁在待恢复状态。

### 5. 设置页与编辑器口径一致
组设置页不再回写已被构建忽略的「切换时中断现有连接」，统一锁定关闭；编辑器把该项改为明确的文字说明，并在已有组的类型锁定时提示「已有组的类型请在组设置页修改」。

### 6. 维护性清理
读取组草稿不再在快照事务内逐组查库；移除「兼容旧版」导出未使用的拒绝提示字符串。「兼容旧版」导出仍保持禁用，现有备份导出与旧格式导入不受影响。

## 📦 版本、资产与安装

- **运行要求**：Android 12+（API 31+）、arm64-v8a、Magisk / KernelSU / APatch 等 Root 环境。
- **版本对齐**：APK `versionName=3.0.16-preview.4` / `versionCode=10154`；Root 模块码 `2030`。瘦管理器和 Root 模块必须来自同一预览 Release、同版成套使用。
- **同版资产**：`wanBoxForAndroid-3.0.16-preview.4-arm64-v8a-release.apk`、`wanbox-root-module-arm64-v8a.zip`、`wanbox-root-module-with-manager-arm64-v8a.zip`、`SHA256SUMS`。
- **回退**：使用正式稳定版 [v3.0.14](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.14) 的 APK 与配套 Root 模块（模块码 `2026`）；不要混用不同 Release 的 APK 和模块。

## 🛡️ 验证边界

源码基于 main `000d3023571153d975d2868eeb7083da57b1a9e4`（PR #61）。本地（aarch64 沙盒，JDK 17，build-tools 原生工具经 qemu 转译）已通过：`testPreviewDebugUnitTest` 与 `testOssDebugUnitTest` 各 541 项 JVM 单元测试（较 preview.3 的 528 项新增 13 项）；AndroidTest Kotlin 编译；Python 工具 124 项、`version_code.py --check-kotlin --check-app-tag`、版本文档一致性检查与 `git diff --check`。新增 JVM 用例覆盖组成员归属与订阅更新/跨组移动后的成员一致、撞名后缀避让、`duplicate_tag` 提示映射、选中节点回落与放弃编辑的执行顺序；新增的 `GroupEditorPersistenceTest.countRuntimeAndExportShareOneMembershipTruth` 等 instrumentation 用例只在本地编译，执行结果以 Verify Android Build CI 为准。本地 `assemblePreviewDebug` 可出 APK；以 R8 压缩、调试签名的 `assemblePreviewBenchmark` APK（4881949 字节）加 preview.3 同版 Root 模块 ZIP 试算 `size_budget --enforce-limit` 合计 21.78 MiB，低于 26 MiB，仅作体积参考，正式发行双产物体积以 Preview Build 为准。发布证据（PR、合并提交、Preview Build 与 verify-android run 号）在发布后写入版本对照，不以本地结果替代。未执行 Magisk / KernelSU / APatch 真机矩阵：组编辑应用链、订阅更新后的组成员与放弃编辑的真机表现仍未验证；自动化结果不等同于真实 Root 设备验证。

---

## 历史归档

### v3.0.16-preview.3 已发布说明

[v3.0.16-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.16-preview.3) 已作为稳定性预览版发布（tag `fa6b95b`，首发 `Preview Build` run `43906481811`；资产于 2026-11-17 由 main `000d302` 的候选 run `43939217515` 经受审查流程替换）。内容为四批次汇总：图形化策略组编辑、策略组浏览/选择/测速、统一资源页与重名消歧、备份与迁移规范化（「兼容旧版」导出未开放：尚无经真实旧版读取器验证的写入器，入口禁用并明确说明）、模块退出诊断上屏、网关缓存深拷贝；APK `10148` / 模块 `2029`。未执行 Root 真机矩阵。资产快照见[版本对照](docs/current-version.zh-CN.md)。

### 更早预览

v3.0.14-preview.1、v3.0.15-preview.1–.3、v3.0.15-preview.4 与 v3.0.16-preview.1、v3.0.16-preview.2 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。
