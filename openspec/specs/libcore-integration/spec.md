# Libcore Integration Specification

## Purpose

定义 ThroneForAndroid 与 NetProxy 所用 reF1nd sing-box 内核之间的 Go/JNI 接驳、eBPF/TUN 网络生命周期、日志、规则集和协议扩展约束。

## Requirements

### Requirement: libcore 使用固定且可验证的 NetProxy sing-box 基线

libcore MUST 使用 NetProxy v8.6.1 实际依赖的 `reF1nd/sing-box` tag `v1.15.0-alpha.11-reF1nd`，并验证其 commit 为 `78ea0ce9257bd3bedd4d1185d6cc4723f04096b4`。`nb4a.properties` 的 `SINGBOX_VERSION` MUST 是唯一版本来源；源码获取脚本 MUST 固定到该仓库并校验 remote 与 tag commit。依赖 MUST NOT 混用官方 alpha 标签和 fork API。

#### Scenario: 获取内核源码

- **GIVEN** CI 开始构建 libcore
- **WHEN** 源码获取脚本处理 `SINGBOX_VERSION`
- **THEN** remote 为 `https://github.com/reF1nd/sing-box.git`
- **AND** HEAD 与指定 tag 的 commit 完全一致
- **AND** 缓存键及版本注入使用同一个 `SINGBOX_VERSION`

### Requirement: eBPF 接管与 TUN 回退保持明确边界

Root 模块 MAY 启用 fork 提供的 Android eBPF inbound；启用前 MUST 完成权限、内核能力、挂载点和路由冲突预检。预检或启动失败 MUST 回滚本次启动新建的挂载与路由，并保留可诊断原因；只有显式配置的安全 TUN 路径可作为回退，不能把未挂载的 eBPF 状态报告为接管成功。管理应用 MUST 只通过受限 Root IPC 请求状态或操作，不得直接管理底层代理生命周期。

#### Scenario: eBPF 启动失败

- **GIVEN** 目标设备缺少所需 eBPF/cgroup 能力或 attach 失败
- **WHEN** Root 模块启动代理
- **THEN** 清理由本次启动创建的部分资源
- **AND** 使用明确启用的 TUN 回退或保持停止
- **AND** 状态报告区分预检、启动和实际接管

### Requirement: 平台接口按 box 实例隔离

每个 sing-box 实例 MUST 创建独立的 `boxPlatformInterfaceWrapper`，其 NetworkManager、TUN 名称和接口状态 MUST NOT 在并发实例之间共享。平台实现 MUST 提供 TUN 打开、fd protect、连接属主查询、网络接口枚举和默认接口监视器，以满足官方拨号路径。

#### Scenario: 正式连接与测试实例并存

- **GIVEN** 主服务运行且多个测试 box 并发启动
- **WHEN** 各 box 初始化或收到默认接口更新
- **THEN** 更新作用于该 box 自己的 NetworkManager
- **AND** 各 box 都能选择可用物理接口

### Requirement: box 启动失败后的关闭必须安全且幂等

libcore box 生命周期 MUST 区分未启动、启动中或部分失败、已启动和已关闭状态。启动失败后调用关闭 MUST 能安全释放尚存资源；同一实例的后续关闭 MUST 保持幂等。若底层启动回滚已关闭资源，libcore MUST NOT 以等价于“已关闭”的返回覆盖原始启动错误或使 JNI 调用方发生未处理异常；其他非预期关闭错误 MUST 保留诊断并返回给受控错误边界。

#### Scenario: 部分启动失败后首次关闭

- **GIVEN** 底层 box 启动时因 outbound 依赖缺失失败，并在内部回滚中关闭资源
- **WHEN** Android 通过 JNI 请求关闭该 box
- **THEN** libcore 将实例标记为已关闭并完成剩余清理
- **AND** 等价于资源已关闭的结果不覆盖原始启动错误
- **AND** 调用方不会因该关闭结果产生未处理异常

#### Scenario: 已关闭实例再次关闭

- **GIVEN** box 已完成正常关闭或失败回滚清理
- **WHEN** 调用方再次请求关闭同一实例
- **THEN** 关闭操作成功返回且不重复释放底层资源
- **AND** 生命周期日志能够区分首次关闭和幂等跳过

### Requirement: 默认网络变化遵循官方重置语义

默认接口监视器 MUST 在接口 name/index 变化时通知官方 NetworkManager，并在解析失败时保留旧接口。Android OEM 接口查找失败时 MAY 依次回退到 NetworkManager 缓存、系统接口和 Kotlin 上报的信息。`networkChangeResetConnections=false` 时普通变化 MUST 只更新默认接口而不触发 ResetNetwork；从无网络恢复时仍 MUST 通知 NetworkWake。正常切网 MUST NOT 由 Kotlin 层额外重复重置。

#### Scenario: Wi-Fi 与移动网络切换

- **GIVEN** 默认物理接口发生变化
- **WHEN** 平台监听器报告新的 name/index
- **THEN** 接口缓存先刷新
- **AND** 开启网络变化重置时仅触发一次官方 ResetNetwork 路径
- **AND** 禁用该设置时保留连接且更新默认接口

