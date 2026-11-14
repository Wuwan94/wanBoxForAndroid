## MODIFIED Requirements

### Requirement: 本地开发不依赖 Go 或 Android 编译环境

开发流程 MUST 默认假定本地未安装 Go 环境且未克隆 sing-box 源码。未获任务范围内的明确用户授权时，AI/开发者 MUST NOT 要求用户在本地编译 Go 核心或 Android APK；可执行不依赖这些环境的静态校验。用户明确授权某项任务的本地 Go/Android 验证门禁时，AI/开发者 MUST 在该授权范围内执行要求的构建与测试，并在对应 OpenSpec change 中记录授权范围、命令和结果；本地结果 MUST NOT 替代 GitHub Actions 或用户真机验证。Go 核心、Android APK 和真机行为 MUST 仍由 GitHub Actions 与用户真机验证。外部依赖的源码、版本和 API 定义 SHOULD 通过官方网络来源查询。

#### Scenario: 验证跨 Go 与 Android 的修改

- **GIVEN** 一次修改影响 Go 核心或 Android 编译，且用户未明确授权本地构建验证
- **WHEN** 本地实现完成
- **THEN** 本地只运行可用的静态检查
- **AND** 最终编译交由 GitHub Actions
- **AND** 运行时行为交由真机验证

#### Scenario: 用户明确授权任务的本地验证门禁

- **GIVEN** 用户明确授权某项任务在本地运行 Go/Android 构建与测试
- **WHEN** 实现进入该任务的本地验证门禁
- **THEN** AI/开发者 MUST 执行授权范围内要求的构建与测试，并在对应 change 记录命令、结果及环境阻塞
- **AND** AI/开发者 MUST NOT 将本地通过等同于远程 CI、instrumentation 或真机通过
- **AND** 授权 MUST 仅适用于该任务，不改变其他任务默认的远程构建政策
