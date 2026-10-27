# v3.0.8 浅灰简洁 UI

基于 `v3.0.8`（`eb6300d`），按用户提供的四页参考图重做 Android 原生界面。

- 仪表盘：白色页面、浅灰连接卡、规则/全局/直连分段选择、四张数据卡。
- 节点：订阅筛选、按订阅分组标题、简洁节点卡和圆形选中标记；搜索、TCP 测试和显示面板。列数、字号、间距、密度实时生效并持久化。
- 订阅：常驻勾选框、全部更新和更新选中按钮、节点数量与相对更新时间；自动更新入口打开对应订阅的设置，不覆盖原来的每订阅周期。
- 设置：分应用代理、Root、DNS、路由、网络策略、外观、日志、备份、关于九个入口。高级设置、工具及文档仍可从右上角菜单打开。二级页面可以返回设置首页。
- 导航：四个入口统一细线图标，无阴影；页面根据实际导航栏高度留出空间。

数据库、订阅解析、节点协议和 Root 模块未更改。演示节点只存在于 `MinimalUiTest` 测试夹具，安装包不包含演示数据。

## 数据显示的边界

v3.0.8 的 Root 状态协议没有提供核心内存和连接数，因此这两张卡显示 `—`。上传、下载及累计流量使用实际服务回调；未连接时不显示旧采样。运行时长是当前 App 观察到连接以来的时长，切换页面不会重新计时；重新打开 App 时会重新开始观察。

## 本地构建

需要完整 JDK 17 或以上、Android SDK 35、Build Tools 35.0.1、NDK 25.0.8775105 和 Go 工具链（由仓库 go.mod 指定）。

```bash
# 按原仓库流程生成 app/libs/libcore.aar。
bash buildScript/lib/core/init.sh
bash buildScript/lib/core/build.sh

# local.properties 中配置 sdk.dir 后构建 ARM64 调试包。
./gradlew :app:assembleOssDebug

# JVM 回归测试。
./gradlew :app:testOssDebugUnitTest
```

正式发布仍需自己的签名密钥，遵循原仓库 `WANBOX_KEYSTORE_FILE` / `WANBOX_KEYSTORE_PASSWORD` 设置。交付的 Debug APK 使用调试包名，可以与正式版并存；Root 连接需原项目的配套模块与真实设备。

## 界面验证

`MinimalUiTest` 在干净的 x86_64 模拟器上创建公共测试夹具，检查四栏导航、订阅批量选择、双列/三列切换、DNS 二级入口和返回，并生成四张原生截图。测试结束删除夹具。

`NodeSectionLayoutTest` 验证单、双、三列下奇数节点分组不会混入同一行，以及筛选和空列表后分组标题正确重建。

## 本次交付验证

- ARM64 OSS Debug APK 编译成功，最低 Android 12（API 31）。
- 281 项 JVM 测试通过，失败、错误和跳过均为 0。
- x86_64 App 和 Android 仪器测试包编译成功。当前构建环境没有 `/dev/kvm`，软件模拟器在 App 安装前的系统首次启动期间发生 SystemServer 看门狗重启，未完成界面运行测试，因此没有宣称仪器测试通过或附上模拟运行截图。
- Root 连接和真实网络流量仍需在带配套 Root 模块的设备上验收。

源码压缩包不包含生成的 `libcore.aar`、原生可执行文件、签名密钥和本地 SDK。构建时先执行上面的原仓库核心构建步骤。补丁可在干净的 `v3.0.8` 检出中使用 `git apply wanBoxForAndroid-v3.0.8-minimal.patch` 应用。
