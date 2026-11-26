# Theme System Specification — Delta: app-edge-to-edge-insets

## MODIFIED Requirements

### Requirement: 系统栏保持沉浸式和可读性

Android 8.0 及以上的状态栏与导航栏 MUST 使用透明或系统提供的兼容性 scrim，并由 Activity 启用 edge-to-edge。导航栏图标 MUST 与当前主题和导航模式保持可读；状态栏图标 MUST 根据主题和夜间状态维持足够对比度，黑色主题的特殊规则 MUST 被保留。每个非浮动 Activity MUST 使用 system bars 与 display cutout Insets 保护所有可交互内容：顶部 AppBar 应用顶部安全区，其他内容不应被手势条、经典三键导航栏或横屏显示缺口覆盖。Insets padding MUST 基于视图原始 padding 幂等计算，重复分发 MUST NOT 累计 padding 或丢失布局预设间距；IME 专用避让 MUST 与系统栏安全区正确组合。

浮动 Alert/Material Dialog MUST 保留系统或 Material 窗口安全边界；自定义透明全窗口 Activity/Dialog 与 edge-to-edge BottomSheet MUST 显式为其交互内容应用所需的 system bars/display cutout Insets，并保留原始布局间距。
被 `ThemedActivity.isDialog` 标记为浮动窗口的 Activity MUST 跳过通用 edge-to-edge 窗口配置及 Activity 根内容安全区 padding；显式全屏的自定义窗口仍须各自配置 Insets。

#### Scenario: 普通页面适配手势导航和经典三键导航

- **GIVEN** App Activity 在 Android 15 / target 35 或较早版本以 edge-to-edge 展示
- **WHEN** 系统分别采用手势导航或经典三键导航并分发 Insets
- **THEN** AppBar 位于状态栏下可操作，页面底部交互项位于导航安全区之上
- **AND** 初始 padding 保持不变，重复 Insets 分发不会逐次增加空白

#### Scenario: 刘海屏与横屏显示缺口

- **GIVEN** Activity 在有 display cutout 的设备上横屏展示
- **WHEN** system bars 与 display cutout Insets 到达
- **THEN** 可交互控件避开受遮挡的侧边区域，背景仍可绘制到边缘

#### Scenario: 自定义 BottomSheet 的底部操作可达

- **GIVEN** 用户在 Android 15 或更早版本打开含底部按钮的 landing-IP BottomSheet
- **WHEN** 设备使用手势条或经典三键导航
- **THEN** BottomSheet 内容保留原有间距并应用必要的底部/侧边 Insets
- **AND** 重新分发 Insets 不会累积 padding

#### Scenario: 浮动弹窗与 IME 保持窗口行为

- **GIVEN** 用户打开标准浮动告警弹窗或在配置编辑器调出软键盘
- **WHEN** 窗口 Insets 更新
- **THEN** 浮动弹窗继续由系统/Material 安全布局，编辑器同时避让键盘与底部导航区

#### Scenario: ThemedActivity 浮动窗口保留原始边界

- **GIVEN** `SwitchActivity` 通过 `isDialog = true` 使用浮动 `Theme.SagerNet.Dialog`
- **WHEN** `ThemedActivity` 完成窗口与内容初始化
- **THEN** Activity 不调用通用 edge-to-edge 窗口配置，也不为内容根添加安全区 padding
- **AND** 透明全屏 `NodeSelectDialogActivity` 仍显式配置窗口并为交互根内容应用 Insets
