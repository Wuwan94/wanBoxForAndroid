## ADDED Requirements

### Requirement: OEM Google 防火墙修复严格限于已验证固件与目标规则

Root 模块 MUST 只在 Android、模块活动且明确命中 RedMagic/努比亚/中兴，或 OnePlus/OPPO ColorOS V16/V16.* 白名单时执行兼容。RedMagic/努比亚/中兴仅可删除 `zte_fw_gms` filter 链中精确的单 UID owner DROP/REJECT 规则；所有存在的 IPv4/IPv6 规则必须先读取，任一读取失败时两族都不得删除。ColorOS 还 MUST 检查当前用户、secure/global 策略值、program tag、唯一关联 map ID、hash 类型及 4 字节键值结构；全部已存在目标 pin 必须在任何删除前通过预检，任一已存在 pin 结构未知时全程不得修改 map，明确缺失的 pin 可跳过；只删除值恰为 `1` 的目标 Google UID 条目。每次实际规则或 map 项删除前 MUST 再次确认模块仍启用且不处于卸载状态。UID 必须来自 Android 包管理器，非白名单共享 UID MUST 跳过。未知固件/规则/策略/BPF 结构 MUST fail-closed。实现 MUST NOT 清空共享链、DNS 规则或 BPF map，也 MUST NOT 生成、持久化或发送 HWID。

#### Scenario: 只删除专用链或已验证 map 中的精确目标

- **GIVEN** 固件、模块标记、包 UID、所有设备策略及规则/BPF 结构均通过门禁
- **WHEN** 存在目标 Google UID 的专用 DROP/REJECT 规则或 BPF value `1`
- **THEN** 只删除该匹配项
- **AND** DNS、通用/共享规则、BPF 程序、其他 UID 及其他 map 值保持不变

### Requirement: Google 联网修复使用独立、固定且 best-effort 的生命周期调度

boot-ready 后 MUST 独立于核心自启调度 60 秒延迟修复；成功热激活 MUST 在安装锁释放后从新活动目录立即调度。失败/回滚 MUST NOT 调度。Root-only 内部命令 MUST 使用固定参数并具备取消/超时；调度进程启动失败 MUST NOT 阻断模块 boot 或已提交更新。非目标平台 MUST 使用无操作实现。

#### Scenario: 开机、自启关闭或热激活

- **GIVEN** Root 模块正常启用
- **WHEN** boot-ready 完成、或热激活成功且安装锁已释放
- **THEN** 分别安排延迟 60 秒或立即运行的固定 Root-only 子进程
- **AND** 核心自启设置不改变修复调度，失败/回滚不触发热激活调度
