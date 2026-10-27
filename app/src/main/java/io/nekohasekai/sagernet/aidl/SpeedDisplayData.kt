package io.nekohasekai.sagernet.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class SpeedDisplayData(
    // Bytes per second
    var txRateProxy: Long = 0L,
    var rxRateProxy: Long = 0L,
    var txRateDirect: Long = 0L,
    var rxRateDirect: Long = 0L,

    // Bytes for the current session
    // Outbound "bypass" usage is not counted
    var txTotal: Long = 0L,
    var rxTotal: Long = 0L,

    // @author 雾晚：模块端遥测（-1=未知，明确协议）
    // 由 :bg 进程通过 AIDL 回调传递，UI 进程观察
    // 使用 -1 标记未知，与真实 0 区分（见回归测试）
    var vmRSSBytes: Long = -1L,
    var uptimeSeconds: Long = -1L,
    var txTotalModule: Long = -1L,
    var rxTotalModule: Long = -1L,
    var connectionCount: Int = -1,
    // 真实运行中的 profileName（已验证代次），不从未确认 selectedProxy 冒充
    var runningProfileName: String = "",
) : Parcelable {
    /** 是否有有效遥测 */
    fun hasTelemetry(): Boolean = vmRSSBytes >= 0 || uptimeSeconds >= 0
    /** 连接数未知时返回 null，区分未知与真实 0 */
    fun connectionCountOrNull(): Int? = if (connectionCount < 0) null else connectionCount
    /** 累计未知时返回 null */
    fun txTotalModuleOrNull(): Long? = if (txTotalModule < 0) null else txTotalModule
    fun rxTotalModuleOrNull(): Long? = if (rxTotalModule < 0) null else rxTotalModule
}
