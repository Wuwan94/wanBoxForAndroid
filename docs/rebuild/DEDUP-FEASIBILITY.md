# 去重用可行性：App 不再内嵌核心

核对时间：2026-10-28
对象：`main` = `40952b0`（preview.26 之后）
目的：回答「① APK 不再内嵌核心」能不能做、要做多少、能省多少。

## 一、先把事实说准（本文修正一处此前错误）

此前把 APK 里的大文件说成 `librootbox.so`，**这是错的**。实测：

| 产物 | 文件 | 未压缩 | APK/zip 内压缩后 | 位置 |
| --- | --- | --- | --- | --- |
| 模块 | `bin/rootbox` | 48,889,600 | 16,331,936 | Root 模块 zip |
| App | `lib/arm64-v8a/libgojni.so` | 49,699,976 | 16,600,871 | APK（占 APK 62.2%） |

- `app/build.gradle.kts` 的 `packaging { jniLibs { excludes += "**/librootbox.so" } }` 早已把 `librootbox.so` 排除出 APK，注释写明「the standalone core belongs to the module, not the manager APK」。
- 两者**同源于 `libcore/` 的 Go 代码**，但形态不同：`rootbox` 是 PIE 可执行（模块运行），`libgojni.so` 是 gomobile 绑定库（App 内嵌引擎）。
- 结论不变：同一套 Go 代码被打了两遍，压缩后合计 32.9 MB，占「模块 + APK」总量 45.87 MB 的 71.8%。变的只是「APK 里那份叫什么」。

## 二、砍掉 App 内嵌引擎，意味着替换多少调用点

`libcore` 在 `app/src/main/java` 共 20 个文件、20 类 API 被调用：

| 能力 | 调用 | 次数 | 替代方案 | 难度 |
| --- | --- | --- | --- | --- |
| 日志桥 | `Libcore.nekoLogPrintln` / `nekoLogClear` | 13 | `android.util.Log`（或转发给模块日志） | 低 |
| 连接重置 | `Libcore.resetAllConnections` | 5 | 模块 `wanboxctl restart` 或直接省略 | 低 |
| HTTP 客户端 | `Libcore.newHttpClient`（订阅拉取） | 4 | 已有 `okhttp 5.0.0-alpha.3` | 低 |
| 网络接口枚举 | `InterfaceType*`、`NetworkInterfaceIterator` | 4 | Android `NetworkCapabilities` | 低 |
| 网络变更回调 | `setNetworkChangeResetConnections` | 2 | 现成的 `ConnectivityManager` 回调 | 低 |
| 配置校验 | `newTestSingBoxInstance` | 2 | 模块侧 `config apply` 干跑 / Go 侧校验接口 | 中 |
| 延迟测试 | `urlTest` / `urlTestFull` / `newURLTestSession` | 3 | 模块 `dashboard`（clash API）或 OkHttp 直测 | 中 |
| 测速 | `newSpeedTestSession` | 1 | 同上，或砍掉该功能 | 中 |
| STUN | `stunTest` | 1 | 保留（需 Go 能力）或砍掉 | 中 |
| 本地 TUN 引擎 | `newSingBoxInstance` | 1 | **无替代**：只能删掉「无模块的本地 TUN 模式」 | 高（产品决策） |
| 初始化 | `initCore`、`versionBox`、`unxz` | 3 | 初始化可删；版本号取自 `nb4a.properties` | 低 |

也就是说：**约 14 项是低难度替换（约 26 个调用点），3 项中等（校验/测速/STUN），1 项是产品决策（本地 TUN）**。

## 三、能不能省那 16.6 MB

**实测尝试失败了，如实记录：**

| 尝试 | 做法 | 结果 | 判定 |
| --- | --- | --- | --- |
| 1 | `-PwanboxDropEmbeddedCore` 编两次 debug APK 对比 | 基线 47,104,719 / 去核心 47,104,648，**只差 71 字节** | 不可信：删掉 16.6 MB 条目不可能只差 71 字节，疑为打包任务被判 up-to-date 跳过重打包 |
| 2 | 只编一次基线，从副本里精确删除 `lib/` 条目 | 列出条目时**没有任何 `lib/` 行** | 不可信：与尝试 1 中「基线含 `libgojni.so`」直接矛盾，说明 lab 里拿到的 debug APK 不代表真实产物 |

两次结果互相矛盾且未定位到根因，因此**弃用 debug APK 路线**，也撤掉了 `-PwanboxDropEmbeddedCore` 开关（不留产出垃圾数字的旋钮）。

**权威数据来自已发布 APK 的构成**（`size_breakdown.py` 对 v3.0.26 release APK 的真实读取）：

```
lib/arm64-v8a/libgojni.so   49,699,976 → 16,600,871   占 APK 62.2%
```

据此计算：

```
APK 26,802,221 − 16,600,871 ≈ 10,201,350
模块（UPX 后）           ≈ 17,381,041
合计                     ≈ 27,582,391
上限                       26,214,400
仍超                      1,367,991
```

即使三项全做（去内嵌核心 + 模块 UPX + 裁 res/assets），也只是**刚刚卡线**：还要从 `res/`（3,108,176）与 `assets/`（2,179,807）里再挤约 1.37 MB。工作词 S3 提到 yacd 约 0.75 MB，剩下的缺口要靠图标与资源压缩。

**未验证的一件事**：APK 去掉引擎后，zip 内的对齐与压缩统计可能略有不同，上面 10.2 MB 是按「总字节减去该条目压缩后字节」推算，误差应在几十 KB 级。要拿到逐字节实测，需要在有 Android SDK 的机器上真编一次 release 变体，而不是靠 debug 变体。

## 四、三条路径

| 路径 | 做法 | 省 | 风险 | 代价 |
| --- | --- | --- | --- | --- |
| A 全量去核心 | 按上表替换全部 20 类调用，只保留「管理模块」能力 | −16.6 MB | 中高：本地 TUN 消失、测速/延迟改走模块、行为变化需真机逐项验 | 工作量最大，涉及 20 文件 |
| B 精简 libgojni | 保留 API，重新用更少 build tag 构建（去掉 tun/gvisor/ebpf 等） | 估 4–6 MB（未实测） | 低：接口不变、行为不变，只是能力子集 | 需另建一套 Go 构建并守住 tag 边界 |
| C 双产物 | 模块用户装「无核心」APK，非模块用户装完整 APK | −16.6 MB（对模块用户） | 中：两套构建、分发与文档都要分叉 | 需产品确认是否还支持非模块用户 |

## 五、建议顺序

1. **先做 B 的实测**（半天到一天）：用不同 tag 组合编几个 `libgojni.so`，量出真实体积曲线。它不改接口，风险最低，能为 A 提供「到底还差多少」的准确输入。
2. **再做 A 的低难度部分**（日志 / HTTP / 接口枚举 / 网络回调，约 26 个调用点）：这部分不改变用户可感知行为，可以先合入，同时把 `initCore` 依赖削薄。
3. **最后单独决策本地 TUN**：它是唯一必须砍功能才能过的关卡。砍掉之前要明确：非 Root 用户是否还有使用场景。

## 六、必须记住的前提

- 本机无法验证 Android 运行时；`libgojni.so` 一旦剔除，App 内所有 `libcore` 调用都会 `UnsatisfiedLinkError`。lab 产出的「去核心 APK」**只用于测体积，禁止发布**。
- UPX 只验证了可还原性，Android arm64 加载兼容性仍未验证。
- 体积门禁已在 CI：判「相对基线不回退」，绝对预算留待 P7 用 `--enforce-limit` 判定。
