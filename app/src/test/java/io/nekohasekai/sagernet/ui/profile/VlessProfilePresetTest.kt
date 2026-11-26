package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessProfilePresetTest {
    @Test
    fun xhttpPresetCreatesVlessWithXhttpTransport() {
        val bean = createVlessBean(useXhttp = true)

        assertTrue(bean.isVLESS)
        assertEquals(-1, bean.alterId)
        assertEquals("xhttp", bean.type)
    }

    @Test
    fun ordinaryVlessPresetKeepsTcpTransport() {
        val bean = createVlessBean(useXhttp = false)

        assertTrue(bean.isVLESS)
        assertEquals(-1, bean.alterId)
        assertEquals("tcp", bean.type)
    }

    @Test
    fun vmessDefaultBeanKeepsVmessTcpDefaults() {
        val bean = VMessBean().apply { initializeDefaultValues() }

        assertFalse(bean.isVLESS)
        assertEquals("tcp", bean.type)
    }
}
