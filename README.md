# wanBoxForAndroid

<p align="center">
  <img src="docs/logo.png" width="128" height="128" alt="wanBoxForAndroid Logo">
  <br>
  <b>面向已 Root 设备的独立 Android 代理管理器与系统级核心套件</b>
  <br>
  <b>An independent, root-focused Android proxy suite & thin manager</b>
</p>

<p align="center">
  <a href="https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.11"><img src="https://img.shields.io/badge/Stable-3.0.11-green.svg?style=flat-square" alt="Stable 3.0.11"></a>
  <a href="https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.4"><img src="https://img.shields.io/badge/Preview-v3.0.13--preview.4-orange.svg?style=flat-square" alt="Published preview v3.0.13-preview.4"></a>
  <a href="https://android-arsenal.com/api?level=31"><img src="https://img.shields.io/badge/Android-12%2B%20(API%2031%2B)-brightgreen.svg?style=flat-square" alt="Minimum Android version"></a>
  <a href="https://github.com/Wuwan94/wanBoxForAndroid/releases"><img src="https://img.shields.io/badge/ABI-arm64--v8a-informational.svg?style=flat-square" alt="Supported ABI"></a>
  <a href="https://www.gnu.org/licenses/gpl-3.0"><img src="https://img.shields.io/badge/License-GPL--3.0-orange.svg?style=flat-square" alt="License"></a>
  <a href="https://t.me/OwnBoxs"><img src="https://img.shields.io/badge/Telegram-@OwnBoxs-2CA5E0.svg?logo=telegram&style=flat-square" alt="Telegram"></a>
</p>

· If this project helps you, joining our TG channel is the best support you can give me.

---

## 📌 当前版本核对 / Release and source snapshot

当前已发布正式版为 [v3.0.11](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.11)，当前唯一已发布预览版为 [v3.0.13-preview.4](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.4)，tag 指向 `e7a6411e03c30ec4bcf4a8a00244362664d070a6`，不替换正式版；源码元数据与发布资产的边界见 [版本对照](docs/current-version.zh-CN.md)。

preview.4 的 Root 模块码为 `2024`、APK `versionCode=10124`。更新器生命周期、瘦模式节点延迟探测、QS Tile 过渡态保护和分应用列表异步加载及 LruCache 优化等改进已由 PR #21 与 PR #23 合并；正式预览资产由仓库 `Preview Build` 流程生成并验签，发布后 SHA256 与资产完整性已核对。CI、跨版本模拟器互通证据与边界见[验收报告](docs/reports/v3.0.13-preview.4-evolution.zh-CN.md)。工具链为 Gradle 8.13 / AGP 8.11.1 / Kotlin 2.2.21 / KSP 2.2.21-2.0.4 / compileSdk 36 / Room 2.7.2；targetSdk 35、minSdk 31、JDK 17、Build Tools 35.0.1 不变。内核固定为 `reF1nd/sing-box v1.15.0-alpha.13-reF1nd`，不据此宣称它是当前上游最新版本。

preview.3（模块码 `2023` / APK `10118`）曾作为上一预览发布，已在 preview.4 完整核验后按渠道偏好清理；稳定版 v3.0.11 保持不变。当前说明见 [PREVIEW_NOTES.md](PREVIEW_NOTES.md)，资产哈希见[版本对照](docs/current-version.zh-CN.md)。**未做 Magisk、KernelSU、APatch 真机回归**，自动化结果不等于 Root 真机签核。

**安装要求：Android 12+、arm64-v8a、Root（Magisk / KernelSU / APatch）**。瘦管理器不包含核心，必须搭配同一发布页中的 Root 模块，不能混用其他版本；安装前先备份并检查 `SHA256SUMS`。更新必须校验后由用户确认安装，不静默安装，不提供 VPN 回退。

预览渠道仅保留最新一个版本；清理旧预览前先核验新版本的完整发布资产，正式版始终保留。[v3.0.11 正式版历史说明](RELEASE_NOTES.md)保持原文。

Published releases: stable v3.0.11 and preview v3.0.13-preview.4 (tag commit e7a6411e03c30ec4bcf4a8a00244362664d070a6). The official Preview Build assets and SHA256SUMS were verified; the replaced preview.3 was removed after publication, while stable v3.0.11 remains. No Root-device regression sign-off has been completed. Requires Android 12+, ARM64 and Root; pair the thin manager APK with the Root module from the same release page.

---

## 🌟 核心特色 / Key Features

