# v3.0.12-preview.1 发行说明

本说明对应预览版 `v3.0.12-preview.1`，不替换 `v3.0.11` 正式版历史。

## 更新内容

- **内核**：保持 `reF1nd/sing-box v1.15.0-alpha.13-reF1nd`（commit `17397d022647b73378705e1f91ff031dddaaec21`）。这是当前最新的 1.15 预览，上游没有 alpha.14；`libcore/go.mod` 的 sing / sing-tun / sing-quic / sing-vmess / quic-go 与该 tag 的 go.mod 逐项一致，Go 仍为 1.25.5。本版不提供稳定版内核选项，也不保留 1.14.x 回退。
- **自动选择启动先测速**：URLTest 组启动时先完成一轮受限测速（上限 5s），首次选路依据真实延迟跳过不可用成员，不再先连接列表第一个节点。全部成员都不可用时仍保留原有回退，避免完全不可用。
- **延迟测试超时统一**：节点测速不再硬编码 5000ms；单节点测速与批量测速共用高级设置里的「测速超时时间」（`speedTestTimeoutMs`，默认 8000ms，钳制 1–60s）。
- **应用内检查更新**：只读本仓库 GitHub Releases，只接受同一 Release 内的管理器 APK、Root 模块与 `SHA256SUMS` 三件套。对话框明确提示两者必须从同一发布页同版更新；选择「下载并校验」后逐文件校验 SHA256，校验通过才打开系统安装器，模块 ZIP 交由用户在 Magisk / KernelSU / APatch 中安装。不会静默安装，资产不全时引导前往发布页。

## 安装与兼容

- Android 12+（API 31+）、arm64-v8a、Root（Magisk / KernelSU / APatch）。
- 瘦管理器 APK 不含核心，**必须与同一发布页的 Root 模块配套使用，两者版本必须一致**；不能只更新其中一个。
- 升级前请先导出备份。本版**未经真机验证**，不承诺覆盖安装后的数据保留、TUN 重启、节点切换或模块状态读取；如遇异常请回滚到上一版并恢复备份。
- 下载后请用同一发布页的 `SHA256SUMS` 核对 APK 与模块 ZIP。

## 工具链

- Gradle 8.10.2、AGP 8.8.1、Kotlin 2.0.21、compileSdk / targetSdk 35、minSdk 31 **保持不变**。
- Gradle 9.9.0 + AGP 9.5.1 + Kotlin 2.2.21 + SDK 36 的升级尝试因旧 `applicationVariants` 输出命名与 AGP 9 内置 Kotlin 迁移未完成而**整体回退**，未混入本版；`runs-on` 仍为 `ubuntu-24.04`。

## 版本与资产

- 版本名 `3.0.12-preview.1`，元数据码 `2017`；预览 APK `versionCode=10086`，Root 模块 `versionCode=2017`，均高于 `v3.0.11-preview.3` 的 `10083/2016`。
- 发布资产：瘦管理器 APK、普通 Root 模块 ZIP、内置管理器的 Root 模块 ZIP（with-manager）、`SHA256SUMS`。具体 SHA256 以发布页清单为准。

## 验证边界

- 本地已完成：Kotlin 单测（`NodeTestRunnerTest`、`UpdateArtifactsTest`）、`app:compileOssDebugKotlin`、`libcore` 的 `protocol/urltest` 与 `protocol/loadbalance` Go 测试。
- CI 结果、APK 签名与 ABI、模块内核版本以发布页与 Actions 记录为准。
- **未完成真机运行时回归**：没有 Android 12+ Root 设备验证，模拟器/编译结果不代表真机通过。
