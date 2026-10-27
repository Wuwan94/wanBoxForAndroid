package io.wanbox.tests

import io.wanbox.domain.LinkCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 分享链接编解码（原 fmt/ 中与平台无关的部分） */
class LinkCodecTest {

    @Test
    fun `ss SIP002 userinfo 为 base64`() {
        val spec = LinkCodec.parse(Links.shadowsocks(host = "hk1.example", port = 8388))
        assertNotNull(spec)
        assertEquals("shadowsocks", spec!!.protocol)
        assertEquals("hk1.example", spec.fields["server"])
        assertEquals("8388", spec.fields["port"])
        assertEquals("aes-256-gcm", spec.fields["method"])
        assertEquals("pass", spec.fields["password"])
        assertEquals("香港01", spec.name)
    }

    @Test
    fun `ss SIP002 userinfo 为明文`() {
        val spec = LinkCodec.parse("ss://aes-128-gcm:pw@plain.example:8389#明文")
        assertNotNull(spec)
        assertEquals("plain.example", spec!!.fields["server"])
        assertEquals("pw", spec.fields["password"])
    }

    @Test
    fun `ss 旧式整段 base64`() {
        val legacy = "ss://" + Links.base64("aes-256-gcm:secret@old.example:8390")
        val spec = LinkCodec.parse(legacy)
        assertNotNull(spec, "旧式链接应能解析")
        assertEquals("old.example", spec!!.fields["server"])
        assertEquals("8390", spec.fields["port"])
        assertEquals("secret", spec.fields["password"])
    }

    @Test
    fun `vmess base64 JSON`() {
        val spec = LinkCodec.parse(Links.vmess(host = "jp1.example", port = 443, name = "日本01"))
        assertNotNull(spec)
        assertEquals("vmess", spec!!.protocol)
        assertEquals("jp1.example", spec.fields["server"])
        assertEquals("443", spec.fields["port"])
        assertEquals("11111111-2222-3333-4444-555555555555", spec.fields["uuid"])
        assertEquals("ws", spec.fields["network"])
        assertEquals("tls", spec.fields["tls"])
        assertEquals("日本01", spec.name)
    }

    @Test
    fun `trojan 带 sni 参数`() {
        val spec = LinkCodec.parse("trojan://pw@tr.example:443?sni=tr.example&allowInsecure=1#东京")
        assertNotNull(spec)
        assertEquals("trojan", spec!!.protocol)
        assertEquals("pw", spec.fields["password"])
        assertEquals("tr.example", spec.fields["sni"])
        assertEquals("东京", spec.name)
    }

    @Test
    fun `vless 用 uuid 作为凭据`() {
        val spec = LinkCodec.parse("vless://11111111-2222-3333-4444-555555555555@v.example:443?security=tls#VL")
        assertNotNull(spec)
        assertEquals("vless", spec!!.protocol)
        assertEquals("11111111-2222-3333-4444-555555555555", spec.fields["uuid"])
        assertEquals("tls", spec.fields["security"])
    }

    @Test
    fun `socks 的 v2rayN 风格 base64 凭据`() {
        val creds = Links.base64("user:pass")
        val spec = LinkCodec.parse("socks://$creds@s.example:1080#S")
        assertNotNull(spec)
        assertEquals("socks", spec!!.protocol)
        assertEquals("user", spec.fields["username"])
        assertEquals("pass", spec.fields["password"])
    }

    @Test
    fun `socks4a 保留协议区分`() {
        val spec = LinkCodec.parse("socks4a://s.example:1080")
        assertNotNull(spec)
        assertEquals("socks4a", spec!!.protocol)
    }

    @Test
    fun `http 与 https 用 tls 字段区分`() {
        val plain = LinkCodec.parse("http://h.example:8080")
        val tls = LinkCodec.parse("https://h.example:8443#H")
        assertNotNull(plain)
        assertNotNull(tls)
        assertEquals("", plain!!.fields["tls"])
        assertEquals("tls", tls!!.fields["tls"])
    }

    @Test
    fun `IPv6 字面量不被端口解析吃掉`() {
        val spec = LinkCodec.parse("trojan://pw@[2001:db8::1]:443#V6")
        assertNotNull(spec)
        assertEquals("2001:db8::1", spec!!.fields["server"])
        assertEquals("443", spec.fields["port"])
    }

    @Test
    fun `非法输入返回 null 而不是抛异常`() {
        assertNull(LinkCodec.parse(""))
        assertNull(LinkCodec.parse("   "))
        assertNull(LinkCodec.parse("#注释"))
        assertNull(LinkCodec.parse("just some text"))
        assertNull(LinkCodec.parse("vmess://not-base64!!!"))
        assertNull(LinkCodec.parse("ss://"))
    }

    @Test
    fun `ss 导出后可再解析`() {
        val original = LinkCodec.parse(Links.shadowsocks(host = "rt.example", port = 8388, name = "往返"))!!
        val uri = LinkCodec.toUri(original)
        assertNotNull(uri)
        val again = LinkCodec.parse(uri!!)
        assertNotNull(again)
        assertEquals(original.fields["server"], again!!.fields["server"])
        assertEquals(original.fields["port"], again.fields["port"])
        assertEquals(original.fields["password"], again.fields["password"])
        assertEquals("往返", again.name)
    }

    @Test
    fun `vmess 导出后可再解析`() {
        val original = LinkCodec.parse(Links.vmess(host = "rt2.example", name = "往返2"))!!
        val again = LinkCodec.parse(LinkCodec.toUri(original)!!)
        assertNotNull(again)
        assertEquals("rt2.example", again!!.fields["server"])
        assertEquals(original.fields["uuid"], again.fields["uuid"])
    }

    @Test
    fun `未知协议不猜`() {
        assertNull(LinkCodec.parse("wireguard://whatever"))
        assertTrue(LinkCodec.toUri(io.wanbox.contracts.dto.ProfileSpec("mystery", "x", mapOf("server" to "a", "port" to "1"))) == null)
    }
}
