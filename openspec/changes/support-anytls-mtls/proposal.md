## Why

AnyTLS 现有配置档缺少客户端证书与私钥输入项，无法配置需要双向 TLS 身份验证的服务端。通用 `sn://` 链接会序列化完整节点对象，因此新加入的私钥材料必须在分享边界显式排除。

## What Changes

- 为 AnyTLS 本地 profile 增加客户端证书和私钥 PEM 字段；旧版 profile 解码时两个字段默认为空。
- 将非空 PEM 映射到 sing-box outbound TLS 的 `client_certificate` / `client_key` 行数组。
- 在 AnyTLS 设置页增加多行输入项，私钥内容不显示在摘要中。
- 标准 `anytls://` 与通用 `sn://` 分享链接均排除客户端证书和私钥；通用分享不得修改本地 profile。
- 文档明确便携式备份与 sing-box JSON 配置导出可能包含敏感 TLS 材料。

## Capabilities

### New Capabilities
- `anytls-mtls`: 定义 AnyTLS 客户端 mTLS profile 持久化、outbound 映射、旧格式兼容和分享边界。

### Modified Capabilities
- `android-application`: 增补 AnyTLS mTLS 设置、配置生成、敏感数据分享与验证契约。

## Non-Goals

- 不修改 Go/libcore、服务端协议或 `nb4a.properties`。
- 不改变 HWID 发送策略（继续不外发）、策略组行为或 Mieru 覆盖范围。
- 不把本地 profile、便携式备份或配置导出文件声明为已脱敏。

## Impact

- Android Bean/Kryo 持久化、AnyTLS 设置页和 sing-box TLS JSON DTO。
- AnyTLS 分享 URI 序列化与对应 Android/JVM 测试。
- 简体中文用户文档与 OpenSpec 契约。
