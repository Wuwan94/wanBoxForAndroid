# AnyTLS 双向 TLS（mTLS）客户端身份验证

AnyTLS 配置可以保存客户端证书和私钥，并将其用于 sing-box outbound TLS。证书和私钥保留在本地配置档中；标准分享链接和通用 `sn://` 分享链接都不会带上这两项。

## 配置客户端证书

编辑 AnyTLS 配置档，在 TLS 安全设置中填写客户端证书与私钥的 PEM 文本。应用会把多行 PEM 映射到 outbound TLS 的 `client_certificate` 和 `client_key` 行数组；留空时不生成对应字段。保存后，材料随本地配置档持久化，旧版配置档读取时新字段默认为空。

私钥不会显示在设置项摘要中。分享或导入 `anytls://`、`sn://` 链接都不会传递 mTLS 身份；这些链接只适用于不需要该客户端身份的场景。

## 备份与导出注意事项

本地 profile 数据库会保存客户端证书和私钥。便携式配置备份及 sing-box JSON 配置导出也可能包含这些 TLS 材料；它们不是脱敏分享格式，应按私钥文件保管，不要公开上传或发送给不可信对象。分享链接单独执行脱敏，不代表备份或配置导出文件已脱敏。

本次改动不改变 HWID 策略，也不会发送 HWID；Mieru 与策略组行为不在本功能范围内。

## 验证状态

- **本地 JVM 已验证：** profile/Kryo 往返、旧版 v1 profile 兼容、PEM 行数组 outbound 映射、标准 `anytls://` 分享边界，以及完整 OssDebug、PreviewDebug、core-domain JVM 测试均通过。
- **本地门禁已验证：** `tools/` 132 项单测、仓库治理检查与 CoreRuntime 调用门禁通过；本次未改 Go/libcore，因此本地 Go `-race` 不适用，且未改 `nb4a.properties`。
- **Android instrumentation：** `sn://` 脱敏（含原 profile 保留）和私钥摘要测试已加入；最新 `OssDebug`、`PreviewDebug` 与 instrumentation APK 均编译成功。本地 API 35 AVD 未能启动到可用状态（ADB 持续 `offline`），故不宣称 instrumentation 本地实跑通过。
- **CI：** GitHub Actions `Verify Android Build` 及 CI CoreRuntime 门禁待 PR 运行并补记结果。
- **需 Root 真机矩阵实测：** 使用启用 mTLS 的 AnyTLS 服务端，在 Magisk、KernelSU、APatch 支持设备上分别验证连接、证书接受/拒绝、重连及配置切换。该矩阵目前未实测。
