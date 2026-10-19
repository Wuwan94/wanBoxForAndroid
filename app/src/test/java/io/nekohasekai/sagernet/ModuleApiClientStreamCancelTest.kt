// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.ModuleApiClient
import io.nekohasekai.sagernet.bg.RootModuleClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * SSE 事件流必须可取消：STREAM 档读超时为 0，阻塞读只有关闭 socket 才能解除。
 * 页面退出后若不关 socket，协程会永久挂在读上，模块侧连接也一直不释放。
 *
 * 服务端用 chunked 编码（与模块 Go 侧 net/http 的行为一致）持续保持连接，
 * 因此「客户端断开」只能来自 App 侧取消，断言才有意义。
 * @author 雾晚
 */
class ModuleApiClientStreamCancelTest {

    @Test fun cancellingCollectorClosesEventStreamSocket() = runBlocking {
        val toolName = if (System.getProperty("os.name").lowercase().contains("win")) "keytool.exe" else "keytool"
        val keytool = File(File(System.getProperty("java.home"), "bin"), toolName)
        assumeTrue("keytool 不可用，跳过真实 TLS 测试", keytool.canExecute())

        val dir = createTempDir(prefix = "wanbox-sse-")
        var serverSocket: SSLServerSocket? = null
        val closed = CountDownLatch(1)
        // 连接在取消之前一直保持打开的次数（每次读超时即一轮），用来证明不是服务端自己断的。
        val openCycles = AtomicInteger(0)
        val closeReason = AtomicReference("none")
        val originalBridge = ModuleApiClient.bridge
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
            val serverContext = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            serverSocket = serverContext.serverSocketFactory.createServerSocket(
                0, 4, InetAddress.getByName("127.0.0.1")
            ) as SSLServerSocket
            val port = serverSocket.localPort
            val socket = serverSocket
            thread(isDaemon = true, name = "wanbox-sse") {
                runCatching {
                    socket.accept().use { raw ->
                        val client = raw as SSLSocket
                        client.startHandshake()
                        client.soTimeout = 200
                        client.outputStream.write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Cache-Control: no-cache\r\n" +
                                "Transfer-Encoding: chunked\r\n\r\n").toByteArray()
                        )
                        // 一个合法事件帧（chunked），之后不再写，连接保持 opening。
                        val frame = "data: {\"schema\":1,\"ok\":true,\"state\":{\"phase\":\"connected\"}}\n\n"
                            .toByteArray()
                        client.outputStream.write("${Integer.toHexString(frame.size)}\r\n".toByteArray())
                        client.outputStream.write(frame)
                        client.outputStream.write("\r\n".toByteArray())
                        client.outputStream.flush()
                        // 只等客户端断开：读超时说明连接还开着（这也说明服务端没主动关）。
                        val buffer = ByteArray(1)
                        while (true) {
                            val result = runCatching { client.inputStream.read(buffer) }
                            if (result.isSuccess) {
                                if (result.getOrNull() == -1) {
                                    closeReason.set("eof")
                                    break
                                }
                                continue
                            }
                            val error = result.exceptionOrNull()
                            if (error is SocketTimeoutException) {
                                openCycles.incrementAndGet()
                                continue
                            }
                            closeReason.set(error?.javaClass?.simpleName ?: "unknown")
                            break
                        }
                        closed.countDown()
                    }
                }
            }

            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(keyStore.getCertificate(ALIAS).encoded)
                .joinToString("") { "%02x".format(it) }
            ModuleApiClient.invalidateCredentials()
            ModuleApiClient.bridge = object : ModuleApiClient.CredentialBridge {
                override suspend fun load() = ModuleApiClient.Credentials(
                    baseUrl = "https://127.0.0.1:$port",
                    token = "a".repeat(64),
                    certSha256 = fingerprint,
                )
            }

            val first = CompletableDeferred<RootModuleClient.Status>()
            // 收集方吞掉流自身结束时的异常：本用例只关心「取消是否关掉 socket」。
            val job = launch(Dispatchers.IO) {
                runCatching { ModuleApiClient.events().collect { status -> first.complete(status) } }
            }
            val status = withTimeout(15_000) { first.await() }
            assertEquals("connected", status.phase)
            // 等到至少两轮读超时：证明取消前连接确实还是打开的。
            val alive = System.currentTimeMillis() + 5_000
            while (openCycles.get() < 2 && System.currentTimeMillis() < alive) {
                Thread.sleep(50)
            }
            assertTrue("取消前连接必须保持打开", openCycles.get() >= 2)

            // 模拟页面退出：取消收集方必须让 socket 立即关闭。
            job.cancel()
            // 加超时：取消后若 awaitClose 没生效、协程永久挂在阻塞读上，
            // 测试必须失败而不是整个套件挂死。
            withTimeout(10_000) { job.join() }
            assertTrue(
                "取消后必须关闭事件流 socket，服务端结束原因: ${closeReason.get()}",
                closed.await(5, TimeUnit.SECONDS),
            )
        } finally {
            ModuleApiClient.bridge = originalBridge
            ModuleApiClient.invalidateCredentials()
            runCatching { serverSocket?.close() }
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val ALIAS = "wanbox"
        const val PASSWORD = "changeit"
    }
}
