# Android Application Specification — Delta: align-ui-with-module-diagnostics

## ADDED Requirements

### Requirement: 模块诊断必须呈现模块已上报的退出与事件信息

设置页的模块状态区域 MUST 呈现 Root 模块状态中已上报的诊断信息：健康状态、
最近一次退出的原因与退出码、退出时间、看门狗重试次数，以及最近的模块事件。
事件 MUST 有界呈现（最多最近 5 条，最新在前，含时间与事件码）。任一字段
在状态中缺失或为空时，对应条目 MUST 隐藏，MUST NOT 显示编造值或占位假值；
旧模块完全不上报诊断字段时，诊断区 MUST 整体不出现。诊断数据 MUST 来自
既有的模块状态通道，MUST NOT 为此新增模块协议或要求模块改动。

#### Scenario: 核心退出后可查看诊断

- **GIVEN** 模块状态含 lastExit、lastReason、lastExitAt、retryCount 与若干事件
- **WHEN** 用户打开设置页的模块状态区域
- **THEN** 可见退出原因、退出码、本地时间格式的退出时间、重试次数与最近事件

#### Scenario: 旧模块不上报时不显示假诊断

- **GIVEN** 模块状态不含任何诊断字段（旧版本模块）
- **WHEN** 用户打开设置页的模块状态区域
- **THEN** 诊断区不出现，既有状态行与版本码显示不受影响

#### Scenario: 事件过多时有界呈现

- **GIVEN** 模块状态含 8 条事件
- **WHEN** 用户查看诊断区
- **THEN** 只显示最近 5 条且最新一条在最前
