# 设计：VLESS + XHTTP 手动新建

## 用户路径

配置列表手动添加菜单中的 `action_new_xhttp` 进入 `ConfigurationFragment` 分派，然后以 `vless=true`、`xhttp=true` 启动现有 `VMessSettingsActivity`。Activity 根据新建 intent 创建 VLESS Bean，并将 `type` 设为 `xhttp`；随后仍由 `StandardV2RaySettingsActivity` 绑定现有表单与传输选项。

## 默认值与兼容

- `alterId = -1` 是现有 Bean 表示 VLESS 的约定，不引入新的协议实体。
- 仅 XHTTP 入口覆盖传输 `type` 为 `xhttp`；普通 VLESS 保持既有默认 `tcp`，VMess 不带 `vless` 标记时仍走现有初始化路径。
- XHTTP 模式和扩展字段继续由现有 Bean 默认初始化逻辑赋值，模式默认为 `auto`；不新增或复制设置页。
- 新菜单文字通过 `action_vless_xhttp` 资源提供，默认与简体中文资源采用相同的标准协议名。

## 非目标

不改 URI/剪贴板/订阅解析、Xray 或 sing-box 核心、连接策略、节点显示标签、策略组行为、HWID 协议或发送行为。Root 模块代码也不改；Root 真机仍需实测入口、保存、回读和实际连接路径。
