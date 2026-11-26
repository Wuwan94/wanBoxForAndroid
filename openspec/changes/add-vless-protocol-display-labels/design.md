## Context

当前节点模型已持久化 VLESS 的传输类型与 Reality 公钥：XHTTP 由 `StandardV2RayBean.type` 表示，Reality 的 URI 解析将 `security=reality` 规范化为 TLS，并把 `pbk` 保存到 `realityPubKey`。节点列表、选择列表和详情已有 `displayType()` 显示入口；通知路径则直接使用 `displayName()`，因此需要把同一标签规则接入通知节点名。

## Decisions

1. 在 `ProxyEntity.displayType()` 内基于现有 VLESS Bean 识别标签：先保留 VMess 判定，再按 XHTTP 与非空 Reality 公钥计算纯类型、组合或普通 VLESS 标签。XHTTP 比较忽略大小写。
2. 通过 `NotificationPresentation.nodeName(displayType, displayName)` 只为 XHTTP/Reality VLESS 追加标签。普通 VLESS、VMess 和其他协议沿用原节点名。
3. 在 `ServiceNotification` 的固定节点初始标题、选中节点、活动 outbound tag 映射及渲染回退路径复用上述函数。活动成员仍只依据已观测 tag 解析；未知成员继续显示既有等待提示。
4. 只改展示层，不增加存储字段，不改变订阅/链接格式、核心配置、拨号时限或 HWID 行为。

## Risks / Trade-offs

- 标签变更会影响依赖 `displayType()` 的搜索文本和其他类型展示入口；当前规则保持普通 VLESS 与 VMess 输出不变。
- 单元测试覆盖通知纯函数及配置展示标签，但不能代替 VPN/Root 服务运行时通知验证。Root 真机矩阵明确保留为未验证项。
