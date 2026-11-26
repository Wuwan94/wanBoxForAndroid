# VLESS + XHTTP / Reality 协议标签

## 展示规则

- VLESS 使用 XHTTP 传输时显示为 `VLESS + XHTTP`。
- Reality 公钥非空时显示为 `VLESS · Reality`；导入的 Reality 节点以 `realityPubKey` 字段识别。
- 同一节点同时使用 XHTTP 与 Reality 时显示为 `VLESS + XHTTP · Reality`。
- 普通 VLESS 仍显示 `VLESS`，VMess 仍显示 `VMess`。

协议标签由现有 Bean 字段即时计算，不新增数据库字段或迁移，也不改分享链接、核心配置或协议实现。节点列表、节点选择列表和详情页沿用 `ProxyEntity.displayType()`；通知栏为固定节点及已观测到的策略组活动成员追加相同标签。未知活动成员仍沿用既有等待提示，不猜测节点。

## 本地/CI 已验证项

- 本地 `app:testOssDebugUnitTest` 与 `app:testPreviewDebugUnitTest` 全量 JVM 测试套件通过；定向用例覆盖普通 VLESS、VMess、XHTTP、Reality、组合标签、固定节点通知、活动策略组成员通知及其他协议文案保持不变。
- 本地 `:core-domain:test` 通过；`python3 -m unittest discover -s tools -p 'test_*.py'` 的 132 项通过。
- 本地 `tools/check_current_version_docs.py`、`tools/check_core_gate.py`、`python3 tools/check_android_security.py ossDebug previewDebug` 与 `git diff --check` 均通过。安全检查前已生成 PreviewDebug packaged manifest。
- TDD 顺序为逐项先失败、再最小实现并转绿；没有修改策略组拨号、HWID 外发或 libcore 协议逻辑。
- Go `-race` 对本 PR 不适用（无 Go/libcore 代码改动）；GitHub Actions 仍会执行项目核心门禁。OpenSpec CLI 未安装。
- 本地 Gradle 单测使用忽略的临时 `libcore.aar` 符号链接，指向现有已发布 AAR，仅满足 Android JVM 编译依赖；它不是当前分支的原生核心构建结果。本地完整原生核心构建未完成。
- GitHub Actions `Verify Android Build`：PR 创建后待运行；通过前不视为 CI 已验证。

## 需 Root 真机矩阵实测项

尚未执行 Magisk、KernelSU、APatch 真机矩阵。待在 Root 模式分别验证固定 VLESS+XHTTP、VLESS·Reality、两者组合的通知标题；再验证策略组活动成员切换后通知跟随实际 outbound tag 更新且不沿用旧成员；同时确认普通 VLESS、VMess 通知文案不变。自动化单测不等同于 Root 服务通知真机验证。
