# 📖 wanBoxForAndroid 官方技术文档中心

欢迎查阅 **wanBox for Android** 项目工程文档中心。本目录汇总了 wanBox 在架构设计、性能调优、版本管理、安全门禁及历史演进全生命周期的核心技术文档。

---

## 🧭 文档体系导航

```
docs/
├── 📌 1. 版本发布与管理规约 (Release & Versioning)
├── 🏗️ 2. 核心系统与网络路由架构 (Architecture & System Design)
├── 🎨 3. 界面交互与用户体验规范 (UI & User Experience)
├── 📊 4. 自动化验收与稳定性审计报告 (Reports & Quality Audits)
├── ⚙️ 5. 配置资产与体积基线 (Configuration & Build Baseline)
└── 📜 6. 历史里程碑与版本归档 (Historical Milestones & Archive)
```

---

## 📌 一、版本发布与管理规约

| 文档名称 | 主要内容与规约说明 | 状态 / 作用 |
|---|---|---|
| [**current-version.zh-CN.md**](./current-version.zh-CN.md) | **发布、源码与版本对照**：正式版与当前已发布预览的 Git 提交、版本码向量、四件套发布资产 SHA-256 镜像与核验记录。 | 核心维护 |
| [**release-checklist.zh-CN.md**](./release-checklist.zh-CN.md) | **分阶段发布检查清单**：不可动兼容性约束、Fail-Closed 本地代理策略、25 MiB 体积门禁与自动化实跑证据要求。 | 核心维护 |
| [**release-notes-next.zh-CN.md**](./release-notes-next.zh-CN.md) | **下一版发行说明草稿**：下一阶段功能演进范围、缺陷收敛规划及当前预览说明快照。 | 核心维护 |
| [**project-positioning.zh-CN.md**](./project-positioning.zh-CN.md) | **项目定位与工程原则**：明确独立分支维护路线、瘦管理器架构、Root 模块承载核心与免 VPN 运行原则。 | 基础规范 |

---

## 🏗️ 二、核心系统与网络路由架构

本板块沉淀了 wanBox 从单体进程向瘦模式演进过程中的核心 RFC 与底层设计文档。

### 1. 路由与出站分流
- [**b-route-design.md**](./b-route-design.md)：B 路由体系设计方案，深入分析内核多链路出站与规则判定。
- [**app-outbound-routing.zh-CN.md**](./app-outbound-routing.zh-CN.md)：应用级分流实现，基于 UID 与包名隔离的出站映射方案。
- [**route-rule-settings.zh-CN.md**](./route-rule-settings.zh-CN.md)：路由规则与偏好设置的数据存储与即时生效链路。
- [**input-method-route.zh-CN.md**](./input-method-route.zh-CN.md)：针对输入法等核心系统服务出站穿透与特例路由的处理方案。
- [**foreground-routing-fixes.zh-CN.md**](./foreground-routing-fixes.zh-CN.md)：前台应用聚焦与网络切换期间的路由平滑迁移修复。

### 2. 跨进程通信与 Root 运行时代次
- [**root-runtime-optimization.zh-CN.md**](./root-runtime-optimization.zh-CN.md)：Root 守护进程代次管理（Generation）、优雅启停与性能调优。
- [**settings-cross-process-invalidation-design.zh-CN.md**](./settings-cross-process-invalidation-design.zh-CN.md)：跨进程配置变更失效机制，利用文件锁与原子状态机消除竞态。
- [**runtime-efficiency-sync.zh-CN.md**](./runtime-efficiency-sync.zh-CN.md)：运行时代次同步开销分析与内存优化。
- [**iteration-modules.zh-CN.md**](./iteration-modules.zh-CN.md)：四模块迭代全景：原生 View、路由解耦与后台长任务性能保证。

### 3. 延迟探测与网络评测
- [**connected-latency.md**](./connected-latency.md)：长连接与活动链路真实延迟探测方案。
- [**arm64-background-rtt.zh-CN.md**](./arm64-background-rtt.zh-CN.md)：针对 ARM64 设备的后台 RTT 往返时延测量分析。
- [**urltest-lifecycle-fix.zh-CN.md**](./urltest-lifecycle-fix.zh-CN.md)：URLTest 节点测速生命周期泄漏修复与超时熔断控制。

---

## 🎨 三、界面交互与用户体验规范

