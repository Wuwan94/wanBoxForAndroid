# preview.3 内核与 TUN 迁移评估

核对日期：2026-11-08（Asia/Shanghai）。只评估，不升级内核、不更改默认配置。

## 版本与结论

- 本仓库固定 `v1.15.0-alpha.13-reF1nd`，源码提交 `17397d022647b73378705e1f91ff031dddaaec21`。
- 本次通过 GitHub releases API 核对 SagerNet/sing-box：最新 1.15 预览仍为 `v1.15.0-alpha.13`（2026-10-24）；另有稳定 1.14.4，并非 1.15 稳定版。reF1nd/sing-box tags 中仍为同一 alpha.13-reF1nd 提交，未发现 alpha.13 之后的新 1.15 预览。
- 结论：preview.3 沿用固定 fork。不要因上游有较低版本号的稳定版便降级，也不把 alpha 宣称稳定。固定提交不会自动获得未来上游修复；每次发行重新核对。

## 新栈与 stack 弃用

官方 1.15 日志/迁移说明：sing-tun 自研 TCP/IP 栈通过移除 `stack` 启用；旧字段于 1.15 弃用，计划 1.17 移除。官方性能描述不是 wanBox 的测量结果。

源码核对：`ConfigBuilder.kt` 当前 `TUN_STACK="system"`，生成配置显式写入此值；`docs/config/tun-fakeip.json` 示例仍写 `mixed`。因此现有配置没有迁移到省略字段的新栈，不能称已经使用新栈；文档示例也不是实际生成配置的依据。现有构建标签默认不含 gVisor，不应随手将运行默认改成 mixed 或重新加大内核。

未来单独迁移需要：审计 fork 的新栈代码与构建标签，生成配置省略字段后通过 sing-box check，覆盖 TCP/UDP/DNS/IPv6、分应用 UID、Root 启停清理、网络切换、URLTest 首连及全员失效；同条件测量 CPU/内存/耗电/吞吐后再决定。旧备份保留原值与键，明确转换策略，不删设置。当前版本不做此迁移。

## Android auto_redirect

官方 1.15-alpha.1 已宣布 Android 经图形客户端 root service 或 root shell 完整支持 auto_redirect。wanBox 是 Root 模块拥有生命周期，不是普通 VPN 客户端。

源码核对：`ConfigBuilder.kt` 对 Root TUN 已生成 `auto_redirect=true`（非 Root 不写），并非本轮新增；静态示例中 false 不能据此推断运行默认关闭。此评估不改变这一既有行为。上游支持不等于 wanBox 的 Magisk/KernelSU/APatch 矩阵已通过；尚未做真机回归。需核对设备路由/防火墙能力、UID排除及清理流程，不能与现有重定向规则叠加后未经验证就称可用。

## 来源与边界

- https://github.com/SagerNet/sing-box/releases
- https://github.com/reF1nd/sing-box/tags
- https://sing-box.sagernet.org/changelog/
- https://sing-box.sagernet.org/migration/

本次结论由版本列表、固定提交和配置源码核对得到；不代表实际网络/Root 设备测试。没有启用 MASQUE、Tailcat、多内核或 VPN 回退。
