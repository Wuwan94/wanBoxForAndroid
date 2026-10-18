// @author 雾晚
package io.nekohasekai.sagernet.bg

/**
 * 采样失败的降级判定。
 *
 * 模块状态由前台观察者按固定周期经 `su` 采样。节点热切换会并发拉起
 * `wanboxctl`，单次调用可能超时或与模块控制锁竞争；若把一次失败直接
 * 当成服务断开，仪表盘按钮与当前节点会先清空再恢复，用户看到的就是
 * 「切换节点时重启/闪烁」。因此只有连续失败达到阈值才降级。
 *
 * 真实停服不会经过异常路径：模块返回的 `phase=stopped` 是正常响应。
 */
internal class SamplingFailurePolicy(private val threshold: Int = 3) {
    init {
        require(threshold > 0)
    }

    private var consecutiveFailures = 0

    val failures: Int get() = consecutiveFailures

    fun onSuccess() {
        consecutiveFailures = 0
    }

    /** 记录一次失败；返回 true 表示应当降级为未连接。 */
    fun onFailure(): Boolean {
        consecutiveFailures++
        return consecutiveFailures >= threshold
    }
}