### Requirement: fd protect 失败不得静默回环

主进程 fd protect MUST 经 Unix socket 转发到 VPN 后台进程。仅 socket 不存在或拒绝连接（表示 VPN 未运行）时 MAY 放行直连；超时、协议错误或 protect 返回失败 MUST 作为拨号错误返回。服务端 MUST 并发处理 fd 请求并隔离 panic。

#### Scenario: protect 确认超时

- **GIVEN** VPN 正在运行但 protect socket 未及时确认
- **WHEN** 测速或正式连接拨号
- **THEN** 拨号失败并返回可诊断错误
- **AND** 未 protect 的 fd 不会进入 TUN 形成代理回环

### Requirement: 日志按来源执行一致的级别门控

sing-box PlatformWriter 消息 MUST 在 Go 平台 writer 中按配置级别过滤；Kotlin `Logs` MUST 在 JNI 调用前过滤；插件进程 MUST 通过启动参数接收等价级别。日志 MAY 包含脱敏的 outbound 链拓扑，但 MUST NOT 包含 UUID、密码、Reality 公钥或其他认证材料。

#### Scenario: info 级日志运行

- **GIVEN** 用户设置日志级别为 info
- **WHEN** 内核、Kotlin 和插件产生 debug 消息
- **THEN** debug 消息不会写入日志文件
- **AND** error/warning/info 仍按各通道规则保留

### Requirement: geo 规则兼容新旧命名

规则生成 MUST 同时接受 `geoip-cn`/`geosite-cn` 与旧 `geoip:cn`/`geosite:cn`。官方命名 SHOULD 优先使用已有 `.srs`；旧命名或缺失的官方 `.srs` MUST 可从本地 geo 数据库转换并缓存。`geoip-private` 与 `geoip:private` MUST 映射为 `ip_is_private`。

#### Scenario: 加载旧版 geo 规则

- **GIVEN** 用户规则包含 `geosite:cn`
- **WHEN** libcore 在 `box.New` 前预处理配置
- **THEN** 规则被转换为可用的本地 rule-set
- **AND** 生成缓存可在源数据库未变化时复用

### Requirement: 自定义协议扩展使用固定内核 registry/API

Juicity outbound 与覆盖版 HTTP outbound MAY 由 libcore 自定义注册，但 MUST 基于固定 reF1nd fork registry/API。HTTP TLS 在用户未显式配置 ALPN 时 MUST 提供 `h2` 与 `http/1.1`，并按协商结果使用 HTTP/2 CONNECT 或 HTTP/1.1 CONNECT。

#### Scenario: 连接 h2-only HTTPS 代理

- **GIVEN** HTTPS 代理仅接受 HTTP/2 且用户未显式指定 ALPN
- **WHEN** HTTP outbound 完成 TLS 握手
- **THEN** ALPN 可协商为 `h2`
- **AND** outbound 使用 HTTP/2 CONNECT 建立代理流

### Requirement: 已知官方内核降级必须显式保留

SSR/Snell、WireGuard outbound 以及 Clash API selector 回调等尚未完成的兼容项 MUST 在主规范中保持可见，且 MUST NOT 被描述为已支持。WireGuard 在完成 outbound 到 endpoint 的配置迁移前 MUST 被视为已知不兼容项。

#### Scenario: 评估 WireGuard 节点

- **GIVEN** 当前官方内核只支持 WireGuard endpoint
- **WHEN** Android 仍生成 WireGuard outbound
- **THEN** 项目文档将其标记为已知降级
- **AND** 不以静默兼容或完整支持对外承诺

### Requirement: libcore 提供节点隔离的上下行速度测试

libcore MUST 提供可由 Android 调用的速度测试接口，经指定节点的 outbound 执行下载和上传测试，并返回可区分节点的下载速度、上传速度、传输字节、延迟、服务端信息、错误与取消状态。测速 MUST 遵守调用方提供的模式、超时和 URL 设置，MUST 支持取消，且 MUST NOT 绕过指定 outbound 或与其他测试实例共享可变网络状态。

#### Scenario: 下载上传完整测速

- **GIVEN** Android 为一个或多个节点请求下载+上传测速并提供有效配置
- **WHEN** libcore 启动速度测试
- **THEN** 每个节点的下载与上传流量均经该节点 outbound
- **AND** 返回结果包含下载和上传吞吐量、字节数、延迟及服务端信息

#### Scenario: 测速被取消

- **GIVEN** 一批速度测试正在执行
- **WHEN** Android 发出取消请求或测试上下文结束
- **THEN** libcore 停止尚未完成的网络操作
- **AND** 返回或发布可识别的取消状态，而不是把取消报告为成功

#### Scenario: 自定义速度测试 URL

- **GIVEN** Android 提供了用户自定义的服务发现、下载、上传或简单下载 URL
- **WHEN** libcore 执行对应速度测试阶段
- **THEN** 网络请求使用已提供的 URL
- **AND** 未提供自定义值时使用与选定 Throne 参考版本一致的默认值
