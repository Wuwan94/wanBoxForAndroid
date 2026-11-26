## 1. Profile 与 outbound

- [x] 1.1 为 AnyTLS Bean 增加本地客户端证书/私钥字段，并通过 Kryo v2 保存。
- [x] 1.2 兼容旧 v1 profile；解码时新 mTLS 字段默认为空。
- [x] 1.3 把非空 PEM 映射至 sing-box TLS `client_certificate` / `client_key` 行数组，空值省略。
- [x] 1.4 增加并通过本地 JVM profile 往返、v1 兼容与 outbound JSON 映射测试。

## 2. 分享 URI 与设置页

- [x] 2.1 确认标准 `anytls://` 不导出 mTLS 字段，并增加分享边界测试。
- [x] 2.2 通用 `sn://` 分享先克隆 AnyTLS Bean 并清空证书/私钥，不修改本地 profile。
- [x] 2.3 设置页增加中英文多行证书/私钥输入，私钥摘要不回显。
- [x] 2.4 增加 Android instrumentation 覆盖 `sn://` 脱敏及私钥摘要行为；Debug 与 Release instrumentation CI 通过，本地 API 35 AVD 持续 `offline`，故未在本地设备实跑。

## 3. 文档与验收

- [x] 3.1 更新简体中文功能说明及对应 OpenSpec 契约，说明便携式备份和 JSON 导出的敏感性。
- [x] 3.2 本地 AnyTLS focused 与完整 OssDebug/PreviewDebug/core-domain JVM 测试、132 项 `tools/` 单测、治理门禁、CoreRuntime 门禁通过。
- [x] 3.3 本次不改 Go/libcore，故本地 Go `-race` 不适用；未改 `nb4a.properties`。CI CoreRuntime 直调门禁通过。
- [x] 3.4 GitHub Actions `Verify Android Build` attempt 2 通过，含 Debug/Release instrumentation、Xray v26.11.9 XHTTP 互通 12/12；Module Size Lab 与 baseline/cross-version backup evidence skipped。
- [ ] 3.5 Root 真机矩阵（Magisk、KernelSU、APatch + mTLS 服务端连接）待实测，不以模拟器替代。