- 🪶 **极致瘦身管理器（Thin Manager）**：管理器 APK 仅约 6.1 MiB，剥离核心二进制（零 JNI、无 `libgojni.so` 内存常驻），提供丝滑的 Material 3 原生交互与毫秒级冷启动。
- ⚡ **原生 Root 模块承载**：由 Magisk / KernelSU / APatch 模块持有核心进程与 TUN 系统路由生命周期，崩溃自动守护，无普通 VPN 接口开销与易被系统查杀问题。
- 🔒 **Fail-Closed 严苛安全边界**：更新检查与瘦模式延迟探测均严格绑定本地回环端口（`127.0.0.1`）与 mixed 代理，代理不可达时明确报错阻断，坚决不回退直连，不泄露真实 IP。
- 🎛️ **原子化 QS Tile 磁贴同步**：通知栏快捷磁贴引入防抖状态机，自动抑制过渡态，切换前绕过缓存强读物理真值，并发调用自动加锁保护。
- 🏎️ **流式分应用代理与 LruCache 缓存**：应用列表包名与标签在后台协程异步解析，搭配 120 项原生内存 `LruCache`，快速滑动零掉帧、零白块闪烁，杜绝重复 IPC。
- 🛡️ **严格的 25 MiB 总下载预算**：APK 与配套模块在构建期严守体积硬门禁，所有改动均经过本地与 CI 完整证据链检验。

---

## 📥 发行版下载 / Downloads

👉 **[前往 GitHub Releases 官方下载页面](https://github.com/Wuwan94/wanBoxForAndroid/releases)**

从 **v3.0.7-preview.6** 起，本仓库新构建只发布 **arm64-v8a** 架构，运行环境要求 **Android 12+** 及 **已 Root 设备**。

> ⚠️ **成套同版配对原则**：瘦管理器 APK 内部不含核心，**必须与同一发布页中的 Root 模块 ZIP 同步更新并搭配使用**，严禁跨版本混用。

---

## 🚀 快速上手 / Quick Start

1. **下载附件**：从最新 Release 页面下载成套的 `wanbox-root-module-with-manager-arm64-v8a.zip`（或单独下载 Root 模块与 APK），并核对 `SHA256SUMS`。
2. **刷入模块**：在 Magisk、KernelSU 或 APatch 管理器中选择模块 ZIP 刷入，完成后重启手机。
3. **安装管理器**：安装同版本 `wanBoxForAndroid-*.apk`，打开应用即可自动识别已运行的核心服务，配置节点即刻畅享。

---

## 📚 官方文档中心 / Documentation Hub

仓库文档已按照工程体系全面分层归类，详细内容请访问 **[📖 wanBox 技术文档中心 (docs/README.md)](docs/README.md)**：

| 模块板块 | 主要内容 | 快速入口 |
|---|---|---|
| 📌 **版本发布与管理规约** | 源码对照、发布检查清单与演进规划 | [`docs/current-version.zh-CN.md`](docs/current-version.zh-CN.md) · [`docs/release-checklist.zh-CN.md`](docs/release-checklist.zh-CN.md) |
| 🏗️ **核心系统与网络路由架构** | 出站分流、B 路由、跨进程同步与代次调优 | [`docs/b-route-design.md`](docs/b-route-design.md) · [`docs/app-outbound-routing.zh-CN.md`](docs/app-outbound-routing.zh-CN.md) |
| 🎨 **界面交互与用户体验规范** | Liquid Glass 质感、Material 3 规范与 UI 重构 | [`docs/ui-redesign/`](docs/ui-redesign/) · [`docs/liquid-glass.zh-CN.md`](docs/liquid-glass.zh-CN.md) |
| 📊 **自动化验收与审查报告** | preview.4 验收报告、25MB 预算报告与备份互通 | [`docs/reports/`](docs/reports/) · [`docs/reports/v3.0.13-preview.4-evolution.zh-CN.md`](docs/reports/v3.0.13-preview.4-evolution.zh-CN.md) |
| ⚙️ **配置资产与体积基线** | 本地回环代理示例、TUN FakeIP 配置与体积基线 | [`docs/config/`](docs/config/) · [`docs/rebuild/`](docs/rebuild/) |
| 📜 **历史里程碑与归档** | 包名迁移记录、早期版本说明与恢复指引 | [`docs/APP_ID_MIGRATION.md`](docs/APP_ID_MIGRATION.md) · [`docs/RELEASE_3.0.3.zh-CN.md`](docs/RELEASE_3.0.3.zh-CN.md) |

---

## 💬 反馈与社区 / Community

- 遇到使用问题、连接异常或特性建议，欢迎提交议题：[GitHub Issues](https://github.com/Wuwan94/wanBoxForAndroid/issues)
- 交流与动态通知，欢迎加入 Telegram 频道：[@OwnBoxs](https://t.me/OwnBoxs)

---

## 📄 授权协议 / License

wanBoxForAndroid 遵循 [GNU General Public License v3.0 (GPL-3.0)](https://www.gnu.org/licenses/gpl-3.0) 开源协议。
