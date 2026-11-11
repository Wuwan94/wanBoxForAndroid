# wanBox for Android 贡献指南 / Contributing Guidelines

感谢你对 **wanBox for Android** 的关注与支持！

wanBox 是一套面向已 Root 设备的系统级 Android 代理管理器与核心套件。为了确保所有用户在获得极致轻量（~6.1 MiB 瘦身管理器）与高性能网络体验的同时，**绝对不发生真实 IP 泄漏、内存膨胀或系统服务崩溃**，本项目实行高度严格的“证据驱动”与“Fail-Closed（故障阻断）”工程规范。

在提交 Pull Request (PR) 或 Issue 前，请仔细阅读以下准则。

---

## 🧭 一、核心架构原则（不可动红线）

1. **瘦身管理器（Thin Manager）原则**：
   - 管理器 APK 坚决不捆绑 Go 核心（零 JNI、无 `libgojni.so` 常驻内存）。
   - APK 必须保持在 ~6.1 MiB 极小体积内，严禁为了少量便利引入臃肿的三方库。
   - 所有 UI 组件坚守 Material 3 原生标准与协程异步化，严禁阻塞主线程。

2. **原生 Root 模块承载原则**：
   - 核心守护进程（sing-box）与系统级 TUN 路由/iptables 规则完全由 Magisk / KernelSU / APatch 模块持有。
   - 绝不依赖 Android 系统传统的 `VpnService`（避免额外的 VPN 线程损耗与被系统单边杀后台）。

3. **Fail-Closed（故障阻断）安全红线**：
   - **绝不回退直连**：更新器与延迟探测若检测到本地核心不可达，必须坚决报错中断，严禁静默回退到直连，防止在代理故障时泄露用户的真实 IP。
   - 本地通信严格绑定 `127.0.0.1:9090`，外发请求坚守 HTTPS 并禁用重定向（`followSslRedirects(false)`）。

4. **单证书与成套配对**：
   - 管理器与 Root 模块必须由同一官方 Release 流水线使用同一私钥签发。
   - 瘦管理器与模块严格成套使用，严禁跨版本混用。

5. **25 MiB 总包体积预算**：
   - 双产物（瘦 APK + 完整 Root 模块 ZIP）必须严格受控在 25 MiB（26,214,400 字节）绝对预算之内。

---

## 🛠️ 二、本地开发与验证

本项目内置了完整的离线单测与文档一致性检查工具（位于 `tools/` 目录）。任何修改在推送到 GitHub 前，**必须确保本地测试全绿通过**。

### 1. 运行自动化单元测试
```bash
python3 -m unittest discover -s tools -p "test_*.py"
```
当前包含 108 项测试，涵盖版本号计算规则、发布清单校验、资产哈希检查与安全门禁。所有测试必须 100% 通过（`OK`）。

### 2. 检查版本快照与文档一致性
```bash
python3 tools/check_current_version_docs.py
```
该检查验证 README、版本对照表、发布清单与发行说明之间的一致性，防止元数据在发布期发生隐性漂移。

---

## 🌿 三、分支与提交规范

### 1. 分支命名
- 特性分支：`feat/your-feature-name`
- 缺陷修复：`fix/issue-description`
- 文档重构：`docs/topic-name`
- 性能优化：`perf/optimization-target`

### 2. 提交信息规范 (Conventional Commits)
提交信息应当清晰、精炼，遵循业界标准：
- `feat(scope): 简要说明新增特性`
- `fix(scope): 简要说明修复的问题`
- `docs(scope): 简要说明文档更新`
- `perf(scope): 简要说明性能改进`
- `refactor(scope): 代码重构（不改变外部行为）`

对于关键修复，建议在提交信息正文中注明：
- **并存：是/否**（是否可与旧版平滑并存）
- **回退策略**（若发生意外，如何干净回滚）

---

## 📋 四、Pull Request 流程

1. **Fork 并检出分支**：从最新的 `main` 分支拉取代码。
2. **编写代码与单测**：遵循既有 Kotlin / Shell / Python 编码风格，若涉及核心逻辑变动，须补全对应测试用例。
3. **本地离线验证**：运行 `tools/` 下的单测与核对脚本。
4. **提交 PR 并填写 Checklist**：仓库配置了 PR 模板，请逐项确认体积守卫、Fail-Closed 原则与测试结果。
5. **CI 自动化验证**：GitHub Actions 将自动执行编译、签名验证与冷启动基准测试。
