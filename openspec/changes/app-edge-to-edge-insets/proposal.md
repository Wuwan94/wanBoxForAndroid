# 提案：App 全屏安全区与预测性返回适配

## 背景
项目 `targetSdk` 为 35，Android 15 会让非浮动窗口默认延伸到系统栏与显示缺口下方。现有 `ThemedActivity` 已启用 edge-to-edge，但主要只给顶部 AppBar 应用状态栏 Insets；底部/侧边安全区分散在页面监听器中，部分监听器覆盖原始 padding，配置列表还保存了 bottom inset 却未实际应用。少数可见 Activity、透明节点选择窗口和自定义 BottomSheet 也不共享一致的系统栏处理。

## 目标
- 为所有普通 App Activity 建立同一套根内容 WindowInsets 安全区策略，保留布局原有 padding，并避免重复派发时累加。
- 顶部 AppBar、没有 AppBar 的页面、输入法、刘海/横屏缺口、手势导航与经典三键导航均不遮挡交互内容。
- 对自定义透明节点选择 Activity 与自定义底部弹层显式适配；标准浮动告警弹窗继续使用系统/Material 的窗口安全边界。
- 显式启用 AndroidX/平台 Back 集成，并将旧式 Activity `onBackPressed` 拦截迁移到 AndroidX dispatcher，保留未保存修改确认及节点优先级结果语义。

## 影响与非目标
- 影响规范：`openspec/specs/theme-system`、`openspec/specs/android-application`。
- 仅修改 `app/` 的窗口 Insets、相关 Activity 返回处理、回归测试和 OpenSpec 文档；不涉及 `rootmodule/`、libcore、协议/业务状态、统一视觉重设计、其他界面功能请求或 `nb4a.properties`。
- 本批只准备 App PR。独立复核前不提交、不推送、不创建 PR；不合并、不发布。
