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
  <a href="https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.11-preview.2"><img src="https://img.shields.io/badge/Preview-v3.0.11--preview.2-orange.svg?style=flat-square" alt="Published preview.2; preview.3 is a candidate only"></a>
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

截至 **2026-11-06**，已发布正式版为 [v3.0.11](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.11)，最新公开预览为 [v3.0.11-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.11-preview.2)。真实 APK / 模块资产实测分别为 **10065 / 2013** 与 **10077 / 2015**，不是从源码或发布说明推断，证据见 [版本对照](docs/current-version.zh-CN.md)。

本分支准备 **v3.0.11-preview.3 稳定性候选**，不是已发布版本。基线纳入 preview.2 的 `2fbe17f662`，保留开工时主分支 `ff26111` 的已有界面；重点是后台查询、损坏导入失败保护、启动诊断和状态读取超时。没有增加新的代理模式或重做全局 UI。候选元数据双码 `2016`，规则输出正式线 `10080`、预览线 `10083`；最终产物仍须逐项核验，参见 [候选验证记录](docs/preview3-validation.zh-CN.md)。

**安装要求：Android 12+、arm64-v8a、Root（Magisk / KernelSU / APatch）**。瘦管理器不包含核心，必须搭配同一套候选或同一发布页的模块，不能混用；安装前先备份并检查 `SHA256SUMS`。版本码和同签名只证明覆盖安装的静态前提，**未完成真机运行时回归**，不承诺无损升级。

本轮仅独立分支和 PR，不合并 main、不创建 tag/Release、不上传发布资产、不清理旧版。仍保留「成功发布并核验新预览后才只留一版预览」的策略，正式版不受预览清理影响。[v3.0.11 正式版历史说明](RELEASE_NOTES.md)保持原文。

Published: stable v3.0.11 and preview.2. This branch prepares an **unpublished preview.3 candidate** only. Requires Android 12+, ARM64 and Root. Real-device runtime regression is not complete; no Baseline Profile or performance improvement is claimed.
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