- [**ui-redesign/**](./ui-redesign/)：**UI 重构专栏**，记录从传统界面向现代 Material 3 原生架构演进的全过程。
- [**liquid-glass.zh-CN.md**](./liquid-glass.zh-CN.md)：**Liquid Glass 视觉规范**，现代半透明磨砂质感卡片与排版系统。
- [**ui-consistency.zh-CN.md**](./ui-consistency.zh-CN.md)：原生控件行为一致性设计，避免自定义 View 带来的性能回退。
- [**compact-cards-and-icon-picker.zh-CN.md**](./compact-cards-and-icon-picker.zh-CN.md)：紧凑型节点卡片设计与图标选择器交互方案。
- [**performance-lifecycle-settings.zh-CN.md**](./performance-lifecycle-settings.zh-CN.md)：高频设置项的生命周期监听防抖与界面流畅度优化。

---

## 📊 四、自动化验收与稳定性审计报告

wanBox 严守「证据驱动」与「Fail-Closed」原则，所有关键改动均附带完整的测试与尺寸审计记录。

### 1. 版本验收报告
- [**reports/v3.0.13-preview.4-evolution.zh-CN.md**](./reports/v3.0.13-preview.4-evolution.zh-CN.md)：**preview.4 演进与验收总报告**（更新器前后台切换保活、瘦模式 Controller 延时探测、原子 QS Tile 与应用列表 LruCache 缓存实测）。
- [**reports/v3.0.13-preview.3-stability.zh-CN.md**](./reports/v3.0.13-preview.3-stability.zh-CN.md)：**preview.3 稳定性审查报告**（UpdateTransport 本地代理传输防直连回退、生命周期协程取消、打包 Manifest 安全校验）。
- [**preview3-validation.zh-CN.md**](./preview3-validation.zh-CN.md)：preview.3 阶段性自动化单测与静态分析数据。
- [**preview12-efficiency.zh-CN.md**](./preview12-efficiency.zh-CN.md) / [**preview13-routing-stability.zh-CN.md**](./preview13-routing-stability.zh-CN.md)：早期稳定性预览版专项实测记录。

### 2. 25 MiB 体积硬门禁与代码尺寸分析
- [**apk-size-report-20261028.md**](./apk-size-report-20261028.md)：内核符号裁剪依据（`.gopclntab` 切字节分析与真实 Deflate-9 压缩实测）。
- [**size-report-final.md**](./size-report-final.md)：双产物（瘦 APK + Root 模块 ZIP）严守 25 MiB（26,214,400 字节）绝对预算的核算报告。

### 3. 安全、备份与工具链审计
- [**audit-security-lifecycle.md**](./audit-security-lifecycle.md)：Android 最终 Manifest 导出组件白名单扫描与生命周期安全。
- [**portable-backup-and-batch-test.zh-CN.md**](./portable-backup-and-batch-test.zh-CN.md)：便携式备份跨版本双向导入导出互通测试方案。
- [**sing-box-1.15-assessment.zh-CN.md**](./sing-box-1.15-assessment.zh-CN.md)：上游 sing-box 1.15 新特性与 TUN 协议演进评估。
- [**upstream-network-review.zh-CN.md**](./upstream-network-review.zh-CN.md)：上游网络栈修复与 wanBox 集成点审查。
- [**target-sdk-36-assessment.zh-CN.md**](./target-sdk-36-assessment.zh-CN.md) / [**toolchain-followup.zh-CN.md**](./toolchain-followup.zh-CN.md)：Android Target SDK 与现代构建工具链演进跟踪。
- [**reference-stability-review.zh-CN.md**](./reference-stability-review.zh-CN.md) / [**alpha10-integration-audit.zh-CN.md**](./alpha10-integration-audit.zh-CN.md)：第三方内核引用稳定性专项审查。

---

## ⚙️ 五、配置资产与体积基线

- [**config/**](./config/)：包含 `local-proxy.json` 与 `tun-fakeip.json` 等经过 CI 自动验证的标准核心运行配置示例。
- [**rebuild/**](./rebuild/)：
  - [BASELINE-AUDIT.md](./rebuild/BASELINE-AUDIT.md)：代码库重建与旧版差异基线审计。
  - [DEDUP-FEASIBILITY.md](./rebuild/DEDUP-FEASIBILITY.md)：重复依赖去除与空间压缩可行性分析。
  - [FULL-REBUILD-DELTA.md](./rebuild/FULL-REBUILD-DELTA.md)：全量构建体积增量变化全景表。
  - `size-baseline.json`：CI 中自动化执行 `tools/size_budget.py` 的精确尺寸基准文件。

---

## 📜 六、历史里程碑与版本归档

> ⚠️ 以下文档保留作为工程演进的历史背景参考，不代表当前版本实现。

- [**APP_ID_MIGRATION.md**](./APP_ID_MIGRATION.md)：应用包名从历史旧包向 `com.lit008834.pixel.wanboxforandroid` 迁移记录。
- [**RELEASE_3.0.3.zh-CN.md**](./RELEASE_3.0.3.zh-CN.md)：v3.0.3 正式版历史发布说明。
- [**installer-data-recovery.zh-CN.md**](./installer-data-recovery.zh-CN.md)：旧版安装器数据恢复与迁移应急方案。
- [**selective-ownbox-2.9.9.zh-CN.md**](./selective-ownbox-2.9.9.zh-CN.md)：早期 OwnBox 2.9.9 差异化特性归档。
- [**preview3-version-evidence.json**](./preview3-version-evidence.json)：早期自动化测试保留的机器可读证据基线。
