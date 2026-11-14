# 安全策略 / Security Policy

wanBox for Android 面向已 Root 设备运行，拥有系统级网络路由（TUN/iptables）与底层进程管理能力。我们高度重视用户的设备安全与网络隐私。

---

## 🛡️ 支持的版本 / Supported Versions

我们为以下发布渠道提供安全更新与缺陷修复：

| 版本线 | 支持状态 | 说明 |
|---|---|---|
| **正式版 (Stable)** | :white_check_mark: 优先维护 | 当前正式版（如 v3.0.14），长期维护与热修复 |
| **最新预览版 (Preview)** | :white_check_mark: 持续维护 | 仅维护当前发布的最新预览（如 v3.0.14-preview.1） |
| **旧预览版 (Archived Previews)** | :x: 停止维护 | 预览渠道仅保留最新版本，旧版预览已被清理 |

---

## 🔒 核心安全边界与威胁模型

wanBox 在设计上坚守以下安全防线，若你发现任何打破这些防线的情况，请向我们报告：

1. **Fail-Closed 流量防泄漏**：
   - 当本地核心代理未启动或异常退出时，网络请求必须坚决报错阻断，**严禁回退为明文直连**，防止暴露用户的真实 IP 与流量。
2. **Root 权限隔离与非预期提权**：
   - 模块脚本与守护进程严禁暴露未鉴权的本地开放端口或可被三方未授权 App 滥用的提权接口。
   - 本地 Controller 通信严格绑定 `127.0.0.1` 并配合 Token 鉴权。
3. **打包签名与防篡改**：
   - 官方发布的 APK 与 Root 模块均在 GitHub Actions 官方隔离环境中自动编译并使用专用私钥签名，且附带完整的 `SHA256SUMS` 校验清单。

---

## 🚨 报告安全漏洞 / Reporting a Vulnerability

如果你发现了潜在的安全漏洞（如流量绕行泄漏、未授权 Root 提权或敏感信息泄露）：

1. **请勿在公开的 GitHub Issues 或社区群聊中公开发布漏洞细节**。
2. 请使用 GitHub 官方提供的 **[Private Vulnerability Reporting](https://github.com/Wuwan94/wanBoxForAndroid/security/advisories/new)** 功能私密提交漏洞报告。
3. 我们会在收到报告后的 48 小时内确认并评估漏洞等级，并在修复后通过 Release 说明进行负责任的致谢披露。
