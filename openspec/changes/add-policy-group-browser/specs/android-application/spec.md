# Android Application Specification — Delta: add-policy-group-browser

## ADDED Requirements

### Requirement: 策略组页面必须按运行中配置的真实快照浏览原生组

策略组页面 MUST 以运行中核心控制器返回的出站快照为唯一数据来源，列出其中
类型为 Selector、URLTest 或 LoadBalance 的原生组，展示组类型、当前选中成员
（`now`）与成员列表（`all`，保持快照顺序）。成员显示名 MUST 优先按运行快照的
tag 映射解析为节点显示名；成员为其他组时 MUST 显示其组标识；无法解析的成员
MUST 以原始 tag 显示，不得丢弃。核心未运行或控制器不可达时，页面 MUST 显示
明确的空状态并提供重试，MUST NOT 用数据库中的组名或节点名拼凑、猜测组数据。

#### Scenario: 浏览运行中的策略组

- **GIVEN** 核心正在运行且控制器可达，快照中含主组 `proxy` 与一个均衡组
- **WHEN** 用户打开策略组页面
- **THEN** 页面列出两个组，主组置顶，各显示类型、当前选中与成员数
- **AND** 展开任一组可见其全部成员且顺序与快照一致

#### Scenario: 核心未运行时不显示假数据

- **GIVEN** 核心未运行或控制器不可达
- **WHEN** 用户打开策略组页面
- **THEN** 页面显示核心未运行的明确空状态与重试入口
- **AND** 不出现任何由数据库拼凑的组或成员

### Requirement: Selector 组切换必须回读校验，自动组不得手动切换

对 Selector 组，用户选择成员后应用 MUST 通过控制器切换并回读该组状态，
回读的当前成员与目标一致才 MUST 视为成功；HTTP 错误或回读不一致时 MUST
显式报错并按服务器真值刷新界面，MUST NOT 显示假成功。URLTest 与
LoadBalance 组成员由核心自动选择，页面 MUST 注明自动选择且 MUST NOT
提供手动切换入口。

#### Scenario: 切换 Selector 组成员并校验

- **GIVEN** 一个 Selector 组当前选中成员 A，成员 B 在其成员列表中
- **WHEN** 用户点选成员 B 且控制器回读显示当前成员为 B
- **THEN** 页面把 B 标为当前选中并提示切换成功

#### Scenario: 切换失败不假成功

- **GIVEN** 一个 Selector 组当前选中成员 A
- **WHEN** 用户点选成员 B 但控制器返回错误或回读仍为 A
- **THEN** 页面显式报错，当前选中仍显示 A

#### Scenario: 自动组不可手动切换

- **GIVEN** 一个 URLTest 组
- **WHEN** 用户查看其成员列表
- **THEN** 成员行不可点选切换，并注明该组为自动选择

### Requirement: 整组测速必须逐个成员给出真实结果或明确失败

策略组页面 MUST 提供对组内每个成员逐个测延迟的能力，使用与现有节点测速
相同的控制器延迟接口与测速 URL 口径。每个成员 MUST 显示测得的毫秒数，
或在超时、错误、成员不在运行配置中时显示明确的失败标注，MUST NOT 以 0
或估算值冒充结果。测速 MUST 可随页面离开而取消，未完成成员 MUST NOT
残留假结果。

#### Scenario: 整组测速逐个回填

- **GIVEN** 一个组有三个成员，其中一个成员测速超时
- **WHEN** 用户对该组发起测速
- **THEN** 两个成员显示各自毫秒数，超时成员显示失败标注

#### Scenario: 离开页面取消测速

- **GIVEN** 整组测速正在进行
- **WHEN** 用户离开策略组页面
- **THEN** 未完成的测速被取消，不再回填结果
