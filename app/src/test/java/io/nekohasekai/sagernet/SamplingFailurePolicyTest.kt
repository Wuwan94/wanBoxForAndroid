// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.SamplingFailurePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单次采样抖动不得让仪表盘判定服务断开。 @author 雾晚 */
class SamplingFailurePolicyTest {

    @Test fun singleFailureKeepsCurrentState() {
        val policy = SamplingFailurePolicy(threshold = 3)
        assertFalse(policy.onFailure())
        assertFalse(policy.onFailure())
    }

    @Test fun degradesOnlyAtThreshold() {
        val policy = SamplingFailurePolicy(threshold = 3)
        policy.onFailure()
        policy.onFailure()
        assertTrue(policy.onFailure())
    }

    @Test fun successResetsTheCounter() {
        val policy = SamplingFailurePolicy(threshold = 3)
        policy.onFailure()
        policy.onFailure()
        policy.onSuccess()
        assertFalse(policy.onFailure())
        assertFalse(policy.onFailure())
        assertTrue(policy.onFailure())
    }
}
