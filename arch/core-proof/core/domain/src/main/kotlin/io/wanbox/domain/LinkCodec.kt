package io.wanbox.domain

import io.wanbox.contracts.dto.ProfileSpec
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * 分享链接编解码（对应原 app 的 fmt/ 中纯解析部分）。
 *
 * 为什么放在 core：链接格式是业务规则，不是平台能力。
 * 为什么不是直接搬运 fmt/：原实现依赖 okhttp3.HttpUrl、gson、org.json 与 SingBoxOptions
 * （用于生成 sing-box 配置），那些属于适配层；这里只保留"链接 <-> ProfileSpec"的可移植部分，
 * 用 JDK 自带 Base64 与自带解析器实现，保证 :core 零第三方依赖。
 *
 * 支持：ss（SIP002 与旧式 base64）、vmess（base64 JSON）、trojan、vless、socks/socks4/socks4a、http/https。
 */
object LinkCodec {

    fun parse(raw: String): ProfileSpec? {
        val link = raw.trim()
        if (link.isEmpty() || link.startsWith("#") || link.startsWith("//")) return null
        if (!link.contains("://")) return null
        return try {
            when (link.substringBefore("://").lowercase()) {
                "ss" -> parseShadowsocks(link)
                "vmess" -> parseVmess(link)
                "trojan" -> parseServerLink(link, "trojan")
                "vless" -> parseServerLink(link, "vless")
                "socks" -> parseSocks(link, "socks")
                "socks4" -> parseSocks(link, "socks4")
                "socks4a" -> parseSocks(link, "socks4a")
                "http", "https" -> parseHttp(link)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 反向导出，用于 UC-DATA-01 导出后能被 UC-DATA-02 / 导入链路读回。 */
    fun toUri(spec: ProfileSpec): String? {
        val host = spec.server
        val port = spec.port
        if (host.isEmpty() || port <= 0) return null
        val suffix = if (spec.name.isBlank()) "" else "#" + quote(spec.name)
        return when (spec.protocol.lowercase()) {
            "shadowsocks" -> {
                val secret = "${spec.fields["method"].orEmpty()}:${spec.fields["password"].orEmpty()}"
                val userInfo = Base64.getUrlEncoder().withoutPadding().encodeToString(secret.toByteArray(Charsets.UTF_8))
                "ss://$userInfo@$host:$port$suffix"
            }
            "vmess" -> {
                val json = buildString {
                    append('{')
                    append("\"v\":\"2\",")
                    append("\"ps\":\"").append(escapeJson(spec.name)).append("\",")
                    append("\"add\":\"").append(escapeJson(host)).append("\",")
                    append("\"port\":\"").append(port).append("\",")
                    append("\"id\":\"").append(escapeJson(spec.fields["uuid"].orEmpty())).append("\",")
                    append("\"aid\":\"").append(escapeJson(spec.fields["alterId"] ?: "0")).append("\",")
                    append("\"net\":\"").append(escapeJson(spec.fields["network"] ?: "tcp")).append("\",")
                    append("\"type\":\"none\",")
                    append("\"host\":\"").append(escapeJson(spec.fields["host"].orEmpty())).append("\",")
                    append("\"path\":\"").append(escapeJson(spec.fields["path"].orEmpty())).append("\",")
                    append("\"tls\":\"").append(escapeJson(spec.fields["tls"].orEmpty())).append("\"")
                    append('}')
                }
                "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
            }
            "trojan" -> "trojan://${quote(spec.fields["password"].orEmpty())}@$host:$port$suffix"
            "vless" -> "vless://${quote(spec.fields["uuid"].orEmpty())}@$host:$port$suffix"
            "socks", "socks4", "socks4a" -> {
                val user = spec.fields["username"].orEmpty()
                val pass = spec.fields["password"].orEmpty()
                val auth = if (user.isEmpty() && pass.isEmpty()) "" else "${quote(user)}:${quote(pass)}@"
                "${spec.protocol.lowercase()}://$auth$host:$port$suffix"
            }
            "http", "https" -> {
                val user = spec.fields["username"].orEmpty()
                val pass = spec.fields["password"].orEmpty()
                val auth = if (user.isEmpty()) "" else "${quote(user)}:${quote(pass)}@"
                val scheme = if (spec.fields["tls"].isNullOrBlank()) "http" else "https"
                "$scheme://$auth$host:$port$suffix"
            }
            else -> null
        }
    }

    // ---------------- 各协议 ----------------

    private fun parseShadowsocks(link: String): ProfileSpec? {
        val body = link.substringAfter("://")
        val fragment = if (body.contains('#')) unquote(body.substringAfter("#")) else null
        val core = body.substringBefore("#")

        if (core.contains('@')) {
            // SIP002：userinfo 可能是 base64(method:password)，也可能是明文
            val userInfo = core.substringBeforeLast("@")
            val hostPart = core.substringAfterLast("@").substringBefore("?")
            val query = if (core.contains("?")) core.substringAfter("?") else ""
            val decoded = decodeBase64(userInfo) ?: userInfo
            val method = decoded.substringBefore(":")
            val password = decoded.substringAfter(":", "")
            if (method.isEmpty()) return null
            val hostPort = hostPort(hostPart) ?: return null
            val fields = mutableMapOf(
                "server" to hostPort.first,
                "port" to hostPort.second.toString(),
                "method" to method,
                "password" to password,
            )
            if (query.isNotEmpty()) fields["plugin"] = unquote(query)
            return ProfileSpec("shadowsocks", fragment ?: "${hostPort.first}:${hostPort.second}", fields)
        }

        // 旧式：整段 base64(method:password@host:port)
        val decoded = decodeBase64(core) ?: return null
        val at = decoded.lastIndexOf('@')
        if (at < 0) return null
        val credentials = decoded.substring(0, at)
        val method = credentials.substringBefore(":")
        val password = credentials.substringAfter(":", "")
        if (method.isEmpty()) return null
        val hostPort = hostPort(decoded.substring(at + 1)) ?: return null
        return ProfileSpec(
            "shadowsocks",
            fragment ?: "${hostPort.first}:${hostPort.second}",
            mapOf(
                "server" to hostPort.first,
                "port" to hostPort.second.toString(),
                "method" to method,
                "password" to password,
            ),
        )
    }

    private fun parseVmess(link: String): ProfileSpec? {
        val payload = link.substringAfter("://").substringBefore("#")
        val json = decodeBase64(payload) ?: return null
        val obj = MiniJson.parseObject(json) ?: return null
        val host = obj["add"]?.takeIf { it.isNotBlank() } ?: return null
        val port = obj["port"]?.toIntOrNull() ?: return null
        val uuid = obj["id"]?.takeIf { it.isNotBlank() } ?: return null
        val name = obj["ps"]?.takeIf { it.isNotBlank() } ?: "$host:$port"
        val fields = mutableMapOf(
            "server" to host,
            "port" to port.toString(),
            "uuid" to uuid,
            "alterId" to (obj["aid"] ?: "0"),
            "security" to (obj["scy"] ?: obj["security"] ?: "auto"),
            "network" to (obj["net"] ?: "tcp"),
            "tls" to (obj["tls"] ?: ""),
        )
        obj["host"]?.takeIf { it.isNotBlank() }?.let { fields["host"] = it }
        obj["path"]?.takeIf { it.isNotBlank() }?.let { fields["path"] = it }
        obj["sni"]?.takeIf { it.isNotBlank() }?.let { fields["sni"] = it }
        return ProfileSpec("vmess", name, fields)
    }

    /** trojan / vless 共用：userinfo 即凭据，参数走 query。 */
    private fun parseServerLink(link: String, protocol: String): ProfileSpec? {
        val parts = urlParts(link) ?: return null
        val credential = parts.userInfo?.let { unquote(it) }?.takeIf { it.isNotBlank() } ?: return null
        if (parts.port <= 0) return null
        val credentialKey = if (protocol == "trojan") "password" else "uuid"
        val fields = mutableMapOf(
            "server" to parts.host,
            "port" to parts.port.toString(),
            credentialKey to credential,
        )
        parts.query["sni"]?.let { fields["sni"] = it }
        parts.query["peer"]?.let { fields["sni"] = it }
        parts.query["allowInsecure"]?.let { fields["allowInsecure"] = it }
        parts.query["security"]?.let { fields["security"] = it }
        parts.query["type"]?.let { fields["network"] = it }
        parts.query["host"]?.let { fields["host"] = it }
        parts.query["path"]?.let { fields["path"] = it }
        return ProfileSpec(protocol, parts.fragment ?: "${parts.host}:${parts.port}", fields)
    }

    private fun parseSocks(link: String, protocol: String): ProfileSpec? {
        val parts = urlParts(link) ?: return null
        if (parts.port <= 0) return null
        val fields = mutableMapOf("server" to parts.host, "port" to parts.port.toString())
        var user = parts.userInfo?.let { unquote(it) }
        // v2rayN 习惯：把 base64(user:pass) 塞在 username 位
        if (user != null && !user.contains(':')) {
            decodeBase64(user)?.let { if (it.contains(':')) user = it }
        }
        if (!user.isNullOrBlank()) {
            fields["username"] = user.substringBefore(':')
            fields["password"] = user.substringAfter(':', "")
        }
        return ProfileSpec(protocol, parts.fragment ?: "${parts.host}:${parts.port}", fields)
    }

    private fun parseHttp(link: String): ProfileSpec? {
        val parts = urlParts(link) ?: return null
        if (parts.port <= 0) return null
        val fields = mutableMapOf(
            "server" to parts.host,
            "port" to parts.port.toString(),
            "tls" to if (parts.scheme == "https") "tls" else "",
        )
        var user = parts.userInfo?.let { unquote(it) }
        if (user != null && !user.contains(':')) {
            decodeBase64(user)?.let { if (it.contains(':')) user = it }
        }
        if (!user.isNullOrBlank()) {
            fields["username"] = user.substringBefore(':')
            fields["password"] = user.substringAfter(':', "")
        }
        return ProfileSpec("http", parts.fragment ?: "${parts.host}:${parts.port}", fields)
    }

    // ---------------- 拆解工具 ----------------

    private data class UrlParts(
        val scheme: String,
        val userInfo: String?,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val fragment: String?,
    )

    private fun urlParts(link: String): UrlParts? {
        val scheme = link.substringBefore("://").lowercase()
        var rest = link.substringAfter("://")
        var fragment: String? = null
        if (rest.contains('#')) {
            fragment = unquote(rest.substringAfter('#')).takeIf { it.isNotBlank() }
            rest = rest.substringBefore('#')
        }
        var query: Map<String, String> = emptyMap()
        if (rest.contains('?')) {
            query = rest.substringAfter('?')
                .split('&')
                .mapNotNull { pair ->
                    if (!pair.contains('=')) null
                    else unquote(pair.substringBefore('=')) to unquote(pair.substringAfter('='))
                }
                .toMap()
            rest = rest.substringBefore('?')
        }
        val at = rest.lastIndexOf('@')
        val userInfo = if (at >= 0) rest.substring(0, at) else null
        val hostPortText = if (at >= 0) rest.substring(at + 1) else rest
        val hostPort = hostPort(hostPortText) ?: return null
        return UrlParts(scheme, userInfo, hostPort.first, hostPort.second, query, fragment)
    }

    /** 处理 IPv6 字面量（[::1]:443）与普通 host:port。 */
    internal fun hostPort(text: String): Pair<String, Int>? {
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end < 0) return null
            val host = text.substring(1, end)
            val tail = text.substring(end + 1)
            val port = if (tail.startsWith(":")) tail.substring(1).toIntOrNull() ?: 0 else 0
            return if (host.isEmpty()) null else host to port
        }
        val host = text.substringBeforeLast(":", "")
        val port = text.substringAfterLast(":", "").toIntOrNull() ?: 0
        return if (host.isEmpty()) null else host to port
    }

    internal fun decodeBase64(text: String): String? {
        val normalized = text.trim().replace('-', '+').replace('_', '/')
        val padded = when (normalized.length % 4) {
            0 -> normalized
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> return null
        }
        return try {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun quote(text: String): String =
        URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun unquote(text: String): String =
        try {
            URLDecoder.decode(text, "UTF-8")
        } catch (e: Exception) {
            text
        }

    private fun escapeJson(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append(" ") else append(ch)
            }
        }
    }
}

/**
 * 极简 JSON 读取器：只用于 vmess 链接这类扁平对象。
 * 手写而不是引第三方库，是为了让 :core 维持零依赖；遇到嵌套结构按 null 处理。
 */
internal object MiniJson {

