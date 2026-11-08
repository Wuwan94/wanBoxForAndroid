# v3.0.12-preview.4 发行说明

复审修复预览，不替换 v3.0.11 正式版。未做真机回归。

## 更新内容

本版落实 v3.0.11 → v3.0.12-preview.3 代码复审中确认的六处问题，均为稳定性与一致性修复，无功能增减：

- URLTest 五秒总预算收口到唯一探测入口。此前只有启动首轮受总预算约束，手动测速与后续复检理论上可长时间挂起；现每一轮探测都在 `urlTest()` 内统一施加同一 5 秒截止时间。
- 并发探测共享在途结果。此前探测进行中再次触发会返回空结果冒充成功；现后到者等待并共享首轮的真实结果与错误，不再伪造成功。
- 更新器回收旧尝试目录。此前每次更新的 attempt 目录永不回收、反复更新持续占空间；现新尝试开始时清理已结束的历史尝试目录，正在进行与最新成功的保留，并新增 3 项 JVM 回归测试覆盖回收边界。
- 配置页最后两处同步设置读取改走列表偏好快照（地址显示、标签页触感反馈），消除主线程同步读 DataStore 的卡顿风险，与此前已迁移的读取方式一致；未新增通用缓存。
- 版本码规则加漂移检查。`tools/version_code.py` 为唯一事实来源，新增 `--check-kotlin` 校验 buildSrc 中的 Kotlin 实现（预览后缀正则与乘数）与之保持一致，不一致时直接报错；该检查随 CI 的 Python 工具测试一同运行。
- 预览清理工具去掉「只删指定一个旧标签」的 `--only-tag` 模式。发布后只保留最新预览版；主线已先行移除工作流里写死的单标签调用，本次再从工具本身移除该选项，双侧断言均有测试覆盖。

工具链与内核不变：Gradle 8.13 / AGP 8.11.1 / Kotlin 2.2.21 / KSP 2.2.21-2.0.4 / compileSdk 36 / Room 2.7.2；targetSdk 35、minSdk 31、JDK 17、Build Tools 35.0.1；内核固定 `reF1nd/sing-box v1.15.0-alpha.13-reF1nd`。

## 安装与版本

- Android 12+、arm64-v8a、Root（Magisk / KernelSU / APatch），瘦管理器不含内核，无普通 VPN 回退。
- 预览名 `3.0.12-preview.4`，Root 模块码 `2020`，APK `versionCode=10104`；按既有 `metadataCode*5+previewSuffix` 计算，高于 preview.3 的 2019/10098。
- 发布候选须提供瘦 APK、普通 Root 模块、with-manager 模块及 SHA256SUMS，管理器和模块必须同页同版成套，不能只更新其中一个。

## 自动化验证与边界

- 本地验证：原生核心（libcore.aar）构建成功；Go 测试中本次涉及的 urltest、loadbalance 包全部通过（含 `-race` 竞态检测）；Python 工具测试 72 项通过（含新增的版本码漂移检查与 prune 测试）。本地另有一项 `internal/appowner` 的 socket 归属测试因本机容器 /proc 环境限制失败，该包与本次改动无交集、代码与 preview.3 一致，以 CI 结果为准。
- 本机 Gradle 守护进程环境异常，Kotlin/JVM 单元测试与 APK 构建未在本机完成；发布门槛为 CI `Verify Android Build` 对候选分支的完整运行通过，未通过则不发布。最终核验结果将在发布前回填本节。
- 未做 Magisk、KernelSU、APatch 真机矩阵或实体设备回归；自动化结果不等同于这些 Root 环境的实测。本次为预览版，不替换 v3.0.11 正式版。

---
