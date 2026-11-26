package moe.matsuri.nb4a.proxy.anytls

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnyTLSFmtTest {

    @Test
    fun localProfilePersistsClientCertificateAndPrivateKey() {
        val bean = AnyTLSBean().apply {
            clientCertificate = CLIENT_CERTIFICATE
            clientKey = CLIENT_PRIVATE_KEY
            initializeDefaultValues()
        }

        val restored = KryoConverters.anyTLSDeserialize(KryoConverters.serialize(bean))

        assertEquals(CLIENT_CERTIFICATE, restored.clientCertificate)
        assertEquals(CLIENT_PRIVATE_KEY, restored.clientKey)
    }

    @Test
    fun outboundTlsJsonUsesPemLineArraysForClientIdentity() {
        val bean = AnyTLSBean().apply {
            allowInsecure = true
            clientCertificate = CLIENT_CERTIFICATE
            clientKey = CLIENT_PRIVATE_KEY
            initializeDefaultValues()
        }

        val tls = buildSingBoxOutboundAnyTLSBean(bean).asMap()["tls"] as Map<*, *>

        assertEquals(
            listOf(
                "-----BEGIN CERTIFICATE-----",
                "client-certificate-fixture",
                "-----END CERTIFICATE-----"
            ),
            tls["client_certificate"]
        )
        assertEquals(
            listOf(
                "-----BEGIN PRIVATE KEY-----",
                "client-private-key-fixture",
                "-----END PRIVATE KEY-----"
            ),
            tls["client_key"]
        )

        val emptyBean = AnyTLSBean().apply {
            allowInsecure = true
            initializeDefaultValues()
        }
        val emptyTls = buildSingBoxOutboundAnyTLSBean(emptyBean).asMap()["tls"] as Map<*, *>
        assertFalse(emptyTls.containsKey("client_certificate"))
        assertFalse(emptyTls.containsKey("client_key"))
    }

    @Test
    fun sharedAnyTlsUrisOmitClientIdentity() {
        val bean = AnyTLSBean().apply {
            serverAddress = "anytls.example"
            serverPort = 443
            password = "share-password"
            name = "share-label"
            sni = "sni.example"
            clientCertificate = CLIENT_CERTIFICATE
            clientKey = CLIENT_PRIVATE_KEY
            initializeDefaultValues()
        }

        val anyTlsUri = bean.toUri()
        assertFalse(anyTlsUri.contains("client-certificate-fixture"))
        assertFalse(anyTlsUri.contains("client-private-key-fixture"))
        val anyTlsImported = parseAnytls(anyTlsUri)
        assertEquals("anytls.example", anyTlsImported.serverAddress)
        assertTrue(anyTlsImported.clientCertificate.isNullOrBlank())
        assertTrue(anyTlsImported.clientKey.isNullOrBlank())
    }

    @Test
    fun versionOneProfileDecodesWithEmptyClientIdentity() {
        val legacy = LegacyAnyTLSBeanV1().apply {
            serverAddress = "legacy.example"
            serverPort = 443
            name = "legacy-profile"
            password = "legacy-password"
            sni = "legacy.example"
            alpn = "h2"
            certificates = "legacy-ca-certificate"
            utlsFingerprint = "chrome"
            echConfig = ""
            realityPubKey = "legacy-reality-public-key"
            realityShortId = "legacy-short-id"
            initializeDefaultValues()
        }

        val restored = KryoConverters.anyTLSDeserialize(KryoConverters.serialize(legacy))

        assertEquals("legacy.example", restored.serverAddress)
        assertEquals(443, restored.serverPort)
        assertEquals("legacy-profile", restored.name)
        assertEquals("legacy-reality-public-key", restored.realityPubKey)
        assertEquals("legacy-short-id", restored.realityShortId)
        assertEquals("", restored.clientCertificate)
        assertEquals("", restored.clientKey)
    }

    /** Writes the pre-mTLS AnyTLSBean v1 profile format as a compatibility fixture. */
    private class LegacyAnyTLSBeanV1 : AnyTLSBean() {
        override fun serialize(output: ByteBufferOutput) {
            output.writeInt(1)
            output.writeString(serverAddress)
            output.writeInt(serverPort)
            output.writeString(password)
            output.writeString(sni)
            output.writeString(alpn)
            output.writeString(certificates)
            output.writeString(utlsFingerprint)
            output.writeBoolean(allowInsecure == true)
            output.writeString(echConfig)
            output.writeString(realityPubKey)
            output.writeString(realityShortId)
        }
    }

    private companion object {
        const val CLIENT_CERTIFICATE = """-----BEGIN CERTIFICATE-----
client-certificate-fixture
-----END CERTIFICATE-----"""
        const val CLIENT_PRIVATE_KEY = """-----BEGIN PRIVATE KEY-----
client-private-key-fixture
-----END PRIVATE KEY-----"""
    }
}
