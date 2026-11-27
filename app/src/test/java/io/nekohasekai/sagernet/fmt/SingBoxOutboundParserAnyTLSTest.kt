package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实导入路径：sing-box JSON outbound → parseSingBoxOutbound() → AnyTLSBean（Kryo 入库）
 * → buildSingBoxOutboundAnyTLSBean() → 生成给核心的 outbound。
 * mTLS 客户端证书 / 私钥必须完整走通这条链。
 */
// 样例均带 insecure=true：生成时走 bean.allowInsecure 短路，避免 JVM 单测初始化 DataStore（同 AnyTLSFmtTest）。
class SingBoxOutboundParserAnyTLSTest {

    @Test
    fun importedStringFormClientIdentitySurvivesParseStoreAndBuild() {
        val outbound = JSONObject(
            """
            {
              "type": "anytls",
              "tag": "mtls-string",
              "server": "anytls.example",
              "server_port": 443,
              "password": "pw",
              "tls": {
                "enabled": true,
                "server_name": "anytls.example",
                "insecure": true,
                "client_certificate": ${JSONObject.quote(CLIENT_CERTIFICATE)},
                "client_key": ${JSONObject.quote(CLIENT_PRIVATE_KEY)}
              }
            }
            """.trimIndent()
        )

        assertClientIdentityRoundTrips(outbound)
    }

    @Test
    fun importedArrayFormClientIdentitySurvivesParseStoreAndBuild() {
        val outbound = JSONObject(
            """
            {
              "type": "anytls",
              "tag": "mtls-array",
              "server": "anytls.example",
              "server_port": 443,
              "password": "pw",
              "tls": {
                "enabled": true,
                "server_name": "anytls.example",
                "insecure": true,
                "client_certificate": [
                  "-----BEGIN CERTIFICATE-----",
                  "client-certificate-fixture",
                  "-----END CERTIFICATE-----"
                ],
                "client_key": [
                  "-----BEGIN PRIVATE KEY-----",
                  "client-private-key-fixture",
                  "-----END PRIVATE KEY-----"
                ]
              }
            }
            """.trimIndent()
        )

        assertClientIdentityRoundTrips(outbound)
    }

    @Test
    fun importWithoutClientIdentityBuildsUnchangedTls() {
        val outbound = JSONObject(
            """
            {
              "type": "anytls",
              "tag": "plain",
              "server": "anytls.example",
              "server_port": 443,
              "password": "pw",
              "tls": {
                "enabled": true,
                "server_name": "sni.example",
                "insecure": true,
                "alpn": ["h2", "http/1.1"],
                "certificate": ${JSONObject.quote(CA_CERTIFICATE)},
                "utls": { "enabled": true, "fingerprint": "chrome" }
              }
            }
            """.trimIndent()
        )

        val bean = parseSingBoxOutbound(outbound) as AnyTLSBean
        assertEquals("", bean.clientCertificate)
        assertEquals("", bean.clientKey)

        val tls = buildSingBoxOutboundAnyTLSBean(bean).asMap()["tls"] as Map<*, *>
        assertFalse(tls.containsKey("client_certificate"))
        assertFalse(tls.containsKey("client_key"))
        assertEquals(
            setOf("enabled", "server_name", "insecure", "alpn", "certificate", "utls"),
            tls.keys
        )
        assertEquals("sni.example", tls["server_name"])
        assertEquals(listOf("h2", "http/1.1"), tls["alpn"])
        assertEquals(CA_CERTIFICATE, tls["certificate"])
        val utls = tls["utls"] as Map<*, *>
        assertEquals(true, utls["enabled"])
        assertEquals("chrome", utls["fingerprint"])
    }

    private fun assertClientIdentityRoundTrips(outbound: JSONObject) {
        val parsed = parseSingBoxOutbound(outbound)
        assertNotNull(parsed)
        assertTrue(parsed is AnyTLSBean)
        parsed as AnyTLSBean
        assertEquals(CLIENT_CERTIFICATE, parsed.clientCertificate)
        assertEquals(CLIENT_PRIVATE_KEY, parsed.clientKey)

        // 入库（Kryo）再读出，等价于订阅/备份导入后的存储
        val stored = KryoConverters.anyTLSDeserialize(KryoConverters.serialize(parsed))

        val tls = buildSingBoxOutboundAnyTLSBean(stored).asMap()["tls"] as Map<*, *>
        assertEquals(CLIENT_CERTIFICATE, (tls["client_certificate"] as List<*>).joinToString("\n"))
        assertEquals(CLIENT_PRIVATE_KEY, (tls["client_key"] as List<*>).joinToString("\n"))
    }

    private companion object {
        const val CLIENT_CERTIFICATE = "-----BEGIN CERTIFICATE-----\n" +
            "client-certificate-fixture\n" +
            "-----END CERTIFICATE-----"
        const val CLIENT_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\n" +
            "client-private-key-fixture\n" +
            "-----END PRIVATE KEY-----"
        const val CA_CERTIFICATE = "-----BEGIN CERTIFICATE-----\n" +
            "ca-fixture\n" +
            "-----END CERTIFICATE-----"
    }
}
