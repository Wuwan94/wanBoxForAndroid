## ADDED Requirements

### Requirement: VLESS 变体在列表、详情与通知中可区分

Android MUST 根据现有 VLESS Bean 字段区分传输与安全特征：XHTTP MUST 显示为 `VLESS + XHTTP`；`realityPubKey` 非空时 MUST 显示为 `VLESS · Reality`；同时具备两项特征时 MUST 显示为 `VLESS + XHTTP · Reality`。普通 VLESS MUST 继续显示为 `VLESS`，VMess MUST 继续显示为 `VMess`。节点列表、节点选择列表与详情页 MUST 使用一致的类型标签。固定节点通知及已观测到的策略组活动成员通知 MUST 显示同一标签；其他协议的通知名称 MUST 保持不变。标签 MUST NOT 暴露公钥以外的凭据或改变分享链接/核心配置。

#### Scenario: 显示 XHTTP、Reality 与组合节点标签

- **GIVEN** 用户分别保存普通 VLESS、VLESS+XHTTP、VLESS·Reality、同时具有 XHTTP 与 Reality 公钥的节点，以及普通 VMess 节点
- **WHEN** 节点列表、选择列表或详情页显示其协议类型
- **THEN** 五类节点分别显示 `VLESS`、`VLESS + XHTTP`、`VLESS · Reality`、`VLESS + XHTTP · Reality` 和 `VMess`

#### Scenario: 固定节点与策略组活动成员通知显示类型标签

- **GIVEN** 当前固定节点或策略组实际活动成员是 VLESS+XHTTP、VLESS·Reality 或两者组合
- **WHEN** 通知栏呈现节点信息
- **THEN** 节点名旁包含对应类型标签
- **AND** 策略组成员仍由实际观察到的 outbound tag 决定，不以测速或推测代替

#### Scenario: 普通协议通知文案保持兼容

- **GIVEN** 当前节点是普通 VLESS、VMess 或其他非特殊协议
- **WHEN** 通知栏呈现节点信息
- **THEN** 节点名称保持既有文案，不附加 XHTTP 或 Reality 标签

> 验证：本地定向 JVM 单测通过；GitHub Actions `Verify Android Build` 待 PR 运行；Magisk / KernelSU / APatch Root 真机矩阵待执行。
