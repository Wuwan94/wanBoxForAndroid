# wanBoxForAndroid

<p align="center">
  <img src="docs/logo.png" width="128" height="128" alt="wanBoxForAndroid Logo">
  <br>
  <b>面向 Root 设备的独立 Android 代理项目；发布状态与当前源码分支请见下方版本核对</b>
  <br>
  <b>An independent, root-focused Android proxy project; see the release/source snapshot below</b>
</p>

<p align="center">
  <a href="https://github.com/Wuwan94/wanBoxForAndroid/releases"><img src="https://img.shields.io/badge/Stable-v3.0.7-blue.svg?style=flat-square" alt="Latest stable release"></a>
  <a href="https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.10-preview.18"><img src="https://img.shields.io/badge/Preview-v3.0.10--preview.18-orange.svg?style=flat-square" alt="Latest preview build"></a>
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

截至 **2026-10-22**，本仓库 GitHub Releases 当前可见的最新正式版是 [v3.0.7](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.7)，最新预览版是 [v3.0.10-preview.18](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.10-preview.18)。`main` 的 `nb4a.properties` 仍声明 `VERSION_NAME=3.0.9`、`PRE_VERSION_NAME=3.0.10-preview.18` 与 `SINGBOX_VERSION=v1.15.0-alpha.11-reF1nd`；这些是源码构建元数据，不能作为该版本已经发布的证明。

**preview.16 管理器更换了签名密钥，不能直接覆盖旧管理器。** 请先阅读该 Release 的卸载旧管理器/重新安装说明并做好备份，不要把下文或旧版本记录中的覆盖安装结论套用到本版。Release 说明称节点与订阅保存在模块目录；这并不等于所有 App 私有设置都能保留。

预览 tag 已采用 `src/` + `module.yaml` 的模块项目结构，与 `main` 的 Android App / `rootmodule/` 布局不同。下载包的功能、版本与安装步骤应以下载包对应的 tag 为准，不将不同源码快照混写为同一版本。详细依据见 [发布与源码版本对照](docs/current-version.zh-CN.md)。下文架构及历史说明保留其原有范围。

As of **2026-10-22**, the repository's currently visible stable release is **v3.0.7**, and its newest prerelease is **v3.0.10-preview.18**. The `main` source metadata is different and is not proof of publication. **The preview.16 manager uses a new signing key and cannot overwrite the old manager.** Read its release-specific migration instructions first. Its tagged source layout also differs from `main`; do not apply this branch's architecture or historical upgrade guarantees to a different release snapshot.

## 📖 项目介绍 / Introduction

**wanBoxForAndroid 从 OwnBoxForAndroid 的早期代码基础发展而来，目前由本仓库独立维护，拥有自己的代码、版本发布与功能路线；它不是 OwnBoxForAndroid 的官方版本，也不再以持续同步上游作为项目目标。项目面向已 Root 的 Android 设备，采用独立 Root TUN 模块承载内核与代理生命周期：模块自行保存节点、订阅与配置，并通过仅限回环的 HTTPS 管理接口对外提供能力，App 只做展示与指令下发，退出或被终止都不影响代理运行，也不提供普通 VPN 模式。本段为当前源码分支的架构说明，不代表所有已发布包均采用同一实现。公开发布版本与本分支元数据并不一致，见上方版本核对；各版本功能和安装兼容性以对应 tag 与发行说明为准。**

**wanBoxForAndroid evolved from an early OwnBoxForAndroid codebase and is independently maintained with its own code, releases, and roadmap. It is not an official OwnBoxForAndroid release and does not aim to continuously sync upstream. The project targets rooted Android devices and runs its core through an independent Root TUN module: the module owns node, subscription and configuration data and exposes a loopback-only HTTPS management API, while the app only presents state and sends commands, so the proxy keeps running after the app exits or is killed. Ordinary VPN mode is not offered. This paragraph describes the current source branch, not every published artifact. Published releases and this branch's metadata differ; use the release/source snapshot above and each tag's own documentation for features and installation compatibility.**

项目起源、当前状态与发展方向详见[项目定位与发展说明](docs/project-positioning.zh-CN.md)。

### 当前架构要点 / Architecture Highlights

* **模块拥有代理生命周期**：配置有效后由模块独立启动、监督与停止核心，App 退出、被清理或强行停止都不会中断代理。
* **模块是数据真值**：节点、订阅与全局配置保存在模块 `store/` 目录，App 只做展示、编辑与指令下发；覆盖升级保留原有数据与设置。
* **仅限回环的 HTTPS 管理面**：每次安装独立的 Bearer token + 自签证书钉扎，接口包含状态、事件流（SSE）、节点、订阅、配置、启停与热重载；核心停止时接口仍可访问。
* **节点热切换不重启**：切换经 Clash API 选择器完成，状态由 `runtime/selection.json` 承载，界面按钮与节点名不闪烁。
* **纯 Root TUN**：不提供 VPN 模式，也不申请 VPN 权限。

* **The module owns the proxy lifecycle**: once a config validates, the module starts, supervises and stops the core on its own; killing the app never tears the proxy down.
* **The module is the source of truth**: nodes, subscriptions and global config live in the module's `store/` directory; the app only presents, edits and sends commands, and upgrades keep existing data.
* **Loopback-only HTTPS management API**: a per-install bearer token plus self-signed certificate pinning, covering status, an SSE event stream, nodes, subscriptions, config, start/stop and hot reload; the API stays reachable while the core is stopped.
* **Hot node switching without a restart**: switching goes through the Clash API selector and is persisted in `runtime/selection.json`, so the header button and node name never flicker.
* **Root TUN only**: no VPN mode and no VPN permission.

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
