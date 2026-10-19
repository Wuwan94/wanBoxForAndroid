// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.ModuleApiClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * 证书钉扎必须用真实 TLS 验证，而不是断言源码字符串。
 *
 * 模块下发的是证书 DER 的 SHA-256 小写十六进制；这里用 keytool 生成自签证书、
 * 起一个真实 TLS 服务，断言「指纹一致能连通、换一张证书必须失败」。
 *
 * 服务端必须**循环 accept**直到测试结束：只 accept 一次的话，错指纹的请求没人处理，
 * 客户端失败原因变成读超时也能满足 IOException 断言，测试就是假阳性。
 * 这里额外统计成功/失败握手次数，证明失败来自 TLS 握手而不是超时。
 * @author 雾晚
 */
class ModulePinningTest {

    @Test fun matchingFingerprintConnectsAndWrongFingerprintFails() {
        val toolName = if (System.getProperty("os.name").lowercase().contains("win")) "keytool.exe" else "keytool"
        val keytool = File(File(System.getProperty("java.home"), "bin"), toolName)
        assumeTrue("keytool 不可用，跳过真实 TLS 测试", keytool.canExecute())

        val dir = createTempDir(prefix = "wanbox-pin-")
        var serverSocket: SSLServerSocket? = null
        val serving = AtomicBoolean(true)
        val acceptedHandshakes = AtomicInteger(0)
        val failedHandshakes = AtomicInteger(0)
        // 服务端观察到的最后一次握手异常，用于失败时定位原因。
        val lastHandshakeError = AtomicReference<Throwable?>(null)
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
                0, 8, InetAddress.getByName("127.0.0.1")
            ) as SSLServerSocket
            val port = serverSocket.localPort
            // accept 带超时，循环才能在测试结束时退出。
            serverSocket.soTimeout = 200
            val socket = serverSocket
            thread(isDaemon = true, name = "wanbox-tls") {
                while (serving.get()) {
                    val accepted = runCatching { socket.accept() }
                    val client = accepted.getOrNull()
                    if (client == null) {
                        val error = accepted.exceptionOrNull()
                        when {
                            !serving.get() -> break
                            // accept 超时只是为了让循环有机会检查 serving 标记。
                            error is SocketTimeoutException -> continue
                            error is SocketException && socket.isClosed -> break
                            else -> continue
                        }
                    }
                    // accept 只完成 TCP 连接，真正的 TLS 握手发生在第一次 I/O；
                    // 必须显式 startHandshake 才能把「握手失败」和「读请求失败」分开统计。
                    val handshake = runCatching { (client as SSLSocket).startHandshake() }
                    if (handshake.isFailure) {
                        failedHandshakes.incrementAndGet()
                        lastHandshakeError.set(handshake.exceptionOrNull())
                        runCatching { client.close() }
                        continue
                    }
                    runCatching {
                        client.use {
                            it.soTimeout = 5_000
                            val reader = it.inputStream.bufferedReader()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val body = """{"schema":1,"ok":true}""".toByteArray()
                            it.outputStream.write(
                                ("HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray()
                            )
                            it.outputStream.write(body)
                            it.outputStream.flush()
                        }
                    }
                    acceptedHandshakes.incrementAndGet()
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
            // 客户端读到完整响应就返回了，服务端记账在另一个线程：
            // 必须轮询等待，立即断言会在 CI 上偶发假阴性（这就是这次的竞态）。
            awaitUntil("指纹一致时必须完成一次成功握手") { acceptedHandshakes.get() >= 1 }
            assertEquals("成功握手不得重复计数", 1, acceptedHandshakes.get())

            val bad = ModuleApiClient.buildPinnedClient(credentials(port, "0".repeat(64)), 5)
            val failed = runCatching { bad.newCall(request).execute().close() }.exceptionOrNull()
            assertTrue("错误指纹必须握手失败，实际: $failed", failed is SSLException)
            assertFalse("不能把读超时当成握手失败", failed is SocketTimeoutException)
            // 服务端线程与客户端失败几乎同时发生：轮询等待它记账，避免竞态导致的假阴性。
            awaitUntil("服务端必须观察到一次失败握手（最后异常: ${lastHandshakeError.get()}）") {
                failedHandshakes.get() >= 1
            }
            // 失败发生在握手阶段，服务端不会把这次连接当成可用请求。
            assertEquals("失败握手不得计入成功连接", 1, acceptedHandshakes.get())

            // 指纹格式必须是 64 位小写十六进制：非法格式在凭据解析阶段就被拒绝。
            val invalid = runCatching {
                ModuleApiClient.parseCredentialText(
                    """{"listen":"127.0.0.1:$port","token":"${"a".repeat(64)}","certSha256":"xyz"}"""
                )
            }.exceptionOrNull()
            assertEquals("module_credentials_invalid", invalid?.message)
        } finally {
            serving.set(false)
            runCatching { serverSocket?.close() }
            dir.deleteRecursively()
        }
    }

    /** 轮询等待服务端线程记账：服务端在另一个线程里计数，客户端不能立即断言。 */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(what, condition())
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
