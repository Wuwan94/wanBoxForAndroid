// @author 雾晚
package io.nekohasekai.sagernet.bg.proto

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ThinControllerDelayClientTest {
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var baseUrl: String
    private val requestCount = AtomicInteger()
    private val authorization = AtomicReference<String>()
    private val requestPath = AtomicReference<String>()
    private val requestQuery = AtomicReference<String>()
    private val requestRemote = AtomicReference<String>()
    private val responseStatus = AtomicInteger(200)
    private val responseBody = AtomicReference("{\"delay\":37}")

    @Before
    fun startServer() {
        server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        baseUrl = "http://127.0.0.1:${server.localPort}/"
        serverThread = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                    val requestLine = reader.readLine().orEmpty()
                    val pieces = requestLine.split(' ', limit = 3)
                    requestPath.set(pieces.getOrNull(1).orEmpty())
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Authorization:", ignoreCase = true)) {
                            authorization.set(line.substringAfter(':').trim())
                        }
                    }
                    requestCount.incrementAndGet()
                    requestQuery.set(requestPath.get().substringAfter('?', ""))
                    requestRemote.set(socket.inetAddress.hostAddress)
                    val bytes = responseBody.get().toByteArray(StandardCharsets.UTF_8)
                    val status = responseStatus.get()
                    val reason = if (status == 200) "OK" else "Found"
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write("Content-Type: application/json\r\n".toByteArray(StandardCharsets.US_ASCII))
                    if (status == 302) output.write("Location: http://example.org/redirected\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write(bytes)
                    output.flush()
                }
            } catch (_: IOException) {
                // Closing the server socket after a no-request test unblocks accept().
            }
        }.apply { name = "controller-delay-test-server"; isDaemon = true; start() }
    }

    @After
    fun stopServer() {
        server.close()
        serverThread.join(1_000)
    }

    @Test
    fun authenticatedRequestIsBoundToLoopbackAndEncodesTagAndTarget() = runTest {
        val client = ThinControllerDelayClient(baseUrl) { SECRET }
        val result = client.measure("node/a", "https://example.org/check?q=one%20two", 5_000)

        assertEquals(37, result)
        assertEquals(1, requestCount.get())
        assertEquals("Bearer $SECRET", authorization.get())
        assertEquals("/proxies/node%2Fa/delay", requestPath.get().substringBefore('?'))
        assertEquals("127.0.0.1", requestRemote.get())
        val query = requestQuery.get().split('&').associate {
            val pieces = it.split('=', limit = 2)
            URLDecoder.decode(pieces[0], "UTF-8") to URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
        }
        assertEquals("https://example.org/check?q=one%20two", query["url"])
        assertEquals("5000", query["timeout"])
    }

    @Test
    fun rejectsNonHttpTargetBeforeSendingRequest() = runTest {
        val client = ThinControllerDelayClient(baseUrl) { SECRET }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { client.measure("node", "file:///etc/passwd", 3_000) }
        }
        assertEquals(0, requestCount.get())
    }

    @Test
    fun rejectsControllerErrorsWithoutFollowingRedirects() = runTest {
        responseStatus.set(302)
        val client = ThinControllerDelayClient(baseUrl) { SECRET }
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.measure("node", "https://example.org/check", 3_000) }
        }
        assertEquals(1, requestCount.get())
        assertEquals("127.0.0.1", requestRemote.get())
    }

    @Test
    fun rejectsInvalidLatencyPayload() = runTest {
        responseBody.set("{\"delay\":0}")
        val client = ThinControllerDelayClient(baseUrl) { SECRET }
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.measure("node", "https://example.org/check", 3_000) }
        }
    }

    companion object {
        private const val SECRET = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
