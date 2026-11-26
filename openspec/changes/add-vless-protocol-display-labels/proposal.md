## Why

Android 已能导入并运行 VLESS XHTTP 与 Reality 节点，但 `ProxyEntity.displayType()` 过去把它们都显示为普通 `VLESS`，用户在列表、详情和通知栏无法快速区分关键传输/安全特征。需要让现有配置字段驱动一致的可见标签，同时保持普通 VLESS、VMess 与节点名称兼容。

## What Changes

- VLESS + XHTTP 显示为 `VLESS + XHTTP`。
- Reality 公钥非空时显示为 `VLESS · Reality`；同时具备两种特征时显示为 `VLESS + XHTTP · Reality`。
- 固定节点及已观测到的策略组活动成员通知附加对应标签；其他协议通知名称保持不变。
- 同步更新中文工程说明、Android 应用 OpenSpec 主规范与本变更的规范 delta。
- 策略组拨号逻辑、HWID 外发、VLESS 核心配置和分享链接均不在本变更范围。

## Capabilities

### Modified Capabilities

- `android-application`: 明确 VLESS 变体在节点列表、选择/详情及通知中的标签契约。

## Impact

- Android：`ProxyEntity.displayType()`、`NotificationPresentation`、`ServiceNotification` 以及相关 JVM 单测。
- 文档：新增 VLESS 标签说明并更新索引；另如实记录策略组 2 秒目标与当前 3 秒实现差异，不更改策略逻辑。
- 不改 `nb4a.properties`、libcore 协议、数据库 schema、Release/Tag 或个人网站。
