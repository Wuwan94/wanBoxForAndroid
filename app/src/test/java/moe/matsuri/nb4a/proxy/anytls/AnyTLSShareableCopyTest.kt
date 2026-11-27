package moe.matsuri.nb4a.proxy.anytls

import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.shareableCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通用分享链接（sn://）编码前的副本：JVM 侧覆盖脱敏逻辑本身，Base64/zlib 外壳由 AnyTLSShareUriTest 在设备上覆盖。 */
class AnyTLSShareableCopyTest {

    @Test
    fun universalShareCopyDropsClientIdentityAndLeavesLocalBeanUntouched() {
        val local = AnyTLSBean().apply {
            name = "share-label"
            serverAddress = "anytls.example"
            serverPort = 443
            password = "share-password"
            sni = "sni.example"
            clientCertificate = "-----BEGIN CERTIFICATE-----\nclient-certificate-fixture\n-----END CERTIFICATE-----"
            clientKey = "-----BEGIN PRIVATE KEY-----\nclient-private-key-fixture\n-----END PRIVATE KEY-----"
            initializeDefaultValues()
        }

        val shared = local.shareableCopy() as AnyTLSBean
        assertNotSame(local, shared)

        val payload = KryoConverters.serialize(shared)
        val payloadText = String(payload, Charsets.ISO_8859_1)
        assertFalse(payloadText.contains("client-certificate-fixture"))
        assertFalse(payloadText.contains("client-private-key-fixture"))
        assertFalse(payloadText.contains("BEGIN PRIVATE KEY"))

        val imported = KryoConverters.anyTLSDeserialize(payload)
        assertEquals("anytls.example", imported.serverAddress)
        assertEquals("share-password", imported.password)
        assertEquals("sni.example", imported.sni)
        assertEquals("", imported.clientCertificate)
        assertEquals("", imported.clientKey)

        assertTrue(local.clientCertificate.contains("client-certificate-fixture"))
        assertTrue(local.clientKey.contains("client-private-key-fixture"))
    }
}
