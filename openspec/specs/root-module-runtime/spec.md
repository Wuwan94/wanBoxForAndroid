# Root Module Runtime Specification

## Purpose

定义 wanBox Root 模块对特定 OEM Google 联网限制的安全兼容行为，以及其生命周期、精确修改边界和验证责任。

## Requirements

### Requirement: OEM Google 防火墙修复仅适用于明确白名单

Root 模块 MUST 只在 Android 上、模块处于活动状态且设备固件明确命中支持范围时执行修复。支持范围 MUST 限定为 RedMagic/努比亚/中兴的专用 GMS 防火墙链，或 OnePlus/OPPO 的 ColorOS 16 专用 BPF 路径。模块活动状态 MUST 同时具备常规文件 `module.prop`、可执行的常规文件 `bin/wanboxctl`，且不存在 `disable` 或 `remove` 标记。未知品牌、ROM 或平台 MUST 跳过，不得推测兼容。

#### Scenario: 不支持固件跳过

- **GIVEN** 品牌不在白名单、OnePlus/OPPO ROM 不是明确的 ColorOS V16/V16.*，或运行平台不是 Android
- **WHEN** 内部修复命令执行
- **THEN** 不读取或修改防火墙/BPF 规则
- **AND** 不阻断常规模块启动或更新

#### Scenario: 模块已禁用或卸载中

- **GIVEN** `disable` 或 `remove` 标记存在、固定 CLI 不可执行或模块标记不可确认
- **WHEN** 子进程等待延迟后复核状态，或在每次实际删除前复核状态
- **THEN** 修复跳过且不修改任何规则

### Requirement: 修复生命周期独立于核心自启且不改变事务结果

在 `sys.boot_completed=1` 后，Root 模块 MUST 独立于核心自启偏好调度一次延迟 60 秒的固定 Root-only 子进程。热激活仅在新模块目录提交成功且安装锁释放后 MUST 从新活动目录调度一次立即修复；失败/回滚 MUST NOT 调度。后台子进程启动失败 MUST NOT 阻断 boot 或已提交热更新。内部命令 MUST 仅接受固定子命令和规范化、有上限的延迟参数；进程与系统命令 MUST 支持取消/超时。

#### Scenario: 核心自启关闭仍检查厂商规则

- **GIVEN** Android boot 已完成且 Root 模块启用，但核心自启为关闭
- **WHEN** `Runtime.Boot` 完成 boot-ready 等待
- **THEN** 独立修复子进程仍被安排在 60 秒后运行
- **AND** 核心仍保持停止状态

#### Scenario: 热激活失败回滚

- **GIVEN** 模块更新事务失败并恢复原目录
- **WHEN** 激活函数返回
- **THEN** 不会从暂存目录或失败的新版本启动修复进程

### Requirement: UID 识别与专用链删除必须精确

实现 MUST 从 Android user 0 包管理器输出解析包 UID，并 MUST 跳过任何被非白名单包共享的 UID。RedMagic/努比亚/中兴路径 MUST 仅枚举 `iptables` 和 `ip6tables` 的 filter 规则，并只删除 `zte_fw_gms` 链中精确的单 UID owner 规则，终端动作仅允许 DROP 或 REJECT（可有明确 `--reject-with` 值）。存在的两族规则必须先全部读取；任一族读取失败时 MUST 在两族中都不删除，随后才可开始精确删除。实现 MUST NOT flush 链或改动通用链、共享规则、接口绑定规则、DNS 规则、其他 UID 或未知语法。

#### Scenario: 目标 UID 被专用链精确拦截

- **GIVEN** 目标 Google UID 未被其他包共享，且 `zte_fw_gms` 有完全匹配的单 UID DROP/REJECT 规则
- **WHEN** 修复执行
- **THEN** 仅按原参数删除该精确规则
- **AND** 共享链、DNS/53、通用 output 及其他 UID 规则保持不变

#### Scenario: 包 UID 共享或解析异常

- **GIVEN** Google 包 UID 与非白名单包共享，或包管理器输出缺字段/冲突/非法
- **WHEN** 修复准备修改规则
- **THEN** 共享 UID 被跳过或整体拒绝解析
- **AND** 不以部分/推测 UID 修改规则

#### Scenario: IPv6 规则读取失败时不部分修改 IPv4

- **GIVEN** IPv4 规则包含目标项，但存在的 `ip6tables` 规则读取失败
- **WHEN** 双栈规则预检结束
- **THEN** `iptables` 与 `ip6tables` 都不执行删除

### Requirement: ColorOS BPF 修复须通过用户策略和结构校验

OnePlus/OPPO MUST 仅在 ColorOS V16/V16.*、当前 Android 用户为 0、secure `google_restric_info=1` 且两个指定 global 用户策略均为 `null` 或 `0` 时进入 BPF 修改。对每个目标 pin，程序 MUST 为 socket filter、tag MUST 精确等于已验证值、程序 MUST 只关联一个 map 且其 ID MUST 与目标 map 一致，map MUST 为 hash 且键/值均为 4 字节。所有已存在目标 pin 和 program/map 结构 MUST 在任何删除前完成预检；任一已存在 pin 的元信息未知时 MUST 在整个 ColorOS 修复中不修改任何 map，明确缺失（ENOENT）的目标 pin可以跳过。仅当目标 Google UID 对应 map value 恰为 `1` 时，MUST 删除该条目；每次删除前 MUST 再次确认模块仍启用且不处于卸载状态；MUST 保留 BPF 程序及 map，不得清空 map。

#### Scenario: 已知 ColorOS 16 结构且值为拒绝位

- **GIVEN** 用户、secure/global 设置、program tag、关联 map ID 与 map 类型/宽度全部匹配
- **WHEN** 目标 UID 的 map value 恰为 `1`
- **THEN** 只删除该 UID 条目
- **AND** 程序、map、其他 UID 和 value 非 `1` 的条目保持不变

#### Scenario: BPF 结构或策略未知

- **GIVEN** 当前用户不是 0、设置读取失败、策略值未知，或任一已存在目标 pin 的 program/map 字段与已知布局不符
- **WHEN** ColorOS 修复准备执行
- **THEN** 即使其他目标 pin 已通过预检，也不删除任何 map 项
- **AND** 不重建或 flush 共享 BPF 对象

### Requirement: 不收集设备标识，设备兼容结论须以真机验证

该兼容 MUST NOT 生成、持久化或发送 HWID 或其他设备标识。Go 单测和 GitHub Actions 的 Android 构建通过 MUST NOT 被表述为 Root OEM 真机兼容已证实；支持结论 MUST 记录真实设备/ROM/Root 管理器及实际规则或 map 验收结果。

#### Scenario: 测试通过但无目标设备

- **GIVEN** 本地 Go 测试和 CI Android 构建通过，但没有在线 Root 设备结果
- **WHEN** 汇报变更状态
- **THEN** 只报告代码/CI 通过
- **AND** RedMagic/努比亚/中兴及 ColorOS 16 真机验证仍标记待测