    fun parseObject(text: String): Map<String, String>? {
        val parser = Parser(text)
        val value = try {
            parser.readValue()
        } catch (e: Exception) {
            return null
        }
        val map = value as? Map<*, *> ?: return null
        parser.skipWhitespace()
        if (!parser.atEnd()) return null
        val out = LinkedHashMap<String, String>()
        for ((k, v) in map) {
            if (k !is String) continue
            when (v) {
                is String -> out[k] = v
                is Double -> out[k] = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
                is Boolean -> out[k] = v.toString()
                else -> Unit
            }
        }
        return out
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw IllegalStateException("unexpected end")
            return when (text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> readNumber()
            }
        }

        private fun expect(literal: String) {
            if (!text.startsWith(literal, index)) throw IllegalStateException("expect $literal")
            index += literal.length
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            index++ // {
            skipWhitespace()
            if (index < text.length && text[index] == '}') { index++; return out }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                if (index >= text.length || text[index] != ':') throw IllegalStateException("expect :")
                index++
                out[key] = readValue()
                skipWhitespace()
                if (atEnd()) throw IllegalStateException("unexpected end in object")
                when (text[index]) {
                    ',' -> index++
                    '}' -> { index++; return out }
                    else -> throw IllegalStateException("expect , or }")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            index++ // [
            skipWhitespace()
            if (index < text.length && text[index] == ']') { index++; return out }
            while (true) {
                out.add(readValue())
                skipWhitespace()
                if (atEnd()) throw IllegalStateException("unexpected end in array")
                when (text[index]) {
                    ',' -> index++
                    ']' -> { index++; return out }
                    else -> throw IllegalStateException("expect , or ]")
                }
            }
        }

        private fun readString(): String {
            if (index >= text.length || text[index] != '"') throw IllegalStateException("expect string")
            index++
            val sb = StringBuilder()
            while (index < text.length) {
                val ch = text[index++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (index >= text.length) throw IllegalStateException("bad escape")
                        when (val esc = text[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw IllegalStateException("bad unicode")
                                sb.append(text.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IllegalStateException("bad escape $esc")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalStateException("unterminated string")
        }

        private fun readNumber(): Double {
            val start = index
            if (index < text.length && (text[index] == '-' || text[index] == '+')) index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
            if (start == index) throw IllegalStateException("expect number")
            return text.substring(start, index).toDouble()
        }
    }
}
