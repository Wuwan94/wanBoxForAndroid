# wanBox 真机验证清单（preview.13）

> 这份清单是**唯一能真正确认软件可用**的方式。模拟器验证不了本项目的核心路径，原因见文末。
> 全程约 15 分钟。每一步都给了「预期结果」和「失败时看哪里」，按顺序走即可。

## 0. 准备

- 手机：arm64-v8a、Android 12+、已 Root（Magisk / KernelSU 均可）
- 下载 `wanbox-root-module-with-manager-arm64-v8a.zip`（内嵌管理 App，一步到位）
- 准备一个可用的代理节点（订阅链接或手工配置）

## 1. 安装与启动

1. Magisk → 模块 → 从本地安装 → 选 `wanbox-root-module-with-manager-arm64-v8a.zip`
2. **重启手机**（`service.sh` 只在开机执行，不重启管理面不会起）
3. 重启后打开 wanBox App

**预期**：App 能读到状态，不提示「模块服务暂时不可用」。

**失败时看**（手机终端或 adb shell）：
```bash
cat /sdcard/wanbox-serve.log          # 管理面启动追踪
cat /data/adb/wanbox/delivery.log     # 凭据交付结果
cat /data/adb/wanbox/api.json         # 端口 / token / 证书指纹
```
- `serve.log` 里应有 `serve: listening` 与 `delivery ok`
- `api.json` 的 `certSha256` 应与 `api-cert.pem` 的 SHA-256 一致（preview.11 修的就是这个）
- App 内「导出日志」里若出现 `module status sample failed (#n): <错误码>`，错误码直接说明原因

## 2. 凭据交付（App 零 Root 读得到）

App 不碰 `su`，只读自己私有目录。确认三路兜底至少一路命中：
```bash
run-as com.lit008834.pixel.wanboxforandroid ls -l no_backup/module-api.json files/module-api.json
```
**预期**：至少一个文件存在，属主是 App 的 uid，权限 0600。

## 3. 配置与节点（配置意图下沉）

1. 导入订阅或手工添加节点
2. 选中一个节点，点连接

**预期**：核心起来，能正常代理。

**失败时看**：
- 日志出现 `profile_missing` → 当前选中的节点没进模块 store，App 会自动补写一次；**持续出现**请记录节点来源（订阅 / 手工）
- 日志出现 `nodes_empty` → 模块 store 是空的，首次迁移没跑成
- 日志出现 `node_tag_duplicate` / `auxiliary_outbound_invalid` → 配置里有重复 tag 或非法出站，把配置发我

## 4. 端点节点（wireguard）—— preview.12 的重点修复

如果你有 wireguard 节点：
1. 选中它，连接
2. 在节点列表里切换回普通节点，再切回 wireguard

**预期**：wireguard 节点**可以选中并连接**（preview.11 及之前会报 `selector_target_missing` 选不了）。

**失败时看**：日志出现 `selector_target_missing` → 端点迁移没生效，检查 `/data/adb/wanbox/store/nodes.json` 里该节点的 `endpoint` 字段是否为 `true`。

## 5. 热更新替换管理面 —— preview.13 的重点修复

这是最值得验证的一条（前两版在这里有缺陷）：

1. 保持 App 连接状态
2. 装一个新版模块 zip（覆盖安装），重启手机
3. 重启后打开 App

**预期**：管理面被新版接管，App 立刻连上，不需要重新配对。

**失败时看**：
```bash
cat /sdcard/wanbox-serve.log     # 应看到旧 serve 停止 + 新 serve 启动
ps -A | grep wanboxctl           # 应只有一个 serve 进程
```
- 出现**两个** `wanboxctl __internal serve` → 旧的没停掉（preview.12 的已知缺陷，preview.13 已修）
- App 连不上且 `api.json` 端口没变 → 管理面没被替换

## 6. 配置失败不落盘 —— preview.13 的重点修复

1. 故意制造一次配置失败：改一条路由规则指向不存在的出站 tag，然后应用
2. 观察 App 报错
3. **不改任何东西**，重新加载配置 / 重启核心

**预期**：第 3 步用的是**上一次成功的配置**，不会把第 1 步的坏配置悄悄生效。

**失败时看**：`/data/adb/wanbox/store/config.json` 里出现了第 1 步的坏规则 → 提前落盘的缺陷复现了，请把文件发我。

## 7. 卸载

Magisk → 模块 → 卸载 wanBox → 重启

**预期**：`wanboxctl` 进程全部退出，`/data/adb/wanbox/api.pid` 被清理，没有残留 serve。

```bash
ps -A | grep wanboxctl    # 应为空
ls /data/adb/wanbox/api.pid 2>&1   # 应为 No such file
```

---

## 为什么模拟器验证不了

不是不想做，是这个项目的核心路径在模拟器上**结构性地跑不通**：

| 需求 | 模拟器现状 |
|---|---|
| 模块二进制 arm64-only | x86_64 镜像跑不了；arm64 镜像在 x64 Windows 上无硬件加速，慢到不可用 |
| Magisk 的 `/data/adb/modules/` | google_apis 镜像没有 Magisk，目录不存在 |
| Root 执行 `wanboxctl` | 模拟器无 root |
| SELinux 上下文对齐（`chcon`） | 模拟器 SELinux 通常 permissive，**真实故障模式不复现** |
| cgroup 隔离 | 模拟器无对应 cgroup 层级 |
| TUN 设备 + 真实代理 | 模拟器网络栈不同 |

而本项目出问题的恰恰是这些（Android 13+ 的 SELinux 属主、Android 16 / HyperOS 的 cgroup 失败）—— 模拟器把它们全部绕过了，跑通也说明不了真机可用。

**CI 里已有的模拟器测试**（`verify-android.yml`，x86_64 + api 35）覆盖的是 App 侧：Room 数据库迁移、便携备份回环、桌面小组件 / 通知外部控制。那是 App 的 UI 与数据层，不是 Root 模块。

所以：**自动化测试（Go 94 + App 357）+ CI 模拟器测试 已经证明了代码逻辑正确；剩下的只有真机能证明。**
