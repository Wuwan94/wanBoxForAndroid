## ADDED Requirements

### Requirement: 手动新增 VLESS + XHTTP 节点

Android MUST 在配置列表的手动新建菜单提供「VLESS + XHTTP」入口，且标签 MUST 由默认语言与简体中文字符串资源提供。选择该项 MUST 打开现有 VMess/VLESS 设置页，不得复制一套独立表单；新建配置 MUST 以 `alterId = -1` 表示 VLESS，且初始传输类型 MUST 为 `xhttp`，使现有 XHTTP 表单字段可用。普通 VLESS 新建 MUST 继续默认 `tcp`，普通 VMess 新建行为 MUST 保持不变。

#### Scenario: 从手动菜单新建 VLESS + XHTTP

- **GIVEN** 用户在配置列表打开「+」并选择手动设置
- **WHEN** 用户选择「VLESS + XHTTP」
- **THEN** 应用打开既有 VMess/VLESS 设置页
- **AND** 新建实体为 VLESS 且传输类型为 `xhttp`
- **AND** 表单显示现有 XHTTP 设置能力

#### Scenario: 普通 VLESS 和 VMess 默认值保持兼容

- **GIVEN** 用户分别选择普通 VLESS 或 VMess 手动新建
- **WHEN** 应用初始化新建实体
- **THEN** 普通 VLESS 仍使用 `tcp` 默认传输
- **AND** VMess 仍采用原有默认实体初始化路径
