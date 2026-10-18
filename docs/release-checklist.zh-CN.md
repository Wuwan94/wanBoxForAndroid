# wanBox 发布清单（覆盖上一次）

> 本文档固化发布规则：**正式版与预览版每次发布都覆盖上一次**，只保留最新一版。
> 覆盖安装、版本号递增、文档更新三件事在每次发布前必须完成。

## 一、核心规则

1. **覆盖上一次**：发布新版本后，自动删除同渠道（正式版/预览版各自独立）的旧 release 与旧 tag，只保留最新一版。
2. **可覆盖安装**：包名 `com.lit008834.pixel.wanboxforandroid`、签名、数据库、备份格式保持不变；`versionCode` 严格递增，系统允许直接覆盖安装、不丢数据。
3. **版本号必须递增**：每次发布都要把对应版本号 +1，否则发布流程会在「版本号 tag 已存在」检查处失败。
4. **文档同步更新**：发布前更新 `CHANGELOG.md`，并按渠道更新 `PREVIEW_NOTES.md`（预览版）或 `RELEASE_NOTES.md`（正式版）。

## 二、版本号规则（`nb4a.properties`）

| 字段 | 含义 | 发布时操作 |
|---|---|---|
| `VERSION_NAME` | 正式版版本名（如 `3.0.9`） | 正式版发布时递增 |
| `VERSION_CODE` | 正式版版本号（如 `362`） | 正式版发布时 +1（APK code = `×5`） |
| `PRE_VERSION_NAME` | 预览版版本名（如 `3.0.10-preview.6`） | 预览版发布时递增 `preview.N` 的 N |
| `PRE_VERSION_CODE` | 预览版版本号（如 `368`） | 预览版发布时 +1，且必须 `> VERSION_CODE` |

> 预览版的 `PRE_VERSION_CODE` 必须大于正式版 `VERSION_CODE`（构建脚本会校验）。
> 换一个新的 `PRE_VERSION_NAME` 是关键——发布流程会检查 `v<PRE_VERSION_NAME>` 这个 tag 是否已存在，已存在则拒绝覆盖。

## 三、预览版发布流程

1. 改 `nb4a.properties`：`PRE_VERSION_NAME` → 下一个 `preview.N`，`PRE_VERSION_CODE` +1。
2. 更新 `PREVIEW_NOTES.md`（本版预览说明）+ `CHANGELOG.md`（追加一条）。
3. 提交，push 到 `beta1-1.15` 分支。
4. CI（`preview.yml`）自动：构建 LibCore + APK → 跑 `go test -race` + 单元测试 → 发布 prerelease → 清理旧预览版。

## 四、正式版发布流程

1. 改 `nb4a.properties`：`VERSION_NAME` → 新正式版本号，`VERSION_CODE` +1。
2. 更新 `RELEASE_NOTES.md`（本版正式说明）+ `CHANGELOG.md`（追加一条）。
3. 提交，push tag `v<VERSION_NAME>`（如 `v3.0.10`）。
4. CI（`release.yml`）自动：构建 + 校验签名 → 发布正式版 → 清理旧正式版（本次新增）。

## 五、「覆盖上一次」的实现位置

| 渠道 | 清理步骤 | 行为 |
|---|---|---|
| 预览版 | `preview.yml` → `Prune older preview releases` | 删除旧的 `prerelease=true` 且 tag 含 `-preview.` 的 release + tag |
| 正式版 | `release.yml` → `Prune older stable releases` | 删除旧的 `prerelease=false` 的 release + tag |

两个渠道**相互独立**：正式版清理时不动预览版，预览版清理时不动正式版。清理失败只告警（`continue-on-error`），不影响已经完成的发布。

## 六、覆盖安装的保证

- **包名/签名不变**：`build.gradle.kts` 与 keystore 固定，覆盖安装不触发签名冲突。
- **versionCode 递增**：APK 的 versionCode 由 `nb4a.properties` 的 `*_CODE × 5` 计算，每次发布 +1 保证系统认可覆盖升级。
- **数据兼容**：数据库 schema、便携备份格式、模块数据目录均向后兼容，覆盖安装不丢数据（相关回归见 `PortableBackupCompatibilityTest` 与 `RootModuleDataUpdateTest`）。
