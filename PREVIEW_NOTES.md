# v3.0.20-preview.1 发行说明（候选）

**状态：未发布的稳定性预览版候选。** 正式版仍为 `v3.0.17`；官方 sing-box 内核与 `SINGBOX_VERSION` 未改。

这一版是 `v3.0.17` 正式版之后的**第一次完整汇总预览**：从 `v3.0.18-preview.1` 到 `v3.0.19-preview.4` 的四个 3.0.18 预览、四个 3.0.19 预览，加上预览线期间合入的两项 AnyTLS 修复（[PR #115](https://github.com/Wuwan94/wanBoxForAndroid/pull/115) / [PR #116](https://github.com/Wuwan94/wanBoxForAndroid/pull/116)），全部内容都在本版里。以往每发一版预览只保留最新一个，旧预览的 Release 会被清理，想回看某次改动只能翻更新日志和旧 PR；这一次把自 `v3.0.17` 以来所有预览版的更新按主题写全在一处，在用 `v3.0.19-preview.4` 的只要升级到本版一次，就等于拿到了这期间的全部改动。

3.0.19 线已经用满每线 `preview.4` 的上限，按版本规则开新线 3.0.20。前两项 AnyTLS 修复此前是以**同标签重发**的方式进了 `v3.0.19-preview.4` 的现行资产，那样 tag 不动、`versionCode` 不变，已装旧包的设备收不到更新；本版换新版本号从 `main` 重新出版，tag 与二进制重新同源，已装 `v3.0.19-preview.4`（无论首发包还是两次重发包）都能正常覆盖升级。同期合入的三份文档（[PR #117](https://github.com/Wuwan94/wanBoxForAndroid/pull/117) preview.4 重发记录、[PR #118](https://github.com/Wuwan94/wanBoxForAndroid/pull/118) 上游同步评估、[PR #119](https://github.com/Wuwan94/wanBoxForAndroid/pull/119) 安全公告同类面排查）不改代码。

### 【一、智能选路：TCP 与 UDP 分开记账，回切只认真凭据】

这是 3.0.18 全线的主线，从 preview.1 一路修到 preview.4，再由 3.0.19-preview.4 补掉最后一处迟到观测的漏洞。

- **当前层级按网络各存一份**：以前一个策略组只共用一份「当前层级 + 回切门」状态，UDP 的结果会顶掉 TCP 的判断、反过来也一样；现在 TCP 和 UDP 各有一份当前层级与恢复门，故障转移只下移出问题的那张网，另一张网不受牵连（3.0.18-preview.1，[PR #77](https://github.com/Wuwan94/wanBoxForAndroid/pull/77)，附 11 条跨协议隔离回归）。
- **每轮探测按「成员 + 网络」分账**：一轮测速里 TCP 与 UDP 的观测分成两张表，两层恢复门只读自己那张；同一轮里缓存跳过的记「跳过」、预算到期或调用方取消的记「取消」，都不算健康轮次；嵌套的策略组按网络各汇总一份，单协议成员只对自己支持的网络产生观测（3.0.18-preview.2，[PR #80](https://github.com/Wuwan94/wanBoxForAndroid/pull/80)）。
- **UDP 探测改成真实往返回显**：以前只要能把 UDP 连接建起来就算成功——VMess / VLESS / Trojan / XUDP 都是「首次写入才真正建会话」，建起来什么也证明不了，回切门可能凭假证据提前切回。现在要发出一条探测包、收到目标原样回显才算「已验证」；首次写入失败、关联失败、回包内容不符记「失败」；静默丢弃、超时、空包记「未知」；没配端点或成员不支持 UDP 记「跳过」（零流量）；取消记「取消」。只有「已验证」推进回切计数，「失败」清零并盖戳，「未知」既不涨也不清零，TCP 成功也不再冒充 UDP 证据（3.0.18-preview.2，PR #80）。
- **探测目标只来自你自己配置**：UDP 回显端点不默认任何第三方服务、不额外发 DNS 查询；目标的服务器必须是 `IP:port`，探测载荷 ≤512 字节，单次超时 ≤5 秒且不超过整轮 5 秒预算；每个成员只发一条、只读一次、不重试，取消即关闭连接叫醒阻塞读（PR #80）。
- **新增「UDP 回显端点」设置**：设置 → 连接与核心 → UDP 回显端点，可以填自管的数字 IP 和端口，默认留空就是零流量；这个端点会传到外层和各子层级，但不会进入导出配置，也不会被一次性测速拿去用；不支持 UDP 回显的服务不能当端点（3.0.18-preview.3，[PR #83](https://github.com/Wuwan94/wanBoxForAndroid/pull/83)）。
- **只有 UDP 的上层成员不再借 TCP 的延迟**：这类成员凭有效回显证据进入候选，TCP 与 UDP 的历史互不替代；回切仍要求连续三轮新观测 + 60 秒无失败（3.0.18-preview.3，PR #83）。
- **降级即作废旧回切资格**：发生故障转移时，在同一把写锁里清零失败层并重盖失败戳、当前层级只往下移、更高层的连续健康计数全部作废，且只作用在被拨的那张网；同时把「门代次」加一，让迟到的观测失效（3.0.18-preview.2，PR #80）。
- **迟到的成功不再有副作用**：门代次变化之后，迟到的 TCP 探测或数据面拨号成功，不再覆盖历史的延迟记录，也不再清掉成员的连续失败计数，回切不会被一条过期的「成功」骗走（3.0.19-preview.4，[PR #104](https://github.com/Wuwan94/wanBoxForAndroid/pull/104)）。
- **UDP 回包按来源核对**：回包来源核对不了记「未知」，来源不符或内容不符记「失败」；截止判定先看是不是父级取消（记「取消」），再看是不是单探针超时（记「未知」），并且在关联、发送、读取之后各再核对一次（3.0.18-preview.4，[PR #86](https://github.com/Wuwan94/wanBoxForAndroid/pull/86)）。
- **迟到回包永远不算成功**：新增行为级用例——回包在探针结束之后才到、或者调用方已经取消，都只记「未知」、不推进回切门（3.0.18-preview.4，[PR #87](https://github.com/Wuwan94/wanBoxForAndroid/pull/87)）。
- **已明确失败的证据照常删除**：门代次在一轮内发生变化时，此前会把整轮 UDP 证据的更新（含删除）一起跳过，已明确失败的成员会一直保留旧的「已验证」；现在代次变化只拦「记成功」，失败删除照常落地（3.0.18-preview.4，[PR #84](https://github.com/Wuwan94/wanBoxForAndroid/pull/84)）。
- **组关掉就不再偷偷拨备用**：策略组一关，在途的尝试就会被取消（含 3 秒单步超时窗口），备用循环不再发起任何新拨号，迟到的连接统一关闭释放并返回统一错误（3.0.18-preview.2，PR #80）。
- **「导出 / 一次性测速排除探测端点」可测了**：这条判定从配置构建里抽成可测函数，补了四种组合的回归（3.0.18-preview.4，PR #84）。
- **没放宽的约束**：测速间隔 ≥10 秒、空闲超时 ≥30 分钟、切换时不中断现有连接、层级回切门（连续 3 轮健康 + 60 秒无失败）都没动；失败方向仍是 fail-closed——证据不足就停在当前层级，不回切。

### 【二、开机自启与 Root 设置：不再「开关点了没反应」】

- **开机自启开关终于生效**：这个开关此前在界面上没有绑定，永远按默认「关」下发给模块；现在会写回设置并立刻同步给 Root 模块（3.0.18-preview.1，[PR #78](https://github.com/Wuwan94/wanBoxForAndroid/pull/78)）。
- **「重启核心」和「回滚配置」有了入口**：重启带确认弹窗；回滚配置是模块保留的上一代配置自救通道，以前 App 侧白名单里没有 `config rollback`，点了会被拒，现在放行。两处都复用模块已有命令，模块侧零改动（3.0.18-preview.1，PR #78）。
- **顶栏显示模块版本**：仪表盘顶栏副标题跟随模块版本，热更新之后在主界面就能看到版本变化，不用再翻设置子页（3.0.18-preview.1，PR #78）。
- **自启改成「下发 → 回读确认 → 落库」闭环**：失败、超时、结果未知都重试一次，仍失败就保持原值、开关回滚显示并给中文提示；返回设置页会回读比对，不一致显示「待同步」；模块没在运行时不会被擅自拉起（3.0.18-preview.2，PR #80）。
- **连点按最后一次选择落地**：等待确认期间连续点开关，按最后一次点的值生效，旧回调不覆盖新选择（3.0.18-preview.3，PR #83）。
- **自启结果不再「粘住」**：结果改成可重放的状态发布，页面重开后会接续同步；界面呈现过一次就清掉，不会再出现「昨天切开关失败、今天进设置还显示同步失败」；失败时在页面还有效的情况下弹一次提示，而不是只留一句摘要（3.0.18-preview.4，PR #87）。
- **锁只保护该保护的东西**：登记锁只管序号和内存状态，数据库读写与系统开关调用全部移到锁外，点开关不会排在写库后面干等；落库抛异常时界面发布可见失败并保持原值，不会永远停在「同步中」（3.0.18-preview.4，PR #87）。
- **首次应用配置前也回读自启设置**：没有有效配置就不启动核心，配置损坏照旧不可用（3.0.18-preview.3，PR #83）。
- **`boot.json` 的状态不再含糊**：文件不存在 → 回落最近快照；格式损坏、空文件、缺字段、字段类型不对 → 「配置无效」；权限或读取错误 → 「配置不可读」。后几种不会再拿快照冒充生效值，坏文件也不改写不删除；重新切一次开关（原子重写）是唯一修复路径，重写前会记一条事件。设置页把「待同步」和「待处理」分开显示，后者给中文提示（3.0.18-preview.2 / preview.4，PR #80 / #86）。

### 【三、Root 模块有了网页控制台】

- **能做什么**：在 KernelSU / APatch 管理器里打开 wanBox 的固定页面，看模块运行状态与最近事件、启停模块、导出最近 40 条事件（3.0.19-preview.4，[PR #107](https://github.com/Wuwan94/wanBoxForAndroid/pull/107)）。
- **怎么连通**：页面只调用固定的 `wanboxctl` 白名单命令，不起本地 HTTP 服务、不加载外部脚本、严格 CSP，成功响应严格校验；事件视图与导出共用同一份「最近 40 条」过滤。
- **打包和权限都定死**：打包、合包、Go 安装器都用固定资源白名单，目录 0755、四项资源 0644；完全不含 WebUI 的旧模块清单照常兼容，只给一部分的会被拒绝。
- **KernelSU / APatch 桥接修好**：去掉裸的 `kernelsu` 导入，改走兼容的 `globalThis.ksu.exec` 加独立回调；回调、异常、超时都会清理回调名；没有兼容桥接时所有按钮一律禁用，不会假装成功（3.0.19-preview.4，[PR #112](https://github.com/Wuwan94/wanBoxForAndroid/pull/112)）。
- **门禁**：WebUI 有独立的 Node 测试（修桥接后 13 项），源码与打包后的 WebUI 各有一道 1 MiB 体积门禁，Root 模块总包与「APK + 模块」总量门禁不变。
- **边界**：页面与桥接只在单测和模拟器上验证过，Magisk / KernelSU / APatch 真机尚未实测。

### 【四、AnyTLS 客户端证书（mTLS）：私钥只留在本机】

- **能填什么**：节点设置里可以贴客户端证书与私钥 PEM，写进 sing-box 出站 TLS 的客户端证书与私钥字段；两项留空就还是原来的单向 TLS（3.0.19-preview.4，[PR #111](https://github.com/Wuwan94/wanBoxForAndroid/pull/111)）。
- **旧配置照常读**：新字段走 v2 持久化，旧版配置照常读取；HWID、Mieru 与策略组行为未改。
- **不会外泄**：`anytls://` 与通用分享链接都不携带证书和私钥（只清理导出副本，不动本地配置）；诊断包会把配置与 Root 快照里的 AnyTLS 私钥 / 证书替换成 `***`；设置页只显示私钥摘要，不回显内容。
- **从 sing-box JSON 导入不再丢证书**：导入时会读客户端证书与私钥（字符串或数组两种写法都支持），保存后完整写回出站配置；没有客户端证书的节点，输出与原来一样（本版，[PR #115](https://github.com/Wuwan94/wanBoxForAndroid/pull/115)）。
- **uTLS 指纹真的生效**：以前生成配置时指纹值被同名局部变量覆盖，`utls.fingerprint` 一直是空的，sing-box 一律按 chrome 处理；现在选 firefox 等会如实写入，REALITY 没选指纹时回落 chrome（本版，PR #115）。
- **PEM 不会被写坏**：证书类数组里混进 `null` 或数字时，以前会被当成 `"null"` / `"1"` 拼进证书；现在这类值和空串、空数组一样按「未配置」处理，Windows 换行统一成 `\n`、只去掉首尾空白、正文不动，各协议 TLS 字段导入都补了回归（本版，[PR #116](https://github.com/Wuwan94/wanBoxForAndroid/pull/116)）。
- **边界**：没有真实 mTLS 服务端可连，只在 JVM 单测与 CI 模拟器里验证字段映射、导入往返与脱敏；VMess / VLESS / Trojan 从 JSON 导入时同样不读客户端证书，留待后续。

### 【五、XHTTP：wanBox 自己也能当服务端，长上传不再断流】

- **新增 XHTTP 服务端入站**：官方 sing-box 不支持 XHTTP 传输，项目此前已在扩展层补齐客户端，这一版补齐服务端——wanBox 可以作为 XHTTP 服务端接受连接。支持 `auto`、`packet-up`、`stream-up`、`stream-one` 四种模式，HTTP/1.1、HTTP/2 与 h2c、TLS，会话与上行数据的 path / query / header / cookie 放置兼容，带 X-Padding 校验与 CORS 预检；传输不是 XHTTP 时原样交回官方实现，普通 VLESS（含 ws 等传输）行为不倒退。经现有节点自定义 JSON（`transport.type = "xhttp"`）启用，不新增配置向导，App 界面与数据模型未改，官方内核与 `SINGBOX_VERSION` 未动（3.0.19-preview.1，[PR #90](https://github.com/Wuwan94/wanBoxForAndroid/pull/90)）。
- **服务端不裸奔**：会话数、单会话与全局缓冲字节、并发上行数、未连接会话超时都有上限，异常请求按状态码拒绝；上传用滚动读取超时，连接超时或关闭后及时回收资源，慢上传不会堵住其他会话（3.0.19-preview.1 / preview.2，PR #90 / [PR #94](https://github.com/Wuwan94/wanBoxForAndroid/pull/94)）。
- **修掉两处客户端老问题**：出站构造请求时先整体替换 Header 再写会话头，会把会话头冲掉；上传字节计数在缓冲区被消费之后才读长度、一直偏小。两处都改了（3.0.19-preview.1，PR #90）。
- **连真 Xray 服务端不再 400**：`stream-one` 模式的客户端会话 ID 让 Xray 把请求识别成 `stream-up` 并拒绝，现已修好；`auto` / `packet-up` / `stream-up` 行为不变（3.0.19-preview.3，[PR #96](https://github.com/Wuwan94/wanBoxForAndroid/pull/96)）。
- **修掉续接与并发竞态**：`stream-up` 续接与 `stream-one` 并发竞态；会话来源校验、重复下行与请求校验收紧；乱序缓冲溢出时结束会话并释放积压数据（3.0.19-preview.2，PR #94）。
- **客户端不再无限等**：连接有了总时长、读、写三种期限，超时按方向失败关闭（读超时关掉这条会话，写超时关掉上传方向）；不做不安全的自动重连续传，也不假装成功；单个请求取消不会把共享的 HTTP/2 连接一起判死（3.0.19-preview.4，[PR #102](https://github.com/Wuwan94/wanBoxForAndroid/pull/102)）。
- **服务端保活更规整**：`stream-up` 成功响应按 20–80 秒周期发 padding 保活，响应写期限与上传读空闲期限分开算，填 `-1` 可以关闭（3.0.19-preview.4，[PR #103](https://github.com/Wuwan94/wanBoxForAndroid/pull/103)）。
- **长上传不再被保活挤断**：服务端开保活时，成功响应以前读到 32 KiB 就被重置，长上传会断在中途；现在会持续排空到响应结束再收尾，上传能完整送达。新增真实 TLS / HTTP/2 回环回归盯这条路径：旧代码 3 次全红、修复后 3 次全绿（3.0.19-preview.4，[PR #108](https://github.com/Wuwan94/wanBoxForAndroid/pull/108)）。
- **日常日志不再刷屏**：服务端拒绝异常请求的提示从信息级降到调试级（3.0.19-preview.3，PR #96）。
- **互通门槛抬成硬门禁**：每次提交都会用官方 Xray v26.11.9 跑 12 项真实互通（双向四模式 + `auto` 双向 TLS + header/cookie 补测），下载包按 SHA-256 校验，跳过或少跑直接判失败；同一份校验脚本同时用于 PR、预览发布和正式发布，命中构建缓存也不跳过（3.0.19-preview.3 / preview.4，[PR #98](https://github.com/Wuwan94/wanBoxForAndroid/pull/98) / [PR #101](https://github.com/Wuwan94/wanBoxForAndroid/pull/101)）。
- **示例与文档跟上**：更新 XHTTP 自定义 JSON 示例，CI 纳入 XHTTP 回归与 Go 竞态检测（3.0.19-preview.2，PR #94）。

### 【六、节点标签与手动新建：XHTTP / Reality 一眼分清】

- **看得清**：节点列表、选择列表、详情页和通知标题按节点实际字段显示 `VLESS + XHTTP`、`VLESS · Reality` 或 `VLESS + XHTTP · Reality`，不再统一写 `VLESS`；普通协议的通知文案保持原样，特殊标签只替换节点名并保留原有分组前缀（3.0.19-preview.4，[PR #109](https://github.com/Wuwan94/wanBoxForAndroid/pull/109)）。
- **加得快**：「+ → 手动设置」新增「VLESS + XHTTP」，点进去就是预置好 XHTTP 传输的表单，不用再手改类型；普通 VLESS 仍默认 TCP（3.0.19-preview.4，[PR #110](https://github.com/Wuwan94/wanBoxForAndroid/pull/110)）。
- **没变形**：分享链接、核心配置、策略组拨号、HWID 外发都没有改动，也没有数据库迁移。

### 【七、界面：全面屏避让与预测性返回】

- 非浮层页面统一做 edge-to-edge 避让，状态栏、导航栏、挖孔与横屏刘海不再压住内容；浮层窗口保持原有行为，透明节点选择、数据库恢复、落地 IP 底部面板各有明确策略（3.0.19-preview.4，[PR #105](https://github.com/Wuwan94/wanBoxForAndroid/pull/105)）。
- 返回键改走 AndroidX 分发，支持预测性返回动画，各页面原有的返回值不变。
- **边界**：Android 14/15 的手势、三键导航、输入法与横屏实机行为未验证，单测只覆盖尺寸计算与接线。

### 【八、部分 OEM 的 Google 联网拦截可按需修掉（待真机确认）】

- **只删该删的那一条**：Root 模块在开机就绪 60 秒后（或成功热激活、锁释放后）做一次严格白名单预检——红魔 / 努比亚 / 中兴只读取并定点删除 `zte_fw_gms` 里目标 Google UID 的单 UID 拦截规则，IPv4 / IPv6 全量读取预检通过后才动手；一加 / OPPO ColorOS 16 要求 user 0、厂商策略门禁与所有已存在的 pin / program / map 结构全部匹配，只删目标 UID 且值为 `1` 的 map 条目，保留 BPF 程序与 map（3.0.19-preview.4，[PR #106](https://github.com/Wuwan94/wanBoxForAndroid/pull/106)）。
- **认不出来就整体跳过**：固件、UID 共享、策略或 BPF 布局任何一项对不上都不动；不 flush 链、不碰 DNS 与共享规则、不生成也不发送 HWID，逐项删除前复核模块状态。
- **边界**：这是可选的一次性尽力修复，不改变核心自启、App 界面与用户配置；Root 真机矩阵（红魔 / 努比亚 / 中兴、一加 / OPPO ColorOS 16）都还没测，已经删掉的拦截项也不会因为回退代码自动重建。

### 【九、文档与安全排查（不改代码）】

- **preview.4 同标签重发的记录补齐**：preview.4 首发之后又有两次同标签修复重发，仓库文档把首发与两次重发的时间、run 号、现行四件套数据分开记清了（[PR #117](https://github.com/Wuwan94/wanBoxForAndroid/pull/117)）。
- **上游同步评估逐项结论**：把 NetProxy v8.7.1–v8.7.3 与 OwnBox `v26.11.24.6-preview` 的「借鉴 / 不借鉴 / 不适用」逐项写进 `docs/upstream-sync-2026-11.zh-CN.md`，只写文档、不改结论口径（[PR #118](https://github.com/Wuwan94/wanBoxForAndroid/pull/118)）。
- **安全公告同类面排查**：对照 Husi 的「不可信备份可恢复并执行自定义插件」公告（GHSA-q6pm-rh93-9hr5），排查了备份导入、插件 / 外部程序、订阅与分享链接三个面，结论是三个面在 wanBox 都不可达，没有改代码（[PR #119](https://github.com/Wuwan94/wanBoxForAndroid/pull/119)）。

### 【升级与回退】

- APK `versionCode` `10219 → 10221`、Root 模块码 `2043 → 2044`；同一 Release 的管理器 APK 与 Root 模块请成套安装，不要跨版本混用。
- 已装 `v3.0.19-preview.4`（首发包或两次重发包都行）可以直接覆盖升级；从更早的 3.0.18 / 3.0.19 预览升上来同理，一路覆盖即可，中间版本不用逐个装。
- 回退用正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17)（模块码 `2035`）成套重装；`v3.0.19-preview.3` 及更早预览的 Release/tag 已按单预览策略清理（现为 404），不要指向它们。

### 【验证边界】

- **自动化覆盖**：Go 单测与 `-race`、`go vet`、Python 工具与文档门禁、JVM 单测、PR CI 的模拟器 instrumentation、官方 Xray v26.11.9 的 12 项互通、Root 模块的 Node / Python 测试与体积门禁。
- **仍未验证**：Root 真机矩阵（Magisk / KernelSU / APatch）、KernelSU / APatch 里真实打开 WebUI、真实 mTLS AnyTLS 服务端握手、真实 Xray 客户端连本项目的 XHTTP 服务端、OEM Google 联网兼容真机、Android 14/15 手势与横屏实机表现。CI 自标 `runtime_regression: not_verified`，自动化结果不等于真机签核，本版不晋升正式版。

---

## 历史归档

### v3.0.19-preview.4 已发布说明

> **同标签修复重发（第二次，Fri 2026-11-27 6:18 PM CST）**：本页四件套已由 [PR #116](https://github.com/Wuwan94/wanBoxForAndroid/pull/116) 合并后的 `main` 提交 `303aa996567969ce0801ce5c7c30740f5404ae31` 重新构建并替换（`Preview Build` [run 45978054821](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/45978054821)，含模拟器冷启动门禁；替换 [prepare 45980842300](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/45980842300) / [execute 45980952801](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/45980952801)）。第一次重发（Fri 2026-11-27 3:38 PM CST，源提交 `d4ae341`，[PR #115](https://github.com/Wuwan94/wanBoxForAndroid/pull/115)）的两项修复全部保留。版本号、`versionCode`、签名证书与 tag 指向均未变。
>
> - **修复 P1：AnyTLS 从 sing-box JSON 导入时丢失客户端证书 / 私钥。** 导入解析现在读取 `client_certificate` / `client_key`（字符串或数组两种写法），经持久化后完整写回出站配置；没有客户端证书的节点输出不变。
> - **修复：AnyTLS 选择的 uTLS 指纹没有生效。** 生成配置时指纹值被同名局部变量吞掉，`utls.fingerprint` 恒为空，sing-box 一律按 chrome 处理；现在选 firefox 等会如实写入，REALITY 未选指纹时回落 chrome。
> - **修复：导入证书时混入非法数组元素会写坏 PEM。** sing-box JSON 的 `certificate` / `client_certificate` / `client_key` / `ech.config` 数组里若夹着 `null` 或数字，原先会被当成 `"null"` / `"1"` 行拼进证书；现在这类值与空串、空数组一样按未配置处理，Windows 换行统一为 `\n`、只去掉首尾空白，正文不动。各协议 TLS 字段导入加了回归测试。
> - **怎么更新**：已装旧 preview.4 的不会收到自动更新，请下载本页同签名 APK 覆盖安装，Root 模块同样换成本页的包，成套安装。
> - **边界**：JVM 单测 774 项全过（oss / preview 两套变体），PR CI 的模拟器 instrumentation 通过，未在真机与真实 mTLS AnyTLS 服务端上验证；VMess / VLESS / Trojan 等协议从 JSON 导入时同样不读客户端证书，留待后续。

**状态：已发布的稳定性预览版。** 正式版仍为 `v3.0.17`；官方 sing-box 内核与 `SINGBOX_VERSION` 未改。

本版由 [升版 PR #113](https://github.com/Wuwan94/wanBoxForAndroid/pull/113) 发布（合并提交 `cfb1e25c4b02f261dee17baa77a4fa4a1fa60565`），`Preview Build` [run 45890957065](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/45890957065) 四个作业全绿，2026-11-26T20:54:52Z 首发上架；首发时 tag、`targetCommitish`、APK 内 `version-control-info` 与发布 job 的 `APPROVED_SHA` 同为 `cfb1e25c4b02f261dee17baa77a4fa4a1fa60565`。两次同标签重发只替换资产、不移动 tag：现行 APK 内 `version-control-info` 为 `303aa996567969ce0801ce5c7c30740f5404ae31`，tag 与 `targetCommitish` 仍为 `cfb1e25c`；Release API 的 `published_at` 随第二次重发更新为 2026-11-27T10:18:51Z。内容是 `v3.0.19-preview.3` 之后合入 `main` 的十二条改动：Root 模块第一次有了能用的网页控制台，AnyTLS 支持客户端证书，VLESS 标签把手动入口分清 XHTTP 与 Reality，XHTTP 长上传在服务端保活下不再断流，界面适配全面屏与预测性返回，另有两处稳定性修复。正式线 `3.0.17 / 2035` 与 `SINGBOX_VERSION` 均未变。

#### 【Root 模块有了网页控制台：在 KernelSU / APatch 管理器里直接看状态、启停、导事件】

- **能做什么**：在 KernelSU / APatch 管理器里打开 wanBox 的固定页面，看模块运行状态与最近事件，启停模块，导出最近 40 条事件。
- **怎么连通**：页面只调用固定的 `wanboxctl` 白名单命令，不起本地 HTTP 服务、不加载外部脚本、严格 CSP；重新适配了 KernelSU（以及兼容其 API 的 APatch）的注入桥接，回调、异常和超时都会清理。没有兼容桥接时所有按钮一律禁用，不会假装成功。
- **边界**：页面与桥接只在单测和模拟器上验证过，Magisk / KernelSU / APatch 真机尚未实测。

#### 【AnyTLS 支持客户端证书（mTLS），私钥只留在本机】

- **能填什么**：节点设置里可以贴客户端证书与私钥 PEM，写入 sing-box 出站 TLS 的 `client_certificate` / `client_key`；两项留空就还是原来的单向 TLS。
- **不会外泄**：`anytls://` 与通用分享链接都不携带证书和私钥，只清理导出副本、不动本地配置；诊断包会把配置与 Root 快照里的 AnyTLS 私钥/证书替换成 `***`；设置页只显示私钥摘要，不回显内容。
- **兼容**：旧版配置照常读取，新字段走 v2 持久化；HWID、Mieru 与策略组行为未改。
- **导入**：首发包从 sing-box JSON 导入时会丢掉证书与私钥，已在本页重发包修复（见顶部）。
- **边界**：没有真实 mTLS 服务端可连，只在单测与 JVM 测试里验证字段映射、导入往返与脱敏。

#### 【VLESS 标签分清 XHTTP 与 Reality，手动新建多了直达入口】

- **看得清**：节点列表、选择列表、详情页和通知标题按节点实际字段显示 `VLESS + XHTTP`、`VLESS · Reality` 或 `VLESS + XHTTP · Reality`，不再统一写 `VLESS`；普通协议的通知文案保持原样。
- **加得快**：「+ → 手动设置」新增「VLESS + XHTTP」，点进去就是预置好 XHTTP 传输的表单，不用再手改类型；普通 VLESS 仍默认 TCP。
- **没变形**：分享链接、核心配置、策略组拨号与 HWID 外发都没有改动。

#### 【XHTTP 更扛得住：服务端保活不再把上传挤断，客户端卡死会按时超时】

- **修好断流**：服务端开启周期保活（padding）时，成功响应原来读到 32 KiB 就被重置，长上传会断在中途；现在会持续排空到响应结束再收尾，上传能完整送达。新加的真实 TLS/HTTP2 回环回归（旧基线 3/3 红、修复版 3/3 绿）盯住这条路径。
- **不再无限等**：客户端连接有了总时长、读、写三种期限，超时按方向失败关闭，不硬撑也不假装成功；单个请求取消不会把共享的 HTTP/2 连接一起判死。
- **服务端保活更规整**：`stream-up` 成功响应按 20–80 秒周期发 padding，响应写期限与上传读空闲期限分开计算，`-1` 可关闭。
- **互通门槛抬高**：官方 Xray v26.11.9 的 12 项互通已成为 PR、预览发布与正式发布的硬门禁，跳过或少跑直接失败。

#### 【界面适配全面屏与预测性返回】

- 非浮层页面统一做 edge-to-edge 避让，状态栏、导航栏、挖孔与横屏刘海不再压住内容；返回键改走 AndroidX 分发，支持预测性返回动画，各页面原有返回值不变。
- **边界**：Android 14/15 的手势、三键导航、输入法与横屏实机行为未验证，单测只覆盖尺寸计算与接线。

#### 【两处稳定性修复】

- **回切不再被迟到的 TCP 成功骗到**：TCP 恢复门代次变化后，旧的探测或数据面拨号成功不再覆盖 `URLTestHistory`，也不再清掉成员的连续失败计数。
- **部分 OEM 的 Google 拦截可按需修掉（待真机确认）**：Root 模块在开机就绪 60 秒后（或成功热激活后）做一次严格白名单预检，只针对红魔/努比亚/中兴 `zte_fw_gms` 里目标 Google UID 的单 UID 拦截规则，以及一加/OPPO ColorOS 16 上值为 `1` 的目标 UID map 条目做定点删除；固件、UID 共享、策略或 BPF 布局任何一项对不上就整体跳过，不 flush 链、不碰 DNS 与共享规则，也不生成或发送 HWID。
- **升级与回退**：APK `versionCode` `10213 → 10219`、Root 模块码 `2042 → 2043`；同一 Release 的管理器 APK 与 Root 模块请成套安装，不要跨版本混用；回退用正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17)（模块码 `2035`）。
- **总边界**：Root 真机矩阵（Magisk / KernelSU / APatch）、WebUI 实机、真实 mTLS 服务端与 OEM Google 兼容都未实测，本版不晋升正式版。

### 四件套（重发后 GitHub Release API 回读）

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.19-preview.4-arm64-v8a-release.apk` | 6382851 | `002407d904023fb6463c5b7e0561d534fdb8c675cf4f60917f944ff3c97b9f72` |
| `wanbox-root-module-arm64-v8a.zip` | 18333508 | `e88d46a3a193567283d59360924d9b5c150331770b994843927ed27c8053bc88` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23991715 | `63d4da6012e2fb371512a852af1fa05f73c00a4061473be65084b85e9b8a66b6` |
| `SHA256SUMS` | 333 | `e203b0214089b509d1f17ec364d1e8c0e9e000a345ed2bc0911c4e8e3078df79` |

APK + 普通 Root 模块 `24716359` B（23.57 MiB），低于 26 MiB 上限 `27262976` B（余量 `2546617` B，2.43 MiB）；合一包 `23991715` B（22.88 MiB）。前两版包的副本保留在各次替换流程的回滚产物中（90 天）。APK 内 `lib/` 条目为 0，`versionCode=10219` / `versionName=3.0.19-preview.4`，证书 SHA-256 `80ECEE1C42D861D59CDC460447335FC4BFDF80103D6225A17C1BDF83A33521F6`（与 `v3.0.17` 同源）；模块 `module.prop` 为 `3.0.19-preview.4` / `2043`，含 4 个 `webroot/` 文件，两个 ZIP 均通过 `python3 -m zipfile -t`。本版发布后，`v3.0.19-preview.3` 的 Release 与 tag-ref API 均返回 404（已按单预览策略清理），正式版 `v3.0.17`、`v3.0.14`、`v3.0.11` 保留。Root 真机矩阵仍未执行。

### v3.0.19-preview.3 已发布说明

**状态：已发布的稳定性预览版。** 正式版仍为 `v3.0.17`；官方 sing-box 内核与 `SINGBOX_VERSION` 未改。

本版收录 [PR #96](https://github.com/Wuwan94/wanBoxForAndroid/pull/96) 的 XHTTP `stream-one` 客户端修复与 12 项真实 Xray 互通矩阵，以及 [PR #98](https://github.com/Wuwan94/wanBoxForAndroid/pull/98) 把该矩阵接进 CI 门禁，由 [升版 PR #99](https://github.com/Wuwan94/wanBoxForAndroid/pull/99) 发布。`Preview Build` [run 45573982693](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/45573982693) 于 2026-11-25T08:32:21Z 上架；tag、`targetCommitish`、APK 内 `version-control-info` 与发布 job 的 `APPROVED_SHA` 同为 `7d193fe45aa3060c9e03aa0befbc4f349f6a712d`。

#### 【修好 XHTTP stream-one 互通，互通测试从此每次提交都真跑】

- **修好连 Xray 服务端时的 400**：以前用 `stream-one` 模式连官方 Xray 服务端会被拒（`400 Bad Request`），现在能正常握手；`auto` / `packet-up` / `stream-up` 行为不变。
- **日志更安静**：服务端拒绝异常请求的提示从信息级降到调试级，日常日志不再被这类拒绝刷屏。
- **互通测试真的跑起来了**：每次提交都会用官方 Xray v26.11.9 跑 12 项真实互通测试（双向四模式 + `auto` 双向 TLS + header/cookie 补测），下载包按 SHA-256 `b651f7e1…ed95f82` 校验，跳过或少跑直接判失败。
- **升级与回退**：APK `versionCode` `10207 → 10213`、Root 模块码 `2041 → 2042`；同一 Release 的管理器 APK 与 Root 模块请成套安装，不要跨版本混用；回退用正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17)（模块码 `2035`）。
- **边界**：互通矩阵是本机 loopback + 模拟器 CI，不等同于真机验证；Root 真机矩阵仍未执行，本版不晋升正式版。

### 四件套（GitHub Release API 回读）

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.19-preview.3-arm64-v8a-release.apk` | 6381543 | `4c8ef03eec5534d6b21e5964cb333cfc4799621db58043a394f5199c65aaabfc` |
| `wanbox-root-module-arm64-v8a.zip` | 18308150 | `2665ba1de7ce390fbbe752a0a5edcd5208b022ed32b187ff947766db8f2640dc` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23965116 | `595f35409f91896261babddf96bf112eceb33ce7e5bf36ff79186913d84f0c37` |
| `SHA256SUMS` | 333 | `eff1f1d491ee8932f527e90c2af50f76151ea19f14d2cf169a046d1b5484d215` |

APK + 普通 Root 模块 `24689693` B（23.55 MiB），低于 26 MiB 上限 `27262976` B；合一包 `23965116` B（22.85 MiB）；APK 内 `lib/` 条目为 0，模块 `module.prop` 为 `3.0.19-preview.3` / `2042`。preview.3 发布后，preview.2 的 Release/tag 已清理（Release API 与 tag-ref API 均返回 404）；本页保留其历史说明与快照。Root 真机矩阵仍未执行。

### v3.0.19-preview.2 已发布说明

**状态：已发布的稳定性预览版。** 正式版仍为 `v3.0.17`；官方 sing-box 内核与 `SINGBOX_VERSION` 未改。

本版收录 [PR #94](https://github.com/Wuwan94/wanBoxForAndroid/pull/94) 的 VLESS XHTTP 服务端加固，由 [升版 PR #95](https://github.com/Wuwan94/wanBoxForAndroid/pull/95) 发布。`Preview Build` [run 45426378108（#63）](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/45426378108) 于 2026-11-24T15:55:00Z 上架；tag 与资产提交同为 `9f4299e50aa88bc6f385daf4663828e07528036d`。

### 本版修复

- 增加上传滚动读取超时与分层资源配额；连接超时或关闭后及时回收资源，避免慢上传阻塞其他会话。
- 修复 `stream-up` 续接和 `stream-one` 并发竞态。
- 收紧会话来源校验、重复下行及请求校验；乱序缓冲溢出时结束会话并释放积压数据。
- 更新 XHTTP 自定义 JSON 示例，并在 CI 纳入 XHTTP 回归与 Go 竞态检测。

### 升级与验证范围

- 发布元数据：APK `versionCode=10207`，Root 模块码 `2041`。管理器 APK 与 Root 模块必须取自同一 Release，不跨版混装。
- [PR #94 Android 验证](https://github.com/Wuwan94/wanBoxForAndroid/actions/runs/45399072399) 已通过；新增请求级回归覆盖 HTTP/1.1。HTTP/2/真机矩阵不是该 CI 记录所覆盖的内容。
- [Xray 互通记录](docs/testing/xhttp-xray-interop.md) 的四模式、TLS `auto` 双向及 Xray→wanBox header/cookie 用例，均在后续本地修复分支上执行；`stream-one` 客户端修复不在已发布 preview.2 资产中，也不代表真机验证。
- GitHub Release API 回读的四件套：

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `wanBoxForAndroid-3.0.19-preview.2-arm64-v8a-release.apk` | 6381543 | `c9884dcc06a38fb620ba0f6684ba545730f07987de5b2701306e83373c6cef1e` |
| `wanbox-root-module-arm64-v8a.zip` | 18308156 | `e070088063692787f184068aec32082468c12a317f4b573ad38cc10bf0f059c6` |
| `wanbox-root-module-with-manager-arm64-v8a.zip` | 23965220 | `8fcaa9d3f68186daedb97d9a514fdfc52235d535c6e76a008340129e184c7d48` |
| `SHA256SUMS` | 333 | `52eefd6f5a091193c691fee2187370760681d45bc6284091024542251d85abc2` |

APK + 普通 Root 模块合计 `24689699` B（23.55 MiB），低于 26 MiB 上限 `27262976` B；合一包为 22.86 MiB。preview.2 发布后，preview.1 的 Release/tag 已清理（Release API 与 tag-ref API 均返回 404）；本页仍保留其历史说明与快照。Root 真机矩阵仍未执行。

### v3.0.19-preview.1 已发布说明

**状态：已发布的稳定性预览版。**

本版收口 `v3.0.18-preview.4` 发布后合入 `main` 的一项新功能：为 VLESS 新增 XHTTP（SplitHTTP）**服务端入站**（PR #90，合并提交 `f3d8a67a443b2c450ba404683d97ef51f8b26d9f`，7 个文件 +1820/−4，`Verify Android Build` run `45251550225` 在 head `35c9256a` 全绿）。`3.0.18` 预览线已满每线 `preview.4` 的上限，本版按版本规则开新线 `3.0.19`。正式线 `3.0.17 / 2035`（APK `10175`）与 `SINGBOX_VERSION=v1.15.0-alpha.13-reF1nd` 均未变；预览元数据为 `3.0.19-preview.1 / 2040`，派生 APK `versionCode=10201`、Root 模块码 `2040`。`Preview Build` run `45338292530` 于 2026-11-24T07:17:56Z 发布本版（轻量 tag 指向升版 PR #91 合并提交 `94a9c9b895ccaf757119abdada3de4153e05aad2`，发布 job 断言 `APPROVED_SHA=94a9c9b` 并回读 tag；前两次尝试的冷启动测量作业因 Maven Central 429 限流失败，第 3 次尝试四作业全绿）；发布后 `prune_prereleases.py` 已清理 `v3.0.18-preview.4`，正式版 `v3.0.17`、`v3.0.14`、`v3.0.11` 保留。

### ✦ 与上一版对比

| 项目 | v3.0.18-preview.4 | v3.0.19-preview.1 |
|---|---|---|
| 内容 | UDP 回包按来源校验、截止判定先认取消；门代次变化仍删失败证据；自启结果可重放且只消费一次、锁外做 Room/IPC；UDP 迟到回包用例 | 新增 VLESS XHTTP 服务端入站（经自定义 JSON 配置启用）；顺带修复 XHTTP 客户端请求头顺序与上传字节计数 |
| APK `versionCode` | 10199 | 10201 |
| Root 模块码 | 2039 | 2040 |

URLTest 稳定性约束（检测间隔 ≥ 10 秒、空闲超时 ≥ 30 分钟、切换时不中断现有连接）、`default` 保真、层级回切门（连续 3 轮健康 + 60 秒无失败）本版均未放宽；失败方向仍是 fail-closed——证据不足时停留在当前层级，不回切。

### ✦ 本版更新

### 1. VLESS XHTTP 服务端入站（PR #90）

- **背景**：官方 sing-box 不支持 `xhttp` 传输；本项目此前已在 libcore 扩展层补齐 XHTTP **出站**（客户端），本版补齐**入站**（服务端），使 wanBox 可以作为 XHTTP 服务端接受连接。
- **改法**：libcore 新增 VLESS 入站注册——传输不是 XHTTP 时原样委托官方 sing-box 实现，普通 VLESS（含 ws 等传输）行为不倒退；XHTTP 时走自有服务端传输：支持 `auto`、`packet-up`、`stream-up`、`stream-one` 四种模式，HTTP/1.1、HTTP/2 与 h2c、TLS，session/seq 与上行数据的 path/query/header/cookie 放置兼容，X-Padding 校验与 CORS 预检。服务端带资源上限（会话数、单会话与全局缓冲字节、并发上行数、未连接会话超时），异常请求按状态码拒绝，不裸奔监听。
- **接入方式**：经现有节点自定义 JSON 配置路径启用（`transport.type = "xhttp"`），不新增服务器配置向导，App 界面与数据模型未改。官方 sing-box 内核源码与 `SINGBOX_VERSION` 未改，扩展只在 libcore 层。
- **证据**：新增 `libcore/vless_xhttp_inbound_test.go` 端到端往返（四种模式、TLS+HTTP/2、header/cookie 放置、GET 上行）与普通 VLESS / WebSocket 入站回归；`libcore/protocol/vless/xhttp/server_test.go` 覆盖会话 ID 与请求头校验、placement 校验、客户端/服务端填充互通、缓冲上限与关闭释放。PR 的 `Verify Android Build` run `45251550225` 全绿。

### 2. XHTTP 客户端两处既有问题顺带修复（PR #90）

- **请求头顺序**：出站构造请求时先整体替换 Header 再写 session/seq 头的旧顺序，会把会话头冲掉；改为先取配置 Header 再写会话头。
- **上传字节计数**：`uploadWriter` 在缓冲区被消费后才读长度，计数偏小；改为写前先记长度。

### 3. 版本与文档口径

`nb4a.properties` 预览线升为 `3.0.19-preview.1` / `2040`（新线首个预览）；正式线 `3.0.17 / 2035` 与 sing-box pin 未变。当前版本文档一致性检查改为「已发布 preview.4 + 未发布 preview.1 候选」的双态口径。

### ✦ 资产与安装

- **运行要求**：Android 12+（API 31+）、arm64-v8a、Magisk / KernelSU / APatch 等 Root 环境。
- **四件套（API 回读）**：APK `6381543` B / `e91b4a81…196507`、普通 Root ZIP `18295938` B / `95a2ec5f…b920ef`、合一 ZIP `23954760` B / `0b8dd917…88fd47`、`SHA256SUMS` `333` B / `a1c22e05…e1857b`；APK + 普通模块 `24677481` B（23.53 MiB）→ 26 MiB 门禁 PASS。
- **成套同版**：管理器 APK 与 Root 模块必须来自同一 Release；严禁跨 Release 混用。
- **回退**：换回正式版 [v3.0.17](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17) 的 APK 与配套模块（模块码 `2035`）成套重装。
- **升级**：APK 由 `10199` 升到 `10201`、模块由 `2039` 升到 `2040`，严格递增且同签名，可直接覆盖安装。

### 🛡️ 验证与边界

- **PR 门禁**：PR #90 的 `Verify Android Build` run `45251550225`（head `35c9256a`）全绿，含 `go test -race`、`go vet`、Python 工具 125 项、`rootmodule` 测试与文档门禁、JVM 单测与插桩；升版 PR #91 的 Verify run `45328063751`（head `c02f04f`）首跑红于 3 个组编辑器插桩测试、失败作业重跑后全绿（抖动）。
- **新增回归**：`vless_xhttp_inbound_test.go` 往返用例（四模式 + TLS/HTTP2 + header/cookie 放置 + GET 上行 + 普通 VLESS 与 WebSocket 回归）；`server_test.go` 校验与资源上限用例（会话 ID、请求头、placement、填充互通、缓冲上限与关闭释放、OPTIONS 预检）。
- **未验证边界**：未执行 Magisk / KernelSU / APatch 真机矩阵（CI 自标 `runtime_regression: not_verified`）；XHTTP 服务端与真实 Xray 客户端的端到端互通未验证（自动化为本项目客户端↔服务端往返）；服务端入站的 OpenSpec 变更已由 PR #92 补齐并合入主规范（`support-xhttp-inbound`，合并提交 `12f657478ef3a6caf082211d415e215f23fe1d31`）。任何自动化结果都不代替真机验证，本版不晋升正式版。

### v3.0.18-preview.4 已发布说明

**状态：已发布的稳定性预览版。**

本版收口 `v3.0.18-preview.3` 发布后合入的三批复审修复（PR #84，合并提交 `00cfeedddc07249cccd8a8344eb1bf7df97d75dc`，5 个文件 +71/−3；PR #86，合并提交 `2add76212e54c54b6d4dda02b1077ab373352e24`，12 个文件 +535/−147；PR #87，合并提交 `f0f80b92d597a334440c25171648b19b76573b59`，11 个文件 +534/−45）。预览元数据 `3.0.18-preview.4 / 2039`，派生 APK `versionCode=10199`、Root 模块码 `2039`。`Preview Build` run `45175762344` 四作业全绿，2026-11-23T12:55:32Z 上架（prerelease，发布 job 断言 `APPROVED_SHA=a8cec2fa` 并回读 tag）；12:55:40Z 由 `prune_prereleases.py` 清理 `v3.0.18-preview.3`，正式版 `v3.0.17`、`v3.0.14`、`v3.0.11` 保留。内容：UDP 回包按来源校验、截止判定先认取消；门代次变化仍删失败证据；导出/测速排除探测端点可测化；自启结果可重放且只消费一次、锁外做 Room/IPC；UDP 迟到回包用例。四件套：APK `6381547` B / `2856c050…1424d5`、普通 Root ZIP `18151369` B / `d5a549c3…f4705b`、合一 ZIP `23807498` B / `5bc8c2fb…b39239`、`SHA256SUMS` `333` B，APK + 普通模块 `24532916` 字节（23.40 MiB）→ `size_budget --enforce-limit` PASS。本版自身的 Release/tag 将在 `v3.0.19-preview.1` 发布时按单预览策略清理。

### v3.0.18-preview.3 已发布说明

**状态：已发布的稳定性预览版。**

本版收口 `v3.0.18-preview.2` 复审提出的四项缺陷（PR #83，合并提交 `8881dd1c59481b3d23ab3766fb21e3b651fa1d65`，25 个文件 +332/−78，`Verify Android Build` run `44879210932` 在 head `dbdbf0b` 全绿）。预览元数据 `3.0.18-preview.3 / 2038`，派生 APK `versionCode=10193`、Root 模块码 `2038`。`Preview Build` run `44882104257` 四作业全绿，2026-11-21T08:13:01Z 上架（prerelease，发布 job 断言 `APPROVED_SHA=8881dd1c` 并回读 tag）；08:13:09Z 由 `prune_prereleases.py` 清理 `v3.0.18-preview.2`，正式版 `v3.0.17`、`v3.0.14`、`v3.0.11` 保留。内容：「设置 → 连接与核心 → UDP 回显端点」可填自管数字 IP 与端口（默认留空即零流量，fail-closed），各优先级层子组独立执行 UDP 回显验证；仅支持 UDP 的上层成员凭有效回显进入候选，不再依赖 TCP 延迟记录（回切仍要连续三轮新观测 + 60 秒无失败）；首次应用配置前也回读已保存的开机自启设置；自启等待确认期间连点按最后一次选择落地。四件套：APK `6374839` B / `e7832733…893507`、普通 Root ZIP `18149655` B / `1fbfc62a…bdc280`、合一 ZIP `23797772` B / `2fe93950…88c80d`、`SHA256SUMS` `333` B，APK + 普通模块 `24524494` 字节（23.39 MiB）→ `size_budget --enforce-limit` PASS。本版自身的 Release/tag 已在 `v3.0.18-preview.4` 发布后（2026-11-23T12:55:40Z）按单预览策略清理（现为 404）。

### v3.0.18-preview.2 已发布说明

[v3.0.18-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.2) 以 main `e7f1f9366316974d8318e8708dcaaa669bc896a0`（升版 PR #81 合并提交，轻量 tag）发布，`Preview Build` run `44728368529` 四作业全绿、2026-11-20T11:51:54Z 上架；预览元数据 `2037`，APK `10187` / 模块 `2037`。内容为收口 preview.1 六项缺陷（PR #80）：URLTest 观测按（成员, 网络）分账、UDP 探测只认真实往返回显、降级即作废旧回切资格、组关闭停拨备用、开机自启「下发 → 回读 → 落库」闭环、`boot.json` 不可用时状态与 `Boot()` 同口径。已知边界：该版 UDP 探测默认零流量，自管端点入口到 preview.3 才落地。其 Release/tag 已在 preview.3 发布后（2026-11-21T08:13:09Z）清理。

### v3.0.18-preview.1 已发布说明

[v3.0.18-preview.1](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.18-preview.1) 以 main 的 `0a30baaf939a97b1941210609ce377e00d386157`（轻量 tag）发布，`Preview Build` run `44516333684` 四个作业全绿（发布 job 断言 `APPROVED_SHA=0a30baaf`）；预览元数据 `2036`，APK `10181` / 模块 `2036`。内容为 PR #77（`currentTier` / `tierGate` 按 TCP/UDP 拆分，消除跨协议污染）与 PR #78（App UI 对齐模块功能：开机自启开关、重启核心、回滚配置、顶栏模块版本）。已知边界：一次探测的观测仍按 tag 共享、嵌套组聚合 summary 与网络无关，两个新 UI 入口无自动化覆盖。`0a30baa` 是 `preview.yml` 的热修直推提交，没有单独的 `Verify Android Build` 记录，其代码树与已全绿的 `4a8ab9b` 相同。本版 Release 在 `v3.0.18-preview.2` 发布时由 `prune_prereleases.py` 清理。

### v3.0.17-preview.4 已发布说明

**状态：已作为稳定性预览版发布；Release/tag 已在 v3.0.18-preview.1 发布后清理。**

基于 main `906a6ea3f48779cc96c7a4116cfd1faeb63aa354`（PR #72，`Verify Android Build` run `44372679147`），`Preview Build` run `44378329740` 于 2026-11-18T19:38:07Z 发布；预览元数据 `2034`，APK `10174` / 模块 `2034`。内容为故障转移不再绕过层级回切门、过期候选保留为最后梯队兜底、direct 失败改 15s/1min/5min 有界退避、区分 `su` 超时与真正的空白名单，另收口 supervisor 吞错、预算到期虚增健康轮与零引用符号。资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。

### v3.0.17-preview.3 已发布说明

[v3.0.17-preview.3](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.3) 已作为稳定性预览版发布（tag/commit `6adc5ac0bcbd87bf9ff4b2097fe367761a4bc841`，PR #69；`Verify Android Build` run `44295650161`，`Preview Build` run `44306599880` 于 2026-11-18T13:08:01Z 发布）。内容为修复 URLTest 预算到期后层级永不回切、诊断包 Android 12+ 不可读取，并收口八项 P2 与 Room 11 schema；APK `10168` / 模块 `2033`。资产快照见[版本对照](docs/current-version.zh-CN.md)；其 Release/tag 已在 preview.4 发布后按单预览策略清理，下载链接不再可用。

### v3.0.17-preview.2 已发布说明

[v3.0.17-preview.2](https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.17-preview.2) 已作为稳定性预览版发布（tag/commit `49e966197dd608b687b36eecdaf394274dacfe15`，PR #67/#68；`Preview Build` run `44281162788` 于 2026-11-18T10:18:19Z 发布）。内容为 OwnBox/NetProxy 两路借鉴移植合并后的首个预览：核心稳定性与故障转移、订阅自动更新、智能分流、冷启动秒连、策略组混搭/锁定/优先级、停核可编辑与脱敏诊断包、按网络规则、路由规则指定策略组；APK `10162` / 模块 `2032`。其 Release/tag 已在 preview.3 发布后按单预览策略清理，下载链接不再可用。

### 更早预览

v3.0.17-preview.1、v3.0.16-preview.4、v3.0.16-preview.3、v3.0.16-preview.2、v3.0.16-preview.1、v3.0.15-preview.1–.4 与 v3.0.14-preview.1 的已发布说明：各版 Release/tag 已按单预览策略清理，说明与资产快照见[版本对照](docs/current-version.zh-CN.md)，发布脉络见 [CHANGELOG.md](CHANGELOG.md)。
