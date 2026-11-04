# wanBoxForAndroid

<p align="center">
  <img src="docs/logo.png" width="128" height="128" alt="wanBoxForAndroid Logo">
  <br>
  <b>面向 Root 设备的独立 Android 代理项目；发布状态与当前源码分支请见下方版本核对</b>
  <br>
  <b>An independent, root-focused Android proxy project; see the release/source snapshot below</b>
</p>

<p align="center">
  <a href="https://github.com/Wuwan94/wanBoxForAndroid/releases"><img src="https://img.shields.io/badge/Stable-3.0.10%20candidate-yellow.svg?style=flat-square" alt="Stable 3.0.10 candidate, not yet published"></a>
  <a href="https://github.com/Wuwan94/wanBoxForAndroid/releases"><img src="https://img.shields.io/badge/Preview-v3.0.10--preview.32-orange.svg?style=flat-square" alt="Preview 3.0.10-preview.32"></a>
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

截至 **2026-11-04（正式版 3.0.10 准备阶段）**，GitHub Releases 中**唯一已发布的版本**是 [v3.0.10-preview.32](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.10-preview.32)（`prerelease=true`），**尚无任何已发布的稳定版 Release**。`main` 的 S0 复核基线为 `2aa9239`，内核 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd`。

**正式版 `3.0.10` 是待发布候选**：`versionName=3.0.10`、APK `versionCode=10055`（元数据 `2011 × 5`）、Root 模块码 `2011`，包名与签名证书和 `.32` 相同。它必须经 PR 获批合并 → 构建与资产核验 → 明确的正式 Release 批准后才发布；源码元数据本身不证明 Release 已公开。`PRE_VERSION_NAME=3.0.10-preview.33` / `2012` 只是为满足「预览码 > 正式码」断言而预置的**未发布候选**，没有 tag 或下载资产。详细依据见 [发布与源码版本对照](docs/current-version.zh-CN.md)。

**安装要求：Android 12+（API 31+）、arm64-v8a、已 Root（Magisk / KernelSU / APatch）**，不提供普通 VPN 模式。正式版与 `.32` **同为瘦管理器**：APK 不含内嵌核心，必须从**同一个 Release 页面**安装配套的 Root 模块 ZIP，并用该页 `SHA256SUMS` 校验；不要把不同版本的 APK 与模块混用。

**升级口径**：包名、签名与版本码满足覆盖安装的静态前提，但覆盖升级后的数据保留、Root 模块状态与 TUN 重启**尚未在真机验证**。当前推荐「备份 → 卸载 → 安装 → 导入」路径，不承诺无损覆盖升级；完整步骤、数据保留边界与回滚见 [RELEASE_NOTES.md](RELEASE_NOTES.md)。下文架构及历史说明保留其原有范围。

As of **2026-11-04**, the only published release is **v3.0.10-preview.32**; no stable Release exists yet. **3.0.10** is a candidate (`versionCode 10055`, module code `2011`) and is not public until the approved PR is merged, the build/assets are verified and the formal Release is explicitly approved. Both preview.32 and the candidate are thin managers: the APK contains no embedded core and must be paired with the Root module ZIP from the **same** Release page. Requires Android 12+ (API 31+), arm64-v8a and a rooted device; no ordinary VPN mode. Data-preserving in-place upgrade from preview.32 is **not verified on a real device** - follow the backup / uninstall / install / import path in RELEASE_NOTES.md instead. 
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
