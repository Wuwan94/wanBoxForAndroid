// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.ModuleApiClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * 证书钉扎必须用真实 TLS 验证，而不是断言源码字符串。
 *
 * 模块下发的是证书 DER 的 SHA-256 小写十六进制；这里用 keytool 生成自签证书、
 * 起一个真实 TLS 服务，断言「指纹一致能连通、换一张证书必须失败」。
 * @author 雾晚
 */
class ModulePinningTest {

    @Test fun matchingFingerprintConnectsAndWrongFingerprintFails() {
        val toolName = if (System.getProperty("os.name").lowercase().contains("win")) "keytool.exe" else "keytool"
        val keytool = File(File(System.getProperty("java.home"), "bin"), toolName)
        assumeTrue("keytool 不可用，跳过真实 TLS 测试", keytool.canExecute())

        val dir = createTempDir(prefix = "wanbox-pin-")
        var serverSocket: SSLServerSocket? = null
        try {
            val store = File(dir, "server.p12")
            val process = ProcessBuilder(
                keytool.absolutePath, "-genkeypair",
                "-alias", ALIAS, "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=localhost", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", store.absolutePath,
                "-storepass", PASSWORD, "-keypass", PASSWORD,
                "-ext", "SAN=IP:127.0.0.1,DNS:localhost",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals("keytool 生成证书失败: $output", 0, process.waitFor())

            val keyStore = KeyStore.getInstance("PKCS12").apply {
                store.inputStream().use { load(it, PASSWORD.toCharArray()) }
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, PASSWORD.toCharArray())
            }
            val serverContext = SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, null, null)
            }
            serverSocket = serverContext.serverSocketFactory.createServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1")
            ) as SSLServerSocket
            val port = serverSocket.localPort
            thread(isDaemon = true, name = "wanbox-tls") {
                runCatching {
                    serverSocket.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.inputStream.bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val body = """{"schema":1,"ok":true}""".toByteArray()
                        socket.outputStream.write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray()
                        )
                        socket.outputStream.write(body)
                        socket.outputStream.flush()
                    }
                }
            }

            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(keyStore.getCertificate(ALIAS).encoded)
                .joinToString("") { "%02x".format(it) }
            val request = Request.Builder().url("https://127.0.0.1:$port/v1/health").build()

            val good = ModuleApiClient.buildPinnedClient(credentials(port, fingerprint), 5)
            good.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertTrue(response.body?.string().orEmpty().contains("\"ok\":true"))
            }

            val bad = ModuleApiClient.buildPinnedClient(credentials(port, "0".repeat(64)), 5)
            val failed = runCatching { bad.newCall(request).execute().close() }.exceptionOrNull()
            assertTrue("错误指纹必须握手失败，实际: $failed", failed is IOException)

            // 指纹格式必须是 64 位小写十六进制：非法格式在凭据解析阶段就被拒绝。
            val invalid = runCatching {
                ModuleApiClient.parseCredentialText(
                    """{"listen":"127.0.0.1:$port","token":"${"a".repeat(64)}","certSha256":"xyz"}"""
                )
            }.exceptionOrNull()
            assertEquals("module_credentials_invalid", invalid?.message)
        } finally {
            runCatching { serverSocket?.close() }
            dir.deleteRecursively()
        }
    }

    private fun credentials(port: Int, certSha256: String) = ModuleApiClient.Credentials(
        baseUrl = "https://127.0.0.1:$port",
        token = "a".repeat(64),
        certSha256 = certSha256,
    )

    private companion object {
        const val ALIAS = "wanbox"
        const val PASSWORD = "changeit"
    }
}
