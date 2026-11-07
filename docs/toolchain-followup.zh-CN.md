# 工具链与可靠性跟进（已随 preview.2 发布）

本文保留 preview.2 发布前的实现与本地验证记录；相关变更已随 [v3.0.12-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.12-preview.2) 发布。下文的“正式预览需经 CI 重建”等措辞描述当时的验证边界，不代表 preview.2 仍未发布；当前发布状态见 [版本对照](current-version.zh-CN.md)。

## 工具链

- Gradle 8.13（固定发行包 SHA-256）、AGP 8.11.1、Kotlin 2.2.21、KSP 2.2.21-2.0.4。
- compileSdk 36；targetSdk 35、minSdk 31、JDK 17 与 Build Tools 35.0.1 保持不变。CI 显式安装编译平台，不依赖 runner 镜像预装项。
- 使用 Kotlin compilerOptions；Room runtime/compiler/ktx/testing 一并升级到 2.7.2，避免旧处理器生成代码与 Kotlin 2.2/KSP 不兼容。
- 选择 Kotlin 2.2.21 官方完整支持范围内的 AGP 8.11.1，不直接迁移 AGP 9 的内置 Kotlin 与新变体 API。保留 ubuntu-24.04 和 JDK 17，避免将构建工具更新与运行环境漂移混在一起。

## 可靠性

- 更新器只匹配普通模块完整名称 `wanbox-root-module-arm64-v8a.zip`，不再受 with-manager 资产排序影响；缺少普通模块则拒绝不完整发布包。
- 下载先写同目录唯一临时文件，成功后替换目标；异常清理临时文件和当前更新包，复用 HTTP 客户端；仍须通过 SHA256SUMS 后才提示安装，不静默安装。
- urltest 启动探测异步进行，核心启动不再等待网络；首次 TCP/UDP 连接等待首轮探测，保留 5 秒总预算，并响应连接取消与分组关闭。
- 节点选择的读写使用同一把读写锁，覆盖状态查询与后台测速并发。

按要求不做真机回归。本地自动化验证不等同于重新发布或线上 CI 完成。

## 自动化验证

- `:app:testOssDebugUnitTest`：325 项测试，失败/错误/跳过均为 0。`:core-domain:test` 为 NO-SOURCE，不计入测试数。
- `:app:assemblePreviewDebug`：APK 打包成功（仅验证 Android 工具链与依赖，不作为正式发布包）。
- `:app:compilePreviewDebugAndroidTestKotlin`：Preview 主程序与 instrumentation 测试源码编译成功；未在设备执行 instrumentation。
- Go：`./internal/...`、`./protocol/urltest`、`./protocol/loadbalance` 扩展测试通过；urltest/loadbalance 的 race 测试通过。
- libcore 根包使用 `with_clash_api,with_gvisor,with_quic,with_wireguard,with_utls` 标签编译及测试通过。
- Python 64 项测试、Android 安全脚本、Shell 语法及 diff 空白检查通过。
- Android 编译检查使用本地已有的 `app/libs/libcore.aar`；Go 修改已独立编译/测试，但这里不把该旧 AAR 当作新内核制品。正式预览需经原 CI 重建 AAR、签名和打包后发布。
