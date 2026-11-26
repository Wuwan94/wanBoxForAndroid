## Context

AnyTLS 节点由 Android 的 `AnyTLSBean` 持久化，sing-box outbound 通过 `OutboundTLSOptions` 生成。锁定的 sing-box fork 将 `client_certificate` 与 `client_key` 定义为可省略的 PEM 字符串列表。标准 `anytls://` 格式本身没有客户端身份字段；通用 `sn://` 则会编码 Bean 的 Kryo 字节，因此必须在序列化前专门清除 mTLS 字段。

## Decisions

### 1. 将证书与私钥存入本地 profile

在 `AnyTLSBean` 中增加 `clientCertificate` 和 `clientKey`。新 profile 采用 Kryo v2；v1 反序列化时这两个字段置为空串，避免旧用户数据因缺字段失败。设置页使用多行文本输入，私钥摘要固定为安全说明而非输入值。

### 2. 在 outbound TLS 层按 PEM 行数组映射

对非空输入按行拆分并剔除空白行，输出至 `tls.client_certificate` 与 `tls.client_key`。空值保持省略；既有 AnyTLS TLS 行为不变。

### 3. 在所有分享 URI 边界排除 mTLS 身份

`anytls://` 解析器不读取客户端身份字段，因此该格式导出/导入保持空字段。`sn://` 对 AnyTLS 先克隆 Bean 并清空证书、私钥，再编码克隆对象；不得修改原 profile，也不得删除其他节点参数。两种格式均不得携带客户端私钥或证书。

### 4. 将备份与配置导出视为敏感资料

本次仅保护分享 URI。便携式备份和 sing-box JSON 配置导出仍可包含 Bean/TLS 材料；文档必须明确提示这些文件含敏感信息，不能声称已脱敏。

## Verification

- JVM：profile/Kryo 往返、v1 兼容、outbound TLS JSON 列表和标准分享 URI 边界。
- Android instrumentation：真实 `sn://` URI 往返脱敏、设置页私钥摘要不回显。
- CI：GitHub Actions `Verify Android Build`。
- 不改 Go/libcore，因此本地 Go `-race` 不适用；CI 核心门禁结果仍需记录。
- Root 真机矩阵（Magisk / KernelSU / APatch + mTLS 服务端连接）待实测。
