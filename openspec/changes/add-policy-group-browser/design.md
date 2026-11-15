# 设计：策略组浏览、选择与测速

## 数据通路

- 新增控制器客户端（bg/proto，仿 ThinControllerDelayClient 的鉴权与硬化方式：
  127.0.0.1:9090、Bearer secret、NO_PROXY、不跟随重定向、响应体上限、超时）：
  - `GET /proxies` 拉取全部出站快照；组 = type 为 Selector / URLTest /
    LoadBalance 的条目（大小写不敏感），成员取 `all` 数组（保序），
    当前选中取 `now`。
  - `PUT /proxies/{tag}` body `{"name": member}` 切换 Selector 组；
    随后 `GET /proxies/{tag}` 回读，`now` 与目标一致才算成功。
  - 成员延迟复用既有 ThinControllerDelayClient.measure（同一控制器、
    同一测速 URL 口径）。
- 成员显示名解析：tag → 节点显示名走运行快照
  （RootModuleClient `node tags` 的 profileId→tag 反查 + 配置数据库名称）；
  成员本身是组时显示其组名（tag 可读名）；direct / block 等内置出站
  显示本地化标签；无法解析的 tag 原样显示，绝不丢弃成员。

## 界面

- 入口：GroupFragment 工具栏菜单新增「策略组」，打开 PolicyGroupsActivity。
- 组列表：组名、类型徽标、当前成员、成员数；主组 `proxy` 置顶，其余按名排序。
- 点组展开成员（同页内展开，不新建页面）：成员行显示显示名、当前选中标记、
  延迟结果（测速后）。Selector 组成员行可点切换；URLTest / LoadBalance 组
  成员行不可点，并注明「自动选择」。
- 每组有「测速」动作：顺序逐个测成员延迟（单个超时 5 秒），逐个回填结果；
  失败显示「失败」而非数字；离开页面取消未完成测速。
- 刷新：进入页面拉取、手动刷新按钮、切换成功后自动回读刷新。
- 空状态：控制器不可达 / 核心未运行时显示明确空状态文案与重试按钮。

## 边界与失败

- 解析防御式：字段缺失、类型未知、成员为空都显式成状态或跳过并计数，
  不崩溃、不臆造。
- 切换失败（HTTP 错误、回读不一致）显式报错并以服务器真值刷新界面，
  不显示假成功。
- 文案进字符串资源，同步 values 与 values-zh-rCN 两套现有语言。

## 测试

- 客户端单测（仿 ThinControllerDelayClientTest 的本地回环 ServerSocket 方式）：
  GET /proxies 请求形态与鉴权、组解析（保序、当前值、未知类型忽略、
  畸形负载报错）、PUT 切换请求体与回读校验（一致才成功、不一致报错）。
- 显示名解析的纯逻辑单测：反查命中、组 tag、内置出站、未知 tag 原样。
