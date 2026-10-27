package io.wanbox.adapters.memory

import io.wanbox.contracts.Clock
import io.wanbox.contracts.TrafficMeter
import io.wanbox.contracts.dto.TrafficSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryTraffic(private val clock: Clock = FixedClock(0L)) : TrafficMeter {

    private val state = MutableStateFlow(TrafficSnapshot(null, 0, 0, 0))

    /** 采样历史，series() 用它模拟曲线。 */
    val samples = mutableListOf<TrafficSnapshot>()

    var persistCalls: Int = 0
        private set

    val current: TrafficSnapshot get() = state.value

    override fun observe(): Flow<TrafficSnapshot> = state.asStateFlow()

    override suspend fun series(window: String, profileId: Long?): List<TrafficSnapshot> = samples.toList()

    override suspend fun reset(profileIds: List<Long>) {
        state.value = state.value.copy(rxBytes = 0, txBytes = 0)
        samples.clear()
    }

    override suspend fun persist() {
        persistCalls++
    }

    /** 模拟一次采样：累加并记入历史。 */
    fun accumulate(rx: Long, tx: Long, at: Long = clock.now(), profileId: Long? = null) {
        val next = state.value.copy(
            profileId = profileId ?: state.value.profileId,
            rxBytes = state.value.rxBytes + rx,
            txBytes = state.value.txBytes + tx,
            since = at,
        )
        state.value = next
        samples += next.copy(rxBytes = rx, txBytes = tx)
    }
}
