# 设计：App 全屏安全区与预测性返回

## 窗口 Insets 边界
普通 `ThemedActivity` 继续 edge-to-edge 绘制。`android.R.id.content` 下的 Activity 内容根统一负责 system bars 与 display cutout 的四边安全区；Insets 加在内容子根上而非 decor 内容承载容器，使根背景仍可绘制到系统栏边缘。顶部 AppBar 在根安全区内布局，不再另行叠加顶部 inset。所有 inset-padding 必须以视图初始 padding 为基线，重复分发只重算、不累加。这样列表、主页底部导航及页面底部操作可以使用同一个父级可视安全区，不再在多层子 RecyclerView/ScrollView 重复补偿。
`ThemedActivity.isDialog = true` 是上述通用策略的明确例外：例如使用浮动 `Theme.SagerNet.Dialog` 的 `SwitchActivity` 不调用 `WindowInsetsPolicy.configureWindow`，也不为内容根添加安全区 padding，以保留浮动窗口的系统/主题边界。

现有 `ListListener` 或内联 listener 重复处理 system bars 的页面改用 Activity 根容器策略；输入法编辑页保留 IME 专用 margin 计算，父级只负责系统栏安全边距。`ToolbarFragment` 不再单独挂载顶部 inset listener，避免 Activity 根容器与 Fragment 重复计算。

标准 Material/Alert Dialog 是浮动窗口，沿用系统/组件窗口布局，不强制切换到全屏 edge-to-edge。`NodeSelectDialogActivity` 是透明全窗口 Activity，根背景铺满，卡片内容通过 Insets padding 留在安全区。非 `ThemedActivity` 的 `DatabaseRecoveryActivity` 显式配置窗口并保护根内容。`LandingIpBottomSheet` 为非浮动底部窗口，内容根应用左右/底部 system bars 与 display cutout Insets（`includeTop = false`），且保留 XML 中原有间距。IME 与 BottomSheet 的行为以真实设备验证为准。

## 系统栏与返回
普通 Activity 继续使用 `WindowCompat.setDecorFitsSystemWindows(window, false)` 与主题计算所得的浅/深系统栏图标；状态栏和导航栏透明/系统对比保护遵循 Android 15 目标 SDK 行为。Application manifest 显式设置 `android:enableOnBackInvokedCallback="true"`。Activity 返回操作使用 AndroidX `OnBackPressedDispatcher`/`OnBackPressedCallback`；dirty 状态确认回调仅在存在未保存改动时启用，清洁状态可落回默认系统导航。`AssetsActivity` 不注册无业务逻辑的返回 callback，工具栏 Home 通过 dispatcher 使用默认返回路径；`BalancerPriorityActivity` 的系统返回与工具栏 Home 均通过 dispatcher 调用保留结果语义的 callback。

不实现跨页面自定义 swipe-progress 动画：存在启用的业务返回回调时，系统预测动画可能不会显示，但回退提交仍通过受支持的 dispatcher 路径处理。无自定义拦截的默认返回路径使用系统默认动画。

## 验证 seam
- 纯函数 `InsetsPadding + safe Insets -> resolved InsetsPadding`：验证原有四边 padding 被保留、左右/底部安全区正确叠加且重复分发不累加。
- 轻量源码/manifest contract：验证 manifest Back 开关、普通 ThemedActivity 根内容 Insets 接线、浮动 `SwitchActivity` 跳过通用窗口/根策略、透明全屏 `NodeSelectDialogActivity` 显式 Insets 接线，以及 Activity 侧 AndroidX `OnBackPressedCallback` 接线；不写 UI 像素断言。
- 这两层只证明 padding 计算与源码接线，不代替 Android 14/15 真机的手势/三键导航、刘海横屏、IME 与预测性返回实测。
- 独立纯 Kotlin/JUnit JVM 路径的 9 项 padding 与源码/manifest contract 测试通过；未编译 Android 源码。Android SDK API 35/36 可用，但 `:app:testOssDebugUnitTest --tests io.nekohasekai.sagernet.EdgeToEdgeWiringContractTest` 在 `:app:compileOssDebugAidl` 因缺少 `app/libs/libcore.aar` 失败，Gradle 测试未启动；`adb devices` 无已连接设备，OpenSpec CLI 不可用。Android 构建仍须由仓库 CI 验证，纯 JVM 测试与静态检查结果不代表 Android 真机行为。

## 官方依据
- [Android Views：Edge-to-edge 与 WindowInsets](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
- [Android 15 行为变化：Window Insets](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Android：Predictive Back 手势支持](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)
- [Android 14：Predictive Back 动画](https://developer.android.com/about/versions/14/features/predictive-back)
