# 任务：App 全屏安全区与预测性返回

## 1. App Insets 与 Back dispatcher（单一实现批次）
- [x] 1.1 先增加公开 padding 纯函数 JVM 回归测试，以及 Manifest/源码接线契约测试
- [x] 1.2 实现幂等 padding helper；由非浮动 ThemedActivity 的 `android.R.id.content` 根容器统一保护 system bars/display cutout 四边安全区，`isDialog = true` 的浮动窗口跳过通用窗口配置与根 padding
- [x] 1.3 清理由根容器重复覆盖的页面级 List/Scroll Insets listener；保留 IME 专用避让，主页底部导航随根安全区布局
- [x] 1.4 为透明节点选择 Activity、非 Themed 数据库恢复页与 landing-IP BottomSheet 显式适配安全区；标准浮动告警弹窗及 ThemedActivity 浮动窗口保持系统/Material 窗口行为
- [x] 1.5 Application 显式允许 AndroidX Back Invoked 集成；将所有 Activity `onBackPressed()` 覆盖迁移为 `OnBackPressedDispatcher`，仅保留有业务语义的回调：Assets 页面走默认返回，节点优先级页的系统与 toolbar Home 返回均经 dispatcher 并保留结果
- [x] 1.6 更新 `theme-system`、`android-application` delta、变更说明，并完成适用的本地静态检查
- [x] 1.7 协调者完成独立差异复核；修复 Assets 默认返回、节点优先级页 toolbar 返回结果、BottomSheet 顶部 inset 与浮动 SwitchActivity 全屏 Insets 例外；复核未发现硬性阻塞

## 2. 批次 1 CI 与设备验证（独立复核后执行）
- [ ] 2.1 复核通过后运行仓库 `Verify Android Build` workflow 与 Kotlin 单测；回传 workflow/run 与失败日志摘要。按本轮要求检查并尝试可行的本地 Android 验证；不运行本地 Go 编译
- [ ] 2.2 真机/模拟器覆盖 Android 14（API 34）和 Android 15（API 35），各测手势导航与经典三键导航；核对首页、节点列表、配置编辑页、无 AppBar 页面、透明节点选择窗、QR/标准浮动弹窗和 landing-IP BottomSheet 的系统栏遮挡/重复留白
- [ ] 2.3 设备覆盖至少一个 display-cutout 横屏场景与 IME 打开状态；验证 dirty 配置 Back 弹确认、clean 配置默认回退动画、节点优先级返回结果不变；记录具体设备/API/导航模式和截图/录屏证据
- [ ] 2.4 仅在独立复核通过、CI 通过后再创建 App PR；本任务不合并、不发布，Root-module 与其他 UI 工作另行处理

## 本地验证记录

- 独立纯 Kotlin/JUnit JVM 路径：9 tests passed（InsetsPadding 计算 2 项、Manifest/源码接线 contract 7 项，含浮动对话框例外及透明节点选择窗接线）；未编译 Android 源码。
- Android SDK API 35/36 可用；临时链接到现有仓库中 SHA-256 相同的 `libcore.aar`（未纳入提交）后，`:app:testOssDebugUnitTest` 通过：739 tests，0 failures、0 errors、0 skipped。
- `:app:assembleOssDebug` 通过，生成 `wanBoxForAndroid-3.0.17-arm64-v8a-debug.apk`（13,948,900 bytes）；`git diff --check` 通过。
- `adb devices -l` 未发现已连接设备；OpenSpec CLI 不在本机，未运行 validate。仓库 `Verify Android Build` workflow 尚未运行，需推送并创建 PR 后检查。
- JVM 测试与 APK 构建不代表 Android 14/15 真机行为通过；手势/三键导航、刘海横屏、IME 与预测性返回仍须设备验证。
