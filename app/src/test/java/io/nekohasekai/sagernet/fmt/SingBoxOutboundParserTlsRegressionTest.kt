package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享 TLS 解析（parseSingBoxTLS / PEM 字段规范化）对非 AnyTLS 协议的回归守卫：
 * SNI、ALPN、CA 证书、insecure、uTLS、Reality、ECH 的导入结果不能因 mTLS 修复而改变，
 * 其他协议也不会凭空拿到客户端证书。
 */
class SingBoxOutboundParserTlsRegressionTest {

    @Test
    fun vlessRealityAndUtlsStayIntact() {
        val bean = parse(
            """
            {"type":"vless","tag":"vless","server":"vless.example","server_port":443,
             "uuid":"11111111-1111-1111-1111-111111111111","flow":"xtls-rprx-vision",
             "tls":{"enabled":true,"server_name":"reality.example",
               "utls":{"enabled":true,"fingerprint":"firefox"},
               "reality":{"enabled":true,"public_key":"reality-public-key","short_id":"ab12"},
               "client_certificate":${JSONObject.quote(CLIENT_CERTIFICATE)},
               "client_key":${JSONObject.quote(CLIENT_PRIVATE_KEY)}}}
            """
        ) as VMessBean

        assertTrue(bean.isVLESS)
        assertEquals("tls", bean.security)
        assertEquals("reality.example", bean.sni)
        assertEquals("firefox", bean.utlsFingerprint)
        assertEquals("reality-public-key", bean.realityPubKey)
        assertEquals("ab12", bean.realityShortId)
        assertEquals("", bean.certificates)
        assertNoClientIdentityLeaked(bean)
    }

    @Test
    fun vmessTlsKeepsAlpnInsecureAndArrayCaCertificate() {
        val bean = parse(
            """
            {"type":"vmess","tag":"vmess","server":"vmess.example","server_port":443,
             "uuid":"22222222-2222-2222-2222-222222222222",
             "tls":{"enabled":true,"server_name":"vmess.example","insecure":true,
               "alpn":["h2","http/1.1"],
               "certificate":["-----BEGIN CERTIFICATE-----","ca-fixture","-----END CERTIFICATE-----"]}}
            """
        ) as VMessBean

        assertFalse(bean.isVLESS)
        assertEquals("tls", bean.security)
        assertEquals("vmess.example", bean.sni)
        assertEquals("h2\nhttp/1.1", bean.alpn)
        assertEquals(true, bean.allowInsecure)
        assertEquals(CA_CERTIFICATE, bean.certificates)
    }

    @Test
    fun trojanTlsKeepsStringCaCertificateAndEch() {
        val bean = parse(
            """
            {"type":"trojan","tag":"trojan","server":"trojan.example","server_port":443,"password":"pw",
             "tls":{"enabled":true,"server_name":"trojan.example",
               "certificate":${JSONObject.quote(CA_CERTIFICATE)},
               "ech":{"enabled":true,"config":["-----BEGIN ECH CONFIGS-----","ech-fixture","-----END ECH CONFIGS-----"],
                      "query_server_name":"ech.example"}}}
            """
        ) as TrojanBean

        assertEquals("tls", bean.security)
        assertEquals("trojan.example", bean.sni)
        assertEquals(CA_CERTIFICATE, bean.certificates)
        assertEquals(true, bean.enableECH)
        assertEquals("-----BEGIN ECH CONFIGS-----\nech-fixture\n-----END ECH CONFIGS-----", bean.echConfig)
        assertEquals("ech.example", bean.echQueryServerName)
    }

    @Test
    fun httpTlsKeepsSniAndCertificate() {
        val bean = parse(
            """
            {"type":"http","tag":"http","server":"http.example","server_port":443,
             "username":"user","password":"pw",
             "tls":{"enabled":true,"server_name":"http.example","certificate":${JSONObject.quote(CA_CERTIFICATE)}}}
            """
        ) as HttpBean

        assertEquals("tls", bean.security)
        assertEquals("http.example", bean.sni)
        assertEquals(CA_CERTIFICATE, bean.certificates)
        assertEquals("user", bean.username)
    }

    @Test
    fun hysteriaAndHysteria2KeepTlsFields() {
        val hysteria = parse(
            """
            {"type":"hysteria","tag":"hy","server":"hy.example","server_port":443,"auth_str":"secret",
             "tls":{"enabled":true,"server_name":"hy.example","alpn":["hysteria"],"insecure":true,
               "certificate":${JSONObject.quote(CA_CERTIFICATE)}}}
            """
        ) as HysteriaBean
        assertEquals(1, hysteria.protocolVersion)
        assertEquals("hy.example", hysteria.sni)
        assertEquals("hysteria", hysteria.alpn)
        assertEquals(true, hysteria.allowInsecure)
        assertEquals(CA_CERTIFICATE, hysteria.caText)

        val hysteria2 = parse(
            """
            {"type":"hysteria2","tag":"hy2","server":"hy2.example","server_port":443,"password":"pw",
             "tls":{"enabled":true,"server_name":"hy2.example",
               "certificate":["-----BEGIN CERTIFICATE-----","ca-fixture","-----END CERTIFICATE-----"]}}
            """
        ) as HysteriaBean
        assertEquals(2, hysteria2.protocolVersion)
        assertEquals("hy2.example", hysteria2.sni)
        assertEquals(false, hysteria2.allowInsecure)
        assertEquals(CA_CERTIFICATE, hysteria2.caText)
    }

    @Test
    fun disabledTlsStillMeansNoTls() {
        val bean = parse(
            """
            {"type":"vmess","tag":"plain","server":"vmess.example","server_port":80,
             "uuid":"33333333-3333-3333-3333-333333333333",
             "tls":{"enabled":false,"server_name":"ignored.example"}}
            """
        ) as VMessBean
        assertEquals("none", bean.security)
        assertEquals("", bean.sni)

        val anyTls = parse(
            """
            {"type":"anytls","tag":"no-tls","server":"anytls.example","server_port":443,"password":"pw",
             "tls":{"enabled":false,"client_key":${JSONObject.quote(CLIENT_PRIVATE_KEY)}}}
            """
        ) as AnyTLSBean
        assertEquals("", anyTls.clientKey)
        assertEquals("", anyTls.clientCertificate)
    }

    private fun parse(json: String): AbstractBean {
        val bean = parseSingBoxOutbound(JSONObject(json.trimIndent()))
        assertTrue(bean != null)
        return bean!!
    }

    private fun assertNoClientIdentityLeaked(bean: VMessBean) {
        assertFalse(bean.certificates.orEmpty().contains("client-certificate-fixture"))
        val stored = String(KryoConverters.serialize(bean), Charsets.ISO_8859_1)
        assertFalse(stored.contains("client-private-key-fixture"))
        assertFalse(stored.contains("client-certificate-fixture"))
    }

    private companion object {
        const val CLIENT_CERTIFICATE = "-----BEGIN CERTIFICATE-----\nclient-certificate-fixture\n-----END CERTIFICATE-----"
        const val CLIENT_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\nclient-private-key-fixture\n-----END PRIVATE KEY-----"
        const val CA_CERTIFICATE = "-----BEGIN CERTIFICATE-----\nca-fixture\n-----END CERTIFICATE-----"
    }
}
