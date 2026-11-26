package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyEntityDisplayTypeTest {
    @Test
    fun xhttpVlessHasTransportLabel() {
        val profile = ProxyEntity(
            type = ProxyEntity.TYPE_VMESS,
            vmessBean = VMessBean().apply {
                alterId = -1
                type = "xhttp"
            },
        )

        assertEquals("VLESS + XHTTP", profile.displayType())
    }

    @Test
    fun plainVlessKeepsBaseLabel() {
        val profile = ProxyEntity(
            type = ProxyEntity.TYPE_VMESS,
            vmessBean = VMessBean().apply {
                alterId = -1
                type = "tcp"
            },
        )

        assertEquals("VLESS", profile.displayType())
    }

    @Test
    fun vmessKeepsItsProtocolLabel() {
        val profile = ProxyEntity(
            type = ProxyEntity.TYPE_VMESS,
            vmessBean = VMessBean().apply {
                alterId = 0
                type = "tcp"
            },
        )

        assertEquals("VMess", profile.displayType())
    }

    @Test
    fun xhttpRealityVlessShowsBothLabels() {
        val profile = ProxyEntity(
            type = ProxyEntity.TYPE_VMESS,
            vmessBean = VMessBean().apply {
                alterId = -1
                type = "xhttp"
                security = "tls"
                realityPubKey = "reality-public-key"
            },
        )

        assertEquals("VLESS + XHTTP · Reality", profile.displayType())
    }

    @Test
    fun realityVlessHasSecurityLabel() {
        val profile = ProxyEntity(
            type = ProxyEntity.TYPE_VMESS,
            vmessBean = VMessBean().apply {
                alterId = -1
                type = "tcp"
                security = "tls"
                realityPubKey = "reality-public-key"
            },
        )

        assertEquals("VLESS · Reality", profile.displayType())
    }
}
