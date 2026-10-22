# 发布与源码版本对照

核对日期：2026-10-22。此页只更新文档，不修改版本字段、代码、签名、工作流或任何已发布 Release。

## 当前可见发布与代码

| 对象 | 当前依据 | 不能据此推断 |
|---|---|---|
| 最新可见正式 Release | [v3.0.7](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.7)，API `prerelease=false` | 不能因 `main` 写了 3.0.9 就说 3.0.9 已正式发布 |
| 最新可见预览 Release | [v3.0.10-preview.18](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.10-preview.18)，API `prerelease=true` | 不能当作正式版；发行说明不是本次重新执行的真机测试 |
| 本次核对的 `main` | [902b6ce095c40865b2aa8a5a181cd2bc550ec78f](https://github.com/Wuwan94/wanBoxForAndroid/tree/902b6ce095c40865b2aa8a5a181cd2bc550ec78f) | 该提交同时是 v3.0.10-preview.18 的 tag 提交；附件、验证范围与安装边界仍以该 tag 和发行说明为准 |
| `main/nb4a.properties` | `VERSION_NAME=3.0.9`、`PRE_VERSION_NAME=3.0.10-preview.18`、`SINGBOX_VERSION=v1.15.0-alpha.11-reF1nd` | 这些字段既不证明对应 Release 存在，也不证明远端附件使用该核心 |

preview.16 的源码有 `module.yaml`、`src/`、`README_EN.md`，没有 `nb4a.properties`。它是模块项目布局；`main` 仍有 Android App 与 `rootmodule/` 布局。历史 changelog、技术审查和回归记录按各自日期/提交保留，不把不同快照的实现、版本和验证结果强行合并。

## preview.16 安装与更新边界

- 发行说明明确说管理器更换了签名密钥，preview.15 及更早版本（包括 v3.0.7）不能直接覆盖安装。先备份并按该版说明卸载旧管理器，再安装目标版。
- 发行说明称节点和订阅位于模块目录，卸载管理器不会删除这些模块数据；这不是对 App 私有设置、历史数据迁移或任意 Root 环境的保留保证。
- 该版说明中的 APK `versionCode` 规则为历史峰值 1875 加提交数，不是旧 App 分支文档的 `*_CODE × 5`；发布版移除 `-ci.<sha>` 后缀。
- 模块自更新清单改从固定 `update-index` 分支读取；`releases/latest` 不代表最新预览版。
- 发行说明列出的支持范围为 arm64-v8a、Android 12+、Root（Magisk / KernelSU / APatch）。每个版本仍需核对其 tag 文档与附件，不将源码编译或发行文字等同于真机联网、安装或升级验证。

## 维护时如何判定“最新”

1. 分别读取正式和预览 Release 的 tag、`prerelease` 状态、附件与正文，不只比较版本字符串或 `releases/latest`。
2. 对照目标 tag 的源码布局和版本声明。默认分支、历史技术说明与已发布附件可能来自不同快照。
3. 只在版本来源一致时更新当前说明；历史记录保留日期和适用范围。版本字段或实际代码不一致需要单独处理，不能用文档修改伪装为已完成代码同步或发布。
