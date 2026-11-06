# preview.3 候选验收记录

## 基线与权限边界

- 默认分支开工快照 main=`ff26111`，不含 preview.2。独立分支合入 tag `2fbe17f662`，保留 main UI。
- 本轮仅独立分支/PR和不发布候选构建；不合并 main、不创建tag/Release、不上传发布资产、不删除现有版本。
- `RELEASE_NOTES.md` 的正式版历史保持不变。

## 实际验证

| 项目 | 当前结果 |
|---|---|
| 两套已发布资产 | aapt/apksigner/模块与8资产SHA256复核完成，见版本证据JSON |
| Python工具测试 | 本地45项通过（UTF-8及Bash环境修正后）；新增版本用例另待统一复核 |
| Android安全检查 | 本地 tools/check_android_security.py 通过 |
| Root Go全量测试 | 已运行Windows测试，cgroup、进程身份及VmRSS平台用例失败；须以Linux CI补验，不修改测试规避 |
| Root Go静态检查 | 本地执行，最终结果待汇总 |
| Android/JVM单测、测试编译 | 待候选CI实际执行 |
| 签名候选、双渠道APK码、ABI、模块ZIP、SHA256 | 待候选CI实际执行；不能以源码值替代产物结果 |
| Macrobenchmark冷启动 | 已复用现有coldStartNoProfile接入API35 x86_64模拟器候选任务；结果待实跑 |
| Baseline Profile | 未新增或调整；不作提速声明 |
| 真机 | adb devices为空；未完成真机运行时回归 |

## 验收命令

```sh
python3 tools/version_code.py --channel formal --baseline-apk-code 10065 --baseline-module-code 2013 --enforce
python3 tools/version_code.py --channel preview --baseline-apk-code 10077 --baseline-module-code 2015 --enforce
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/check_android_security.py
go -C rootmodule test -race ./...
go -C rootmodule vet ./...
python3 -m unittest discover -s rootmodule -p 'test_*.py'
./gradlew app:testPreviewDebugUnitTest app:compilePreviewDebugAndroidTestKotlin
./gradlew app:assemblePreviewRelease -PwanboxThin=true
./gradlew app:assembleOssRelease -PwanboxThin=true
python3 tools/verify_preview_candidate.py --directory candidate --report candidate-validation.json
(cd candidate && sha256sum --check SHA256SUMS)
git diff --check
```

候选工作流用 `publish_release=false`；publish/cleanup job必须跳过。模拟器只执行现有无Profile冷启动10轮，profileable仅在可选benchmark变体启用；模拟器数字不能作为Root真机性能结论。

## 待真机验证

- Android 12+ ARM64 Root设备上，由正式版与preview.2各自覆盖安装候选，核对配置、分组、订阅、设置和模块数据保留。
- 损坏/截断/不支持格式导入报错且完整数据库快照不变；正常导入/导出及旧备份往返。
- 启动失败的具体原因可见；单次状态读取失败不误报未连接，超时后观察继续恢复。
- 服务启停、节点热切换、TUN、网络切换恢复、后台存活及模块更新回滚。
- 真机Macrobenchmark冷启动/首页或节点列表基线；有对比数据再讨论Baseline Profile。

已知未扩展范围：Balancer子页其它同步查询；applyBackend清理异常可能覆盖原始错误。保留这些风险，不以当前测试声称全路径已解决。
