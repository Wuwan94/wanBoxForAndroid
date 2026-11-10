# wanBoxForAndroid

<p align="center">
  <img src="docs/logo.png" width="128" height="128" alt="wanBoxForAndroid Logo">
  <br>
  <b>面向 Root 设备的独立 Android 代理项目；发布状态与当前源码分支请见下方版本核对</b>
  <br>
  <b>An independent, root-focused Android proxy project; see the release/source snapshot below</b>
</p>

<p align="center">
  <a href="https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.11"><img src="https://img.shields.io/badge/Stable-3.0.11-green.svg?style=flat-square" alt="Stable 3.0.11"></a>
  <a href="https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.3"><img src="https://img.shields.io/badge/Preview-v3.0.13--preview.3-orange.svg?style=flat-square" alt="Published preview v3.0.13-preview.3"></a>
  <a href="https://android-arsenal.com/api?level=31"><img src="https://img.shields.io/badge/Android-12%2B%20(API%2031%2B)-brightgreen.svg?style=flat-square" alt="Minimum Android version"></a>
  <a href="https://github.com/Wuwan94/wanBoxForAndroid/releases"><img src="https://img.shields.io/badge/ABI-arm64--v8a-informational.svg?style=flat-square" alt="Supported ABI"></a>
  <a href="https://www.gnu.org/licenses/gpl-3.0"><img src="https://img.shields.io/badge/License-GPL--3.0-orange.svg?style=flat-square" alt="License"></a>
  <a href="https://t.me/OwnBoxs"><img src="https://img.shields.io/badge/Telegram-@OwnBoxs-2CA5E0.svg?logo=telegram&style=flat-square" alt="Telegram"></a>
</p>

---
<div align="center">

## 支持本项目

·如果这个项目对你有帮助,请加入 TG 频道就是对我的最大鼓励

·If this project helps you, joining our TG channel is the best support you can give me.

---
## 当前版本核对 / Release and source snapshot

当前已发布正式版为 [v3.0.11](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.11)，已发布预览版为 [v3.0.13-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.3)，固定指向 `77e291a150819a70d87c0af8458f1c603088e432`，不替换正式版；源码元数据与发布资产的边界见 [版本对照](docs/current-version.zh-CN.md)。

源码现准备 v3.0.13-preview.4：Root 模块版本码 `2024`、APK `versionCode=10124`；本地 PreviewRelease 测量 APK（临时测试签名）与 Root 模块 ZIP 已通过 26 MiB 体积门禁，但它仍是候选，不代表正式签名、哈希或发布资产已核验。当前工作分支聚焦更新任务前后台切换、瘦模式节点延迟探测、QS Tile 状态同步和分应用列表异步加载；验收证据与限制见 preview.4 候选报告。工具链为 Gradle 8.13 / AGP 8.11.1 / Kotlin 2.2.21 / KSP 2.2.21-2.0.4 / compileSdk 36 / Room 2.7.2；targetSdk 35、minSdk 31、JDK 17、Build Tools 35.0.1 不变。内核固定为 `reF1nd/sing-box v1.15.0-alpha.13-reF1nd`，不据此宣称它是当前上游最新版本。

已发布 preview.3 的版本映射为模块码 `2023` / APK `10118`；当前源码候选 preview.4 的 `2024 / 10124` 严格递增。preview.3 的发布说明保留在 [PREVIEW_NOTES.md](PREVIEW_NOTES.md) 的历史归档段落，preview.4 候选说明见同文件开头。本轮没有授权合并、发布或清理 Release/tag；**未做 Magisk、KernelSU、APatch 真机回归**，自动化结果不等于 Root 真机签核。

**安装要求：Android 12+、arm64-v8a、Root（Magisk / KernelSU / APatch）**。瘦管理器不包含核心，必须搭配同一发布页中的 Root 模块，不能混用其他版本；安装前先备份并检查 `SHA256SUMS`。更新必须校验后由用户确认安装，不静默安装，不提供 VPN 回退。

预览渠道仅保留最新一个版本；清理旧预览前先核验新版本的完整发布资产，正式版始终保留。[v3.0.11 正式版历史说明](RELEASE_NOTES.md)保持原文。

Published releases: stable v3.0.11 and preview v3.0.13-preview.3 (tag commit 77e291a150819a70d87c0af8458f1c603088e432). Source metadata is preparing v3.0.13-preview.4 (2024 / APK 10124); this candidate is not published or asset-verified. No Root-device regression sign-off has been completed. Requires Android 12+, ARM64 and Root; pair the thin manager APK with the Root module from the same release page.
---

## 📥 发行版下载 / Downloads

请前往 **wanBoxForAndroid 自己的 GitHub Releases 页面**选择所需渠道，下载该版实际提供的模块 ZIP / 管理器附件，并查看对应版本说明；不要假定每个版本都有相同格式的附件：

Choose the channel on **this wanBoxForAndroid repository's GitHub Releases page** and download the module ZIP / manager assets actually provided for that release. Asset formats differ between versions:

👉 **[前往本仓库 GitHub Releases 下载 / Download from this repository's GitHub Releases](https://github.com/Wuwan94/wanBoxForAndroid/releases)**

---

### 📦 安装包架构说明 / Architecture Notes

从 **v3.0.7-preview.6** 起，本仓库新构建只发布 **arm64-v8a**，面向 Android 12 及以上。内部 x86_64 模拟器测试包不作为下载附件。

From **v3.0.7-preview.6**, new public builds are **arm64-v8a only** and require Android 12 or newer. Internal x86_64 emulator fixtures are not release downloads.

当前版本的 Root 模块、内核与随包可执行文件均为 **arm64-v8a**，**ARMeabi-v7a、x86_64、x86 不再提供新构建**，仅存在于历史版本的发行说明与附件中。

The Root module, core, and bundled executables in current versions are **arm64-v8a** only. **ARMeabi-v7a, x86_64 and x86 are no longer built**; they only exist in the release notes and assets of historical versions.

---

## 💬 反馈与建议 / Issues & Feedback

遇到任何使用问题、连接异常或有新的功能建议，欢迎前往 **wanBoxForAndroid 自己的仓库议题页**提交反馈：

If you encounter any issues, connection errors, or have feature requests, please submit an issue to the **wanBoxForAndroid repository**:

👉 **[前往本仓库提交议题 / Submit an issue to this repository](https://github.com/Wuwan94/wanBoxForAndroid/issues)**
