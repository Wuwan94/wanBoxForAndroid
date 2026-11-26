# 任务：Root 模块静态 WebUI（PR-D）

## 1. 页面与固定命令

- [x] 1.1 新增本地静态页面、严格 CSP、响应式样式与兼容提示
- [x] 1.2 新增固定 `wanboxctl` 动作映射、返回结构校验、稳定错误、忙态和有界事件导出
- [x] 1.3 Node 内建测试覆盖命令白名单、拒绝注入、stderr 隐藏、畸形响应和最近 40 条视图

## 2. 打包、安装和权限

- [x] 2.1 打包递归收集固定 WebUI 资源并拒绝未知资源；合包显式白名单更新
- [x] 2.2 Go 安装白名单接受四项资源、拒绝部分资源集，并保留全缺失 WebUI 的旧 manifest 兼容
- [x] 2.3 首次安装显式设置 WebUI 资源为 0644；Go 热更新只放开模块根/webroot目录与WebUI资源权限
- [x] 2.4 Python 打包红测转绿：递归资源与未知文件拒绝；首次安装权限 contract
- [x] 2.5 Go 安装权限红测转绿：模块根目录/webroot 可遍历、页面资源可读；完整资源集校验红测转绿

## 3. 体积门禁和文档

- [x] 3.1 体积预算红测先失败，再实现源与 ZIP 内 WebUI 各 1 MiB 检查
- [x] 3.2 所有 PR（含 fork）均有独立、不依赖 libcore/app 作业的 Node 测试与源/ZIP 探针 WebUI-only 门禁；若实际模块 ZIP 构建成功则另验真实 ZIP；同仓库 PR 与 Preview 候选继续执行真实 APK + 模块总量门禁
- [x] 3.3 更新中文 Root 模块文档，记录运行时边界、数据范围、文件权限、体积和回退方法
- [x] 3.4 新增 Root WebUI 主规范与本变更的 proposal/design/tasks/spec

## 4. 本地验证与独立审查

- [x] 4.1 Node WebUI 测试、Root Python 包测试、tools 全量测试（含 size-budget）全部通过
- [x] 4.2 最终 `go test -race ./...`、`go vet ./...`、真实 ARM64 模块 ZIP 构建及源/归档 WebUI 体积门禁通过；真实 APK + 模块合计留待 CI 验证
- [x] 4.3 完成 `git diff --check` 与 OpenSpec/工作流/manifest 差异检查；`nb4a.properties`、发布/Tag 语义无改动，本任务未改其他工作树
- [x] 4.4 Standards 与 Spec 双轴独立复审通过；Spec 复核无遗漏/范围外/实现错误，发现的问题已按 red-green 修复并复跑门禁
- [ ] 4.5 提交并推送独立分支，创建 PR-D；PR-C #106 保持分离，PR-D 不合并、不发布
- [ ] 4.6 报告 GitHub CI 结果；Root 真机验收单独标记待测，不由本地/CI 结果替代

## TDD 记录

- WebUI controller 采用 Node `node:test` 作为公开动作映射 seam；在实现前补测试，初次验证 8/8 通过。
- Spec 复核指出成功 envelope 中 `state:{}` 被接受；新增固定 `schemaVersion`/`ok`/`state.phase` 回归测试先红（8/10），再实现最小响应校验后绿（10/10）。
- 独立代码自审发现导出绕过视图层，可能保留事件额外字段；先增加 `eventSummary` 行为测试并因缺少导出 seam 失败，再实现共享过滤函数、让视图与下载复用，Node 测试绿至 11/11。
- 打包新增递归资源测试后，Python 测试先因合包白名单缺少资源而失败；加入固定清单后 5/5 转绿。首次安装权限测试先因 `customize.sh` 未设置 WebUI 0644 而失败，逐项补权限后 6/6 转绿。
- Go 安装权限测试先因 `package_manifest_invalid` 拒绝 WebUI 资源而失败；补 Go 白名单与最小权限后针对性测试转绿。部分 WebUI manifest 测试先失败（错误接受缺少 `style.css`），增加完整资源集校验后针对性测试转绿。
- WebUI 体积测试先因 CLI 不认识 `--webui-source` 失败；实现源目录/ZIP 子集校验与 1 MiB 门禁后，`tools.test_size_budget` 当前 5/5 通过。为覆盖无签名 APK 的 fork PR，新增 WebUI-only 测试先因 `--apk` 必填失败，再扩展 CLI；该测试 red → green。Spec 复核随后发现门禁依赖 Android app 作业；工作流改为独立 PR job 生成固定 `webroot/` ZIP 探针并运行 WebUI-only 检查，真实模块 ZIP 检查仍保留。

本地复核结果：Node 11/11、Root Python 30/30、tools 全量 136/136，版本文档离线一致性检查通过；`go test -race ./...`、`go vet ./...`、ARM64 模块 ZIP 构建、WebUI-only 源/归档门禁、Actionlint 与 `git diff --check` 均通过。源 WebUI 与最新重建 ZIP 内未压缩 WebUI 均为 13,221 字节，低于各自 1 MiB 门禁；模块 ZIP 18,322,334 字节。Standards 与 Spec 独立复核均通过；仅提示不同触发链重复维护 CI 命令，视为非阻断。下载总量 smoke test 使用 86 字节占位 APK，不作为真实 APK + 模块总量通过结论。GitHub PR/CI 与 Root 真机验收仍待完成；此 change 是 PR-D，禁止与 PR-C 混合、合并或发布。
