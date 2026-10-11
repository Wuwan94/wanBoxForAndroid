# wanBox 3.0.8 正式版

作者：@author 雾晚

## 更新内容

- **节点热切换失败回退：**节点切换失败时保留旧可用连接与正确选择，不做无意义的全量重载；抛出异常触发 onError 通知用户失败原因，由用户决定是否重试或手动重载。
- **模式控制真实化：**Dashboard 模式切换经 `RealBackend.setMode` 做 Clash API PATCH+GET 确认，不再硬编码成功；Confirmed/Error 后刷新选中状态，失败时回读真实状态，避免后台模式与界面不一致；模式切换不重启核心。
- **设置页四组重组：**设置页重组为外观、运行、维护、关于四组，紧凑单列；Root TUN 不适用的旧入口已清理。
- **Root 设置精简：**删除 `enableClashAPI` 误导开关（Root 必需内部接口，固定启用）；`serviceMode`/`tunImplementation` 为只读模块信息；mixed 端口/认证/LAN 共享随 mixed 入站禁用而联动禁用；保留 DNS、路由、分应用指定节点、MTU、安全确认、备份恢复及有效高级参数；历史配置 key 保留迁移兼容。
- **诊断按钮：**仪表盘新增测速与落地 IP 查询按钮；测速经 Clash API GLOBAL 延迟接口（实际代理出站）；落地 IP 经当前节点查询（Root TUN 下所有流量经 TUN）；支持取消，节点切换时旧请求结果不覆盖新结果，不可用时明确提示原因。

## 回归测试

- 295 项 Kotlin 单测全绿，含节点热切换策略、ProfileSelection 行为、设置层级契约、测速契约测试。
- 仪器测试（SettingsHierarchyTest）覆盖四组新结构。

## 兼容性与升级

- 正式版 `versionName` 为 `3.0.8`；元数据 `VERSION_CODE=361`，Android `versionCode=1805`（构建时乘以 5），高于上一版 1800，可直接覆盖安装。
- 保持应用包名 `com.lit008834.pixel.wanboxforandroid` 和既有签名；核心为 sing-box `v1.15.0-alpha.10`。
- 不恢复普通 Android VpnService，不使用 eBPF，仅 Root TUN + sing-box auto_redirect。

## 未在真实 Root 设备验证

- 节点热切换实际行为、模式切换 PATCH+GET 真实流程、测速/落地 IP 诊断按钮、仪表盘四页导航完整流程需真机验证。CI 通过不等于真机通过。
