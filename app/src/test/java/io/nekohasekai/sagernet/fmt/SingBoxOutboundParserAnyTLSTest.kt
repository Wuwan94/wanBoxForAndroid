package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import org.json.JSONArray
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

    @Test
    fun importedStringFormKeepsEveryOtherAnyTlsField() {
        val outbound = JSONObject(
            """
            {
              "type": "anytls",
              "tag": "mtls-string",
              "server": "anytls.example",
              "server_port": 443,
              "password": "test-password",
              "tls": {
                "enabled": true,
                "server_name": "sni.example",
                "alpn": ["h2"],
                "client_certificate": "-----BEGIN CERTIFICATE-----\nCERT\n-----END CERTIFICATE-----",
                "client_key": "-----BEGIN PRIVATE KEY-----\nKEY\n-----END PRIVATE KEY-----"
              }
            }
            """.trimIndent()
        )

        val bean = parseSingBoxOutbound(outbound) as AnyTLSBean

        assertEquals("mtls-string", bean.name)
        assertEquals("anytls.example", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("test-password", bean.password)
        assertEquals("sni.example", bean.sni)
        assertEquals("h2", bean.alpn)
        assertEquals("", bean.certificates)
        assertEquals(false, bean.allowInsecure)
        assertEquals("-----BEGIN CERTIFICATE-----\nCERT\n-----END CERTIFICATE-----", bean.clientCertificate)
        assertEquals("-----BEGIN PRIVATE KEY-----\nKEY\n-----END PRIVATE KEY-----", bean.clientKey)
    }

    @Test
    fun crlfAndSurroundingWhitespaceNormalizeWithoutTouchingPemBody() {
        val bean = parseAnyTlsWithClientIdentity(
            "\r\n  -----BEGIN CERTIFICATE-----\r\nclient-certificate-fixture\r\n-----END CERTIFICATE-----\r\n  ",
            JSONArray(listOf(
                "-----BEGIN PRIVATE KEY-----\r",
                "client-private-key-fixture\n-----END PRIVATE KEY-----",
            ))
        )

        assertEquals(CLIENT_CERTIFICATE, bean.clientCertificate)
        assertEquals(CLIENT_PRIVATE_KEY, bean.clientKey)
    }

    @Test
    fun invalidOrEmptyClientIdentityImportsAsEmptyAndKeepsOtherTlsFields() {
        val invalidValues: List<Any?> = listOf(
            JSONObject.NULL,
            "",
            "   \n  ",
            JSONArray(),
            JSONArray(listOf(1, 2)),
            JSONArray().put("-----BEGIN CERTIFICATE-----").put(JSONObject.NULL).put("-----END CERTIFICATE-----"),
            JSONArray().put("-----BEGIN CERTIFICATE-----").put(JSONObject()).put("-----END CERTIFICATE-----"),
            42,
            true,
            JSONObject().put("pem", "nested"),
        )
        for (value in invalidValues) {
            val bean = parseAnyTlsWithClientIdentity(value, value)
            assertEquals("client_certificate=$value", "", bean.clientCertificate)
            assertEquals("client_key=$value", "", bean.clientKey)
            assertEquals("sni.example", bean.sni)
            assertEquals("h2", bean.alpn)
            assertEquals(CA_CERTIFICATE, bean.certificates)

            val tls = buildSingBoxOutboundAnyTLSBean(bean).asMap()["tls"] as Map<*, *>
            assertFalse(tls.containsKey("client_certificate"))
            assertFalse(tls.containsKey("client_key"))
        }
    }

    @Test
    fun missingTlsOrMissingClientFieldsImportAsEmpty() {
        val noTls = parseSingBoxOutbound(
            JSONObject("""{"type":"anytls","server":"anytls.example","server_port":443,"password":"pw"}""")
        ) as AnyTLSBean
        assertEquals("", noTls.clientCertificate)
        assertEquals("", noTls.clientKey)
        assertEquals("pw", noTls.password)

        val onlyCertificate = parseAnyTlsWithClientIdentity(CLIENT_CERTIFICATE, MISSING)
        assertEquals(CLIENT_CERTIFICATE, onlyCertificate.clientCertificate)
        assertEquals("", onlyCertificate.clientKey)

        val onlyKey = parseAnyTlsWithClientIdentity(MISSING, CLIENT_PRIVATE_KEY)
        assertEquals("", onlyKey.clientCertificate)
        assertEquals(CLIENT_PRIVATE_KEY, onlyKey.clientKey)
    }

    @Test
    fun beanToSingBoxJsonToBeanKeepsEveryTlsField() {
        for (insecure in listOf(false, true)) {
            val original = AnyTLSBean().apply {
                name = "round-trip"
                serverAddress = "anytls.example"
                serverPort = 8443
                password = "round-trip-password"
                sni = "sni.example"
                alpn = "h2\nhttp/1.1"
                certificates = CA_CERTIFICATE
                clientCertificate = CLIENT_CERTIFICATE
                clientKey = CLIENT_PRIVATE_KEY
                allowInsecure = insecure
                utlsFingerprint = "firefox"
                realityPubKey = "reality-public-key"
                realityShortId = "ab12"
                initializeDefaultValues()
            }

            val json = JSONObject(
                buildSingBoxOutboundAnyTLSBean(original) { false }.asMap()
            )
            val restored = parseSingBoxOutbound(json) as AnyTLSBean

            assertEquals(original.serverAddress, restored.serverAddress)
            assertEquals(original.serverPort, restored.serverPort)
            assertEquals(original.password, restored.password)
            assertEquals(original.sni, restored.sni)
            assertEquals(original.alpn, restored.alpn)
            assertEquals(original.certificates, restored.certificates)
            assertEquals(original.clientCertificate, restored.clientCertificate)
            assertEquals(original.clientKey, restored.clientKey)
            assertEquals(original.allowInsecure, restored.allowInsecure)
            assertEquals(original.utlsFingerprint, restored.utlsFingerprint)
            assertEquals(original.realityPubKey, restored.realityPubKey)
            assertEquals(original.realityShortId, restored.realityShortId)
        }
    }

    private fun parseAnyTlsWithClientIdentity(certificate: Any?, key: Any?): AnyTLSBean {
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", "sni.example")
            .put("insecure", true)
            .put("alpn", JSONArray(listOf("h2")))
            .put("certificate", CA_CERTIFICATE)
        if (certificate !== MISSING) tls.put("client_certificate", certificate)
        if (key !== MISSING) tls.put("client_key", key)
        val outbound = JSONObject()
            .put("type", "anytls")
            .put("server", "anytls.example")
            .put("server_port", 443)
            .put("password", "pw")
            .put("tls", tls)
        return parseSingBoxOutbound(outbound) as AnyTLSBean
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
        val MISSING = Any()
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
