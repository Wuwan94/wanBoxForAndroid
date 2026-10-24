# 纯 Root 模块化实施记录

作者：雾晚。此分支先在本地实施与验证；用户随后授权发布实验预览版。实际发布状态以 Actions 与 Release 为准。

## 基线与原因

- wanBox 基线：`1ea09c6aef379cecac09c7237cc088e9bffa44bb`，本地分支 `feat/root-module-runtime`。
- 核心 pin：reF1nd/sing-box `v1.15.0-alpha.11-reF1nd`，commit `78ea0ce9257bd3bedd4d1185d6cc4723f04096b4`；依赖该 fork 的 NetProxy eBPF 入站实现。
- 旧 RootTunService 持有 su 子进程、App 私有配置以及 App PID；独立执行文件仍依赖 App 生命周期。旧代码还保留 VPN 授权与失败回退。
- 参考 [NetProxy lifecycle](https://github.com/Fanju6/NetProxy-Magisk/blob/27c909d9f0982355b7f3a6c767ecccfc9e56770a/src/native/netproxy/internal/module/lifecycle.go)、[配置事务](https://github.com/Fanju6/NetProxy-Magisk/blob/27c909d9f0982355b7f3a6c767ecccfc9e56770a/src/native/netproxy/internal/module/config_transaction.go)、[启动脚本](https://github.com/Fanju6/NetProxy-Magisk/blob/27c909d9f0982355b7f3a6c767ecccfc9e56770a/src/module/service.sh)。仅借鉴模块启动、监督器、事务模式，未复制实现。
- 安装约定依据 [Magisk 模块指南](https://topjohnwu.github.io/Magisk/guides.html)、[KernelSU 模块指南](https://kernelsu.org/guide/module.html)。实际管理器安装兼容仍待设备验证。

## 进程与数据流

```mermaid
flowchart LR
  Manager[Root 管理器 service.sh] --> Boot[wanboxctl boot]
  Boot --> Supervisor[独立 supervisor]
  Supervisor --> Core[rootbox / sing-box]
  Supervisor --> Helpers[必要外部协议辅助进程]
  UI[原有 Android 管理界面] --> SU[su / 固定 CLI]
  SU --> Transaction[配置验证与原子提交]
  Transaction --> Data[私有持久快照]
  Data --> Supervisor
  SU --> Status[身份核对后的实时状态]
  Status --> UI
```

模块代码在 `/data/adb/modules/wanbox`；持久数据在 `/data/adb/wanbox`。监督器不是 App 子服务；在 Android 上通过现有 cgroup mount 信息把自身迁到 init 的 cgroup，失败即拒绝运行。此机制是否避开各管理器/厂商的 force-stop/freezer 必须实测，不保证所有设备兼容。

App 的 RootTunService 仅在绑定时观察模块。页面隐藏后解绑并停止观察，不停止核心。模块拥有 TUN、原生网络监听、有限重启和资源释放。App 的订阅更新、节点编辑及单独节点测速仍属于管理任务；它们按需持有物理网络监听，不充当后台 watchdog。

## 配置契约与回滚

快照协议兼容 `schemaVersion=1`（完整配置 JSON 仍由 App 生成）；`schemaVersion=2` 由模块从 `rootInbound` 构造 Root 入站；`schemaVersion=3` 再从 `rootProxyGroup` 构造顶层代理组；`schemaVersion=4` 允许 `rootOutbounds` 传递 SOCKS/SS 结构化字段；`schemaVersion=5` 扩展到基础 HTTP 与 Trojan（仅常规 TLS、SNI、ALPN 与 insecure）；`schemaVersion=6` 再把 Root 全局路由策略（自动探测接口、进程识别、Android VPN 覆盖、默认域名解析器和拨号网络策略）作为结构化字段由模块写入；`schemaVersion=7` 把静态 hosts 原文交给模块解析并生成 `dns-hosts` 服务器；`schemaVersion=8` 将 DNS 的 final server 选择和地址族策略交给模块校验、写入；`schemaVersion=9` 再将独立 DNS 缓存策略一并作为结构化字段。`schemaVersion=20` 只把 ConfigBuilder 在 IPv4-only/IPv6-only 模式生成的精确 IP 版本拒绝路由规则改为结构化字段；重复、扩展或其他形态仍留在过渡配置。 `schemaVersion=21` 仅迁移 ConfigBuilder 可选的精确 `ip_is_private: true` → `bypass` 规则；`schemaVersion=22` 再迁移唯一、无扩展字段的固定 `port: [53]` → `hijack-dns` 规则，并按原路由索引恢复。 `schemaVersion=23` 再迁移唯一、无扩展字段的 `protocol: ["dns"]` → `action: "hijack-dns"` 规则，并保留其原路由索引。重复或带扩展字段的规则仍走过渡配置。 `schemaVersion=35` 再仅将精确的源 IP CIDR-only reject 规则以原始 CIDR 文本交给 Root 模块解析并保序生成；混合、自定义或无效规则仍留在旧路径。 `schemaVersion=36` 再仅将精确的目标 IP CIDR-only reject 规则以原始 CIDR 文本交给 Root 模块解析并保序生成；混合、自定义或无效规则仍留在旧路径。 `schemaVersion=41` 仅将显式 `domain:` 后缀 reject 规则的原始匹配文本交给 Root 解析并按原索引生成 `domain_suffix`；`full:` 精确域名规则、隐式后缀、混合和无效形态仍留在旧路径。DNS 服务器定义和其余规则仍保留在过渡配置。App 仍先用 Kotlin `ConfigBuilder` 生成过渡配置，再仅将已支持的出站、全局路由策略、静态 hosts 和已迁移的 DNS 策略投影成结构化字段；携带传输层、uTLS/ECH/Reality、证书、自定义项或代理链的出站继续留在旧配置中。路由匹配规则、DNS 服务器及规则、规则集、FakeDNS、hosts 关联规则和订阅 DNS 仍由 App 生成，故这是渐进迁移，不是纯 UI 客户端或完整 profile 由模块独立生成。模块会校验协议/字段/端口/标签并生成实际 sing-box 对象；不在 schema 声明范围的字段不会被投影。所有版本仍携带其余配置对象、Base64 资源、固定辅助协议清单、用户自启/性能偏好、节点显示信息及 `expectedRevision`。现有 Room、备份格式、订阅及路由顺序不改；从现有数据库生成快照而非搬迁/清空数据库。历史 VPN/proxy 模式值按产品要求归一化为 Root，不保留 VPN 后备能力。

1. 解析和大小/路径校验：编码最多 192 MiB、配置 8 MiB、资源总量 128 MiB、单文件 64 MiB、512 文件、32 辅助进程；禁绝 App 绝对路径/路径穿越。
2. 私有 staging 完整写入配置、Geo/SRS/CA、YACD 和所需插件资源，真正调用固定核心 `--check`（30 秒上限）。Geo 别名兼容旧规则；缺失/损坏资源不得静默成为空规则。
3. revision 冲突先拒绝；当前核心保持运行直到校验成功。保存 previous，再停止、原子提交 current、启动新核心。启动失败完整停止新核心后恢复上一版；清理失败不再启动。
4. 手工 `config rollback` 可恢复 previous；`config validate` 不接管流量。首次没有有效快照时 boot 不启动。

验证配置不等于节点可连。远程规则集仍沿用现有下载/初始处理能力，不把离线 schema 校验说成完整网络验证。自定义配置若依赖无法快照化的外部文件将明确拒绝，不能继续依赖 App 存储。

每次核心生命周期最多 4 次启动，退避 1/2/4 秒；预算耗尽需手动重试。身份使用 PID、启动时间、可执行路径；旧 PID 不作当前成功。辅助进程可在身份验证后有限升级 SIGKILL；核心保留 SIGTERM 清理路径，清理超时拒绝重启。状态没有 ready/有效进程时不展示旧统计。

## UI 与产品边界

保留主题、布局、导航、桌面图标、节点/规则卡片、磁贴、快捷方式。原连接按钮改为提交配置/启动模块，停止只控制模块；App 打开从 CLI 查询实际状态。VPN Service/授权 Activity/回退及可用模式选项已移除，共享 Root TUN 地址、路由、DNS 配置仍保留。

原“自动连接”值不擅自改变：App 成功控制模块后同步自启开关，模块 boot 尊重持久 opt-out。App-only WakeLock/唤醒后重置等无效入口已删除，保留历史键值用于备份兼容；不虚构 native WakeLock。App 不再拥有代理前台通知；模块状态由原管理界面显示。Widget 在 App 绑定或显式动作后更新，关闭 App 时不会增加常驻轮询，其显示可能滞后，点击动作仍查询真实模块状态。

## 清理与设备验收门槛

启动/停止、禁用（监督器最多 10 秒发现）、升级及卸载只清理本模块识别的进程，通过 sing-tun Close 释放路由；不全局 flush 防火墙。卸载模块/App 均保留私有恢复快照；其中包含凭据，不能上传。升级先停止旧核心，清理失败即拒绝升级。

**不能据此宣称 SIGKILL/内核崩溃后的网络恢复已完成。** 现有 TUN 名称及核心 auto-redirect 规则命名仍须与其他 Root 模块做冲突验证；安装前停用其他接管 TUN 的模块，不自动清理他人的路由。模块管理器强制卸载/内核断电也可能跳过优雅清理。

| 验收环境/操作 | 当前状态 |
|---|---|
| Magisk 安装、启用、升级/卸载与重启自启 | 无在线 Root 设备，未执行 |
| KernelSU 相同流程、SELinux 与 cgroup 脱离 | 未执行 |
| 关闭/强停/卸载 App 后真实代理继续、重开状态一致 | 未执行 |
| Wi-Fi/蜂窝、断网恢复、IPv4/IPv6/DNS/UDP、Doze | 未执行 |
| Linux 真实子进程/取消/回滚 fixture 与 race | 首次 Actions Native Build 已通过；后续热更新版本以本次 Actions 结果为准 |
| Android 仪器测试与 UI 前后截图 | 仅编译，未执行 |

设备验收：先备份并连接同一虚构/测试节点，记录 `status` 的 supervisor/core PID 与 revision；依次关闭页面、force-stop 管理 App、重开、更新配置、提交损坏配置、重启、自启关闭、禁用/启用、升级、卸载。每步验证代理实际通路、私有数据保留、进程数、`ip rule`/路由/防火墙前后差异。执行两种管理器并覆盖上述网络矩阵后才可发布支持结论。

## 本地验证命令

```text
go -C rootmodule test -count=1 -v ./...
go -C rootmodule vet ./...
python -m unittest discover -s rootmodule -p "test_*.py"
(cd libcore && go test ./internal/...)
go -C ../sing-box-netproxy run ./cmd/sing-box check -c docs/config/local-proxy.json
go -C ../sing-box-netproxy run ./cmd/sing-box check -c docs/config/tun-fakeip.json
gradlew.bat --offline --no-daemon :app:testPreviewDebugUnitTest :app:assemblePreviewDebug :app:compilePreviewDebugAndroidTestKotlin
gradlew.bat --offline --no-daemon :app:lintPreviewDebug
git diff --check
```

配置校验实际应传绝对配置文件路径。Android Go CLI/rootbox 使用 NDK Android31 ARM64 编译为 PIE，fresh core 含 `rootmodule` build tag；主线四 ABI 构建能力保留。构建 Android 管理 APK 的既有 AAR 不是手工修改对象，新的独立核心由源码编译并放在 ZIP。

本地 Debug APK 不使用正式版发布签名，不能声称可覆盖已安装正式版；需使用原发布流程与原签名验证升级，不能让用户卸载丢数据。仅递增预览版版本号，包名/签名策略不变。产物清单、校验和和实际结果记录在交付目录，生成文件不提交。

## 免手机重启更新与三种数据方式

`install.go` 校验固定文件白名单、SHA256/大小、ARM64 PIE、模块 ID，并用新核心重新校验当前快照。待旧进程清理后，在模块目录同一文件系统 rename 切换代码；若正在连接则重启核心，失败尝试回滚。管理器 staging 必须与活动目录不同；SELinux、管理器挂载及断电原子性仍是设备验收项。手机无需重启不等于连接无中断。

`pack.py` 生成不带 APK包及 manifest；`bundle_manager.py` 在 CI 已验证原签名/包名/版本/ABI 后加入同一公开 APK，生成带管理 APK包。仅后者执行 Android package shell 安装，失败不回滚模块，也不删除旧 App 数据。

数据方式现改为安装器选择：音量上循环、音量下确认，默认保留全部；全新安装或仅保留节点/订阅需再次确认。带 APK 包另可选择安装/覆盖更新或跳过，不带 APK 包不操作管理 App。已删除 App 的「模块更新数据」按钮。详情见 [安装器选项](install-options.zh-CN.md)。安装器保存带包指纹和令牌的已确认意图，配套新版 App 首次打开时用 PortableBackup 导出全部数据，再通过 `data prepare` 停止并归档模块配置，Room 事务写入完整空计划或保留节点/分组计划，最后 `data finish` 和令牌确认。路由/其他偏好不保留；不改 Room schema，不 root 操作 App SQLite，不执行 pm clear、不删除内置资源和备份。

App AtomicFile 日志和模块固定路径日志持久化步骤。中断时 App 从备份重复同一计划，模块完成剩余 rename；未完成时 CLI 的连接、配置提交、升级受阻。热更新和标准开机共用请求令牌，完成记录防止同一暂存包在重启后再次重置数据。两个存储域不伪称同一 SQLite 原子事务；恢复机制提供可重入完成及完整备份。模块 `data rollback` 只用于未完成事务的管理员恢复，使用前应先恢复对应 App 完整备份。归档保留敏感凭据且不自动删除，可用 Root 文件管理器复制 App 私有备份，再用已有工具页导入入口恢复。

新增测试覆盖代码切换/损坏拒绝/新核心不兼容/启动失败回滚、禁用和卸载标志、数据准备重入/连接阻断/归档恢复。启动失败测试用注入的生命周期故障，不能替代 Android Root 真机。Android 节点保留与清空使用真实序列化/Room 的仪器测试另行编译，执行结果以设备/CI 记录为准。

## preview.9 安装时序修复

详见 [NetProxy 安装对照](install-reference.zh-CN.md)。preview.9 等待管理器收尾后再热切换，并处理管理器删除安装期文件；该修复不属于 preview.8 的行为。


Schema v10 将 ConfigBuilder 固定的 `dns-local` 本地 DNS 服务器作为结构化字段交给模块重建；非标准本地服务器仍留在过渡配置。DNS 规则、其他 DNS 服务、规则集、FakeDNS、订阅 DNS 与扩展出站尚未全部迁移。

Schema v11 结构化重建固定的 `dns-direct` 备用解析器；v12 只迁移指向现有出站的 `route.final`；v13/v14 仅迁移精确匹配的内建 loopback/link-local 与 multicast 安全规则，并在模块端按原索引恢复。Schema v15 再将唯一匹配的固定 DNS `outbound: ["any"]` → `dns-direct` 规则交给模块，保持其余 DNS 规则及顺序在过渡配置中；重复匹配或不支持内容仍留在旧路径。Schema v16 仅迁移与地址族策略一致、唯一且位于首位的内建 DNS A/AAAA 拒绝规则；其余规则继续留在过渡配置。


Schema v17 仅接管 ConfigBuilder 生成的精确 DNS 端口 53 路由保护规则；其他匹配、重复或自定义规则继续保留在旧配置路径。

Schema v18 只将 ConfigBuilder 的精确 `protocol: ["dns"]` 保护路由规则作为结构化输入；与端口 53 规则及其他规则一起按原索引恢复，重复、自定义或扩展匹配继续留在过渡配置。

Schema v19 仅迁移 ConfigBuilder 可选的固定绕过局域网规则；只有唯一、无扩展字段且 CIDR/直连完全匹配时才由模块按原索引恢复，重复或自定义规则仍保留在过渡配置。

Schema v24 只迁移 ConfigBuilder 生成的唯一、精确 `{action: "sniff"}` 路由动作；重复或带其他匹配字段的同类规则继续保留在过渡配置中，并按原顺序恢复。其它自定义路由匹配仍走旧路径。


Schema v38 transfers exact source-port plus destination-port-only reject rules as raw port text; the Root module parses both lists and restores their original route order. Unsupported and malformed rules remain on the App-generated path.


Schema v42 将仅显式 `keyword:` 域名拒绝规则作为原始文本交给 Root 模块解析并按原规则顺序生成 `domain_keyword`；混合或不支持输入继续保留在 App 旧路径。

Schema v43 的 `domain_regex` 投影现已停用，相关规则继续由 App 生成；原始文本/索引测试不足以证明 pinned core 的 matcher/action 运行等价。Schema v44 仅接管精确 `{network: [tcp/udp…], outbound: tag}` 规则，保留网络列表、出站选择及原始规则位置；遇到不支持的输入继续走旧路径。Schema v45 仅接管 Root TUN、DNS 路由关闭、纯包名规则（无域名/IP/ruleset/端口/网络/来源/协议/扩展动作），App 解析 Android UID 后作为结构化输入；Root 按原位置还原 `package_name` 与可用 `user_id` 及同一出站。含 DNS 副作用或任何复合条件的应用规则仍留在 App 旧路径。UID/规则集展开、域名/IP 拆分与反向语义、DNS route/reject/FakeDNS 顺序及其他出站迁移仍未完成；v45 不表示 G7 全量完成。
