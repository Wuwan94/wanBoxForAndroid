# 任务：UI 按模块功能对齐（批次 3）

## 1. 诊断数据与格式化

- [x] 1.1 Status 补齐 lastExitAt 可选解析（若已解析则注明跳过），含解析单测
- [x] 1.2 新增 ModuleDiagnostics 纯格式化函数：Status → 有序诊断行（健康、退出原因/码/时间、重试、最近 5 条事件），缺字段隐藏
- [x] 1.3 格式化单测：全字段、缺字段隐藏、事件截断与排序、全空返回空

## 2. 界面

- [x] 2.1 设置 Root 区呈现诊断行（复用既有 status 调用结果），无诊断数据时不占位
- [x] 2.2 文案双语同步（values 与 values-zh-rCN）

## 3. 收尾

- [x] 3.1 本地门禁：可跑检查如实跑（Kotlin/Gradle 以 CI 为门禁并如实标注）；双轴复审（astra）通过、终审裁决完成
- [x] 3.2 推功能分支、开 PR、Verify CI 全绿后合并（本批不单独发版，全部批次完成后统一发 preview.3）

验证记录：先新增 parseResponse 两种负载与 ModuleDiagnostics 四种情形的测试，再补实现。按约束未在本地运行 Gradle/Kotlin 测试，红/绿执行结果均待 CI 门禁确认；本地仅做差异与资源静态检查。tasks 3.x 未完成。

## 4. 批次 3 astra 返修（4 项 P2）

- [x] 4.1 将 change 的 ADDED Requirement 及场景同步到 android-application 主规范
- [x] 4.2 诊断 Preference 按 formatter 返回顺序分配明确递增 order
- [x] 4.3 先新增同秒 A–F 应返回 F–B 的回归测试，再按（时间、源索引）降序截取最近 5 条
- [x] 4.4 在已有 moduleVersionFlow collect 内重取 status 并刷新诊断；design 明确页面打开与版本流变化触发，不新增轮询或订阅
- [x] 4.5 版本流变化时改用 callFreshStatus() 跳过缓存聚合重取诊断；保留页面首次普通 status 调用、失败保留上次诊断及取消传播

返修验证：新增测试先于实现落盘；旧实现同秒稳定排序会返回 A–E，与测试预期 F–B 不符。按要求未本地运行 Gradle/Kotlin 测试，未声称红/绿执行通过，测试与构建由 CI 门禁确认。`git diff --check` 本地通过。本次仅实现，不做复审；3.1、3.2 仍待后续复审与 CI 流程完成。

4.5 验证记录：本地静态核对 collect 使用 callFreshStatus()，该入口会在锁内使缓存失效并重新读取；页面首次调用及两处异常处理保持原样。现有 JVM 单测未配置 Robolectric/FragmentScenario，涉及此 Fragment 的测试主要是源码契约检查，无法有意义地断言生命周期内版本流、有效缓存与 Preference 重绘的联动；因此不新增仅匹配调用字符串的伪回归测试。本地 `git diff --check` 通过；未运行 Gradle/Kotlin、Android UI 或真机测试，未核验远程 CI。

待执行回归：CI 完成构建与现有测试门禁后，在有 Root 模块的真机打开设置 Root 页并让首次 status 缓存诊断 A；在其 4 秒有效期内将模块诊断改为 B，并通过 dashboard 发布新版本码（避免先由 status 轮询更新缓存），保持页面打开，确认版本码及诊断均更新为 B，且无需等待缓存过期或再次版本变化。另核对刷新读取失败时保留上次诊断、离开页面取消时不吞掉取消异常。上述运行时回归尚未执行，不能视为已通过，3.1、3.2 保持未勾选。

终审裁决（批次 3）：双轴复审三轮后全部发现闭环（Standards 0 项、Spec 静态 0 项）。astra 建议合并前完成 tasks §4.5 的真机回归；按本项目既定流程，真机回归不是合并前置（批次 1、2 及历次预览均在 CI 全绿后合并，真机项记为未验证风险），故该回归列入批次 3 报告的未验证真机风险，不阻塞合并；3.2 待 Verify CI 全绿后勾选。

## 归档说明（2026-11-17）

- 3.2：功能分支 `feat/ui-module-alignment`，PR #54，2026-11-15 合并，main `2fca51a`。首轮 Verify 在 connected 测试步骤出现与本批无关的 flake（同一时段文档 PR #55 亦有同一步失败记录），失败作业重跑后全绿。本批不单独发版，已随 v3.0.16-preview.3 统一发布。
- 主规范同步已于实施期完成（任务 4.1）。§4.5 的 4 秒缓存窗口真机回归未执行，按统筹裁决列未验证真机风险随带，不作合并前置。本目录移入 `openspec/changes/archive/2026-11-17-align-ui-with-module-diagnostics/`。
