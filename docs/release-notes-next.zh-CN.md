# v3.0.12-preview.3 发行说明

收口验证预览，不替换 v3.0.11 正式版。未做真机回归。

## 更新内容

- URLTest 补齐五秒总预算、全员不可用、取消和关闭回归；取消不污染历史，关闭后不重启探测，原选路同锁约束保持。
- 更新器采用独立尝试目录、同目录唯一临时文件和原子提交；APK及模块同批通过 SHA256SUMS 才放行。断网、存储错误、校验失败、缺失资产均清理本次尝试，保留旧完整文件，提供中英错误提示。不静默安装，普通及with-manager模块完整文件名匹配。
- 超过1000节点列表后台 DiffUtil 与重载/搜索交错保护，移除已定位的绑定/布局同步设置读取；六个线性图标和工具页分组收尾。设置只提交跨进程失效设计，没有增加通用缓存。
- 内核与targetSdk评估已记录：2026-11-08核对，上游最新1.15预览仍alpha.13，fork仍 `v1.15.0-alpha.13-reF1nd`（17397d0）；本次沿用，不默认迁移新TUN栈、不擅自升级target。
- 保持Gradle8.13 / AGP8.11.1 / Kotlin2.2.21 / KSP2.2.21-2.0.4 / compileSdk36 / Room2.7.2；target35、min31、JDK17、BuildTools35.0.1不变。测速默认8秒、1–60秒设置不变。

## 安装与版本

- Android12+、arm64-v8a、Root（Magisk / KernelSU / APatch），瘦管理器不含内核，无普通VPN回退。
- 预览名 `3.0.12-preview.3`，Root模块码 `2019`，APK `versionCode=10098`；按既有 `metadataCode*5+previewSuffix` 计算，高于preview.2的2018/10092。
- 发布候选须提供瘦APK、普通Root模块、with-manager模块及SHA256SUMS，管理器和模块必须同页同版成套，不能只更新其中一个。

## 自动化验证与边界

- 本地构建与验证：arm64 原生核心构建成功；PreviewDebug Kotlin/AndroidTest 编译与 APK 构建通过，338 项 JVM 测试、67 项 Python 测试通过；Android 安全检查、版本码检查、APK ABI 校验及 `git diff --check` 通过。
- 本地没有发行签名密钥，故未声称本机签名 Release 构建通过。CI run `42398036152` 使用仓库正式签名完成 Release 变体构建与签名验证，并通过数据库迁移验证及原生全 ABI 检查。
- 同一 CI run 在 API 35 x86_64 模拟器真实采集 `baseline-prof.txt` 并运行冷启动基准；旧版→新版及新版→旧版的 `BackupCrossVersionTest` 均通过。该模拟器不具备 Root 设备环境。
- 未做 Magisk、KernelSU、APatch 真机矩阵或实体设备回归；模拟器与自动化结果不等同于这些 Root 环境的实测。本次为预览版，不替换 v3.0.11 正式版。

---
