# 设计：Root 模块静态 WebUI

## Runtime 与来源

- KernelSU WebUI 入口为模块根目录下 `webroot/index.html`，页面脚本通过官方 `kernelsu` JS API 的 `exec` bridge 执行固定命令。参考 [KernelSU WebUI 指南](https://kernelsu.org/guide/module-webui.html) 与 [KernelSU JS API 包说明](https://www.npmjs.com/package/kernelsu)。
- APatch FAQ 声明其 WebUI 与 KernelSU 兼容（[APatch FAQ](https://apatch.dev/faq.html)）；这只作为 API 兼容的依据，不替代 APatch/设备实测。Magisk 自身不提供原生 WebUI，第三方 runtime 未验证。
- 本页资源全部本地加载；CSP 禁止 connect-src、对象内容和表单提交。页面不接触 `rootmodule/channel.go` 的私有回环 channel，不新增 HTTP 路由。

## 页面能力与命令界限

```text
页面按钮 / 初始状态读取
  -> 固定动作表：status | dashboard | logs | start | stop | restart
  -> /data/adb/modules/wanbox/bin/wanboxctl <固定子命令>
  -> 结构校验，只渲染 allowlisted state fields
  -> 中文状态、操作反馈与最多 40 条事件
```

- `controller.mjs` 在调用 bridge 前拒绝未知动作；命令无用户输入、无路径参数。
- 非零 errno、`ok:false`、无效 JSON、非正整数 `schemaVersion`、缺失 `ok:true` 或空/缺失 `state.phase` 时返回稳定错误，不带 stderr。
- 状态 UI 只显示 phase、health、profile、revision、retryCount、error 和固定字段事件；使用 `textContent`，未知状态字段不传到 DOM。
- 操作期间锁定全部按钮。事件导出限制最近 40 条，仅 JSON 状态摘要，不读原始日志/诊断归档。

## 打包与权限

- 固定四个资源：`webroot/index.html`、`app.mjs`、`controller.mjs`、`style.css`。`pack.py` 递归收集并拒绝未知文件；`bundle_manager.py` 和 Go `packageFiles` 通过显式白名单校验。
- Go manifest 校验若出现任一 WebUI 项则要求四项齐全；纯旧包可完全不带 WebUI，以保持旧 manifest 的兼容处理。
- 首次安装 `customize.sh` 的递归默认是目录 0755、普通文件 0600，因此逐一把四个 WebUI 文件改为 0644。热更新复制时只对 module root 与 `webroot/` 设为 0755、WebUI 文件设为 0644，其他资源维持现有私有权限。

## 体积预算与工作流

- `size_budget.py --webui-source <dir>` 校验固定源文件集，并统计源文件字节数与 ZIP 内 WebUI 未压缩字节数；二者分别不得超过 1 MiB。
- `.github/workflows/verify-android.yml` 为每个 PR 建立独立 job：运行 Node 行为测试，并从固定四项资源生成仅用于门禁的 `webroot/` ZIP 探针，执行源/探针 ZIP WebUI-only 检查，不依赖 `libcore` 或 Android app job；Android job 成功构建时仍检查真实模块 ZIP。
- `.github/workflows/preview.yml` 在未发布候选验证中执行相同检查。既有 APK + 模块 ZIP 27,262,976 字节门禁保持不变；不改发布分支、Release/Tag、候选哈希或发布流程。

## 回退与非目标

- 当前管理器没有兼容 API 时，页面只报不支持，不尝试降级到任意 shell 或网络接口；继续使用已有 Android Root 管理 UI。
- 本变更没有数据 schema、网络写协议或 HWID 变更；撤销实现可 revert PR-D 并重新构建模块包。此次工作不会合并或发布 PR，也不承诺 Root 真机兼容。
- PR-C #106 与该 PR-D 独立；本任务不改变 PR-C 的状态或 skipped 检查，不触碰 App Edge-to-Edge/WindowInsets。
