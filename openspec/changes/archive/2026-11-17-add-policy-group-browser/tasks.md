# 任务：策略组浏览、选择与测速（批次 2）

## 1. 控制器客户端与模型

- [x] 1.1 新增策略组控制器客户端：GET /proxies 拉取并解析组快照（类型过滤、成员保序、当前选中），鉴权与硬化方式与 ThinControllerDelayClient 一致
- [x] 1.2 Selector 切换：PUT /proxies/{tag} 后回读校验 now，一致才算成功；非 Selector 组拒绝手动切换
- [x] 1.3 成员显示名解析：运行快照 tag 反查节点名、组 tag 可读名、内置出站本地化、未知 tag 原样
- [x] 1.4 客户端单测：请求形态与鉴权、解析（含畸形负载）、切换回读校验、显示名解析

## 2. 界面

- [x] 2.1 PolicyGroupsActivity：组列表（主组置顶）、展开成员、当前标记、类型徽标
- [x] 2.2 切换交互：Selector 组成员可点切换，失败显式报错并按服务器真值刷新；自动组注明「自动选择」且不可点
- [x] 2.3 整组测速：逐个成员测延迟并回填，失败标注「失败」，离开页面取消
- [x] 2.4 空状态与刷新：核心未运行/控制器不可达的明确空状态与重试；进入/手动/切换后刷新
- [x] 2.5 入口与文案：GroupFragment 工具栏菜单加「策略组」；字符串同步 values 与 values-zh-rCN；Manifest 注册 Activity

## 3. 收尾

- [x] 3.1 本地门禁：受影响的本地可跑检查如实跑（Kotlin/Gradle 本地不可跑，以 CI 为门禁并如实标注）；双轴复审（astra）通过、终审裁决完成
- [x] 3.2 推功能分支、开 PR、Verify CI 全绿后合并（本批不单独发版，全部批次完成后统一发 preview.3）

## 归档说明（2026-11-17）

- 3.2：功能分支 `feat/policy-group-browse`，PR #51，Verify CI 全绿，2026-11-15 合并，main `a4e6ef0`。本批不单独发版，已随 v3.0.16-preview.3 统一发布（见 fix-gateway-cache-deep-copy 归档说明）。
- 主规范同步：本变更三条 delta 要求（真实快照浏览、Selector 回读校验、整组测速）在实施 PR 中未并入主规范，随本次归档补并入主 `android-application` 规范，本目录移入 `openspec/changes/archive/2026-11-17-add-policy-group-browser/`。
