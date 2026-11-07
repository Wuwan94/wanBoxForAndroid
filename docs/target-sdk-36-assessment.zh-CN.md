# preview.3 targetSdk 36 评估

评估日期：2026-11-08（Asia/Shanghai）。结论：本次保持 targetSdk 35，不擅自升级；compileSdk 36 只是编译 API，不表示已选择 target 36。

## 影响清单

| 类别 | Android 16 的变化与适用条件 | wanBox 影响及升级前验收 |
|---|---|---|
| Edge-to-edge | target 36 且运行 Android16 时不能再用 windowOptOutEdgeToEdgeEnforcement 退出；Android15 上该属性仍可生效 | 检查所有 Activity、对话框、工具页、IME与底栏的 inset/触摸范围，不能只测首页 |
| 大屏 | target36，smallest width ≥600dp 时默认忽略方向、可调整大小与宽高比约束 | 验证双栏/横竖屏、多窗口、折叠屏和状态恢复，现有手机截图不足以通过 |
| 周期执行 | target36 的 scheduleAtFixedRate 最多补跑一次错过执行 | 定向搜索 app 未发现直接调用；仍需检查依赖/统计刷新行为，不依赖补跑次数累计流量 |
| 后台任务 | Android16 的 JobScheduler 配额调整影响所有 target，包括 WorkManager/DownloadManager | 本项目使用 RemoteWorkManager 更新订阅/数据，保持任务可取消、可重试；真实后台与待机桶下验证。保持target35不能规避这一系统行为 |
| 前台服务/开机/通知 | 既有权限和启动资格仍需按系统版本检查；不能推断 target36 赋予豁免 | Manifest 有 systemExempted 前台服务与开机接收器；Root权限不等于Android前台服务资格，检查通知授权、后台启动、开机及重启恢复，管理器退出不应停止模块 |
| Intent/组件安全 | 平台安全收紧及既有导出/不可变PendingIntent边界要复核 | 验证磁贴、小组件、快捷方式、文件导入与显式系统安装器，不放宽组件、TLS或安装权限以通过测试 |

## 为什么暂不升级

本轮是收口预览，target升级会同时扩大UI、后台及组件验收范围；目前没有设备矩阵证据。现有 min31、target35、compile36、JDK17、BuildTools35.0.1 与工具链保持不变。Android16所有应用行为仍应测试，不能把维持target35说成无需适配。

后续若另行批准升级，应先生成单独变更并对 Android12、15、16、≥600dp/多窗口执行功能和视觉回归；后台更新、通知、开机、Root生命周期、覆盖安装、双向备份也应重跑。本文件不是升级授权，也没有修改设置键、默认值或数据库。

## 来源与验证边界

- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/16/behavior-changes-all
- 本仓库 `buildSrc/src/main/kotlin/Helpers.kt`、AndroidManifest.xml、ConfigBuilder.kt 与 RemoteWorkManager 使用点。

这是文档和源码评估，不是 Android16 实机或仪器测试，未做真机回归。
