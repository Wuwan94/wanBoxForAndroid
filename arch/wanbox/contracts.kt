// 契约草案已升级为可编译的真实实现，本文件不再维护，避免出现两份互相矛盾的接口定义。
//
// 权威位置（编译通过、有测试）：
//   arch/core-proof/core/contracts/src/main/kotlin/io/wanbox/contracts/Contracts.kt   ← 接口，唯一对外文件
//   arch/core-proof/core/contracts/src/main/kotlin/io/wanbox/contracts/dto/Dtos.kt    ← DTO 目录
//
// 覆盖的端口（相较草案新增 7 个，都是 28 条用例逼出来的）：
//   ProfileRepository（新增 deleteByGroup）
//   SubscriptionGateway（update 返回 SubscriptionUpdate 含增删改计数；新增 recordResult 承载退避）
//   RouteRepository（新增 setEnabled / selectedPackages / clearOutbound）
//   SettingsStore（Map patch → 类型化 SettingsPatch；新增 replace 供迁移原子替换）
//   TrafficMeter（新增 persist，断开时落库）
//   FileGateway（新增 preview，先校验后写入）
//   LogGateway / RootModuleGateway / StorageHealthPort（新增，分别对应日志导出、root 白名单、损坏恢复）
//
// 依赖纪律：除 kotlinx-coroutines-core 外零依赖；DTO 不含 Context/Uri/Bitmap/Parcelable。
// 这条由 arch/wanbox/arch-check.sh 第 1、4 节自动校验，不靠人记。
package io.wanbox.contracts

/** @deprecated 见文件头注释；实现已迁至 arch/core-proof/core/contracts。 */
@Deprecated("契约已落地为可编译模块，见 arch/core-proof/core/contracts")
object ContractsMovedNotice
