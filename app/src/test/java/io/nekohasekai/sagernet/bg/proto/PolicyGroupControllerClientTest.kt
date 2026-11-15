package io.nekohasekai.sagernet.bg.proto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class PolicyGroupControllerClientTest {
    private val secret = "a".repeat(64)

    private fun serve(vararg responses: Pair<Int, String>, block: (String, MutableList<String>) -> Unit) {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            val requests = CopyOnWriteArrayList<String>()
            val worker = Thread {
                try {
                    responses.forEach { (status, body) ->
                        server.accept().use { socket ->
                            socket.soTimeout = 3000
                            val input = socket.getInputStream().buffered()
                            val headers = mutableListOf<String>()
                            var length = 0
                            while (true) {
                                val line = buildString {
                                    while (true) {
                                        val byte = input.read()
                                        if (byte < 0) throw IOException("Unexpected end of request headers")
                                        if (byte == '\n'.code) break
                                        if (byte != '\r'.code) append(byte.toChar())
                                    }
                                }
                                if (line.isEmpty()) break
                                headers.add(line)
                                if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                            }
                            val payload = ByteArray(length)
                            var offset = 0
                            while (offset < length) {
                                val count = input.read(payload, offset, length - offset)
                                if (count < 0) throw IOException("Unexpected end of request body")
                                offset += count
                            }
                            requests.add(headers.joinToString("\n") + "\n\n" + String(payload, Charsets.UTF_8))
                            val bytes = body.toByteArray()
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 $status Test\r\nLocation: http://example.org/\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes)
                                flush()
                            }
                        }
                    }
                } catch (_: IOException) { }
            }.apply { isDaemon = true; start() }
            try { block("http://127.0.0.1:${server.localPort}/", requests) }
            finally { server.close(); worker.join(1000) }
        }
    }

    @Test fun snapshotAuthenticatesAndPreservesMembersAndCurrentSelection() {
        serve(200 to """{"proxies":{"node":{"type":"Shadowsocks"},"proxy":{"type":"sElEcToR","now":"b","all":["b","a"]},"auto":{"type":"URLTest","now":"a","all":[]}}}""") { url, requests ->
            val snapshot = runBlocking { PolicyGroupControllerClient(url) { secret }.snapshot() }
            assertEquals(setOf("proxy", "auto"), snapshot.groups.map { it.tag }.toSet())
            assertEquals(listOf("b", "a"), snapshot.groups.single { it.tag == "proxy" }.members)
            assertEquals("b", snapshot.groups.single { it.tag == "proxy" }.now)
            assertEquals(PolicyGroupType.SELECTOR, snapshot.groups.single { it.tag == "proxy" }.type)
            assertTrue(requests.single().startsWith("GET /proxies HTTP/1.1"))
            assertTrue(requests.single().contains("Authorization: Bearer $secret"))
        }
    }

    @Test fun malformedSnapshotFails() {
        for (body in listOf("not json", "{}", "{\"proxies\":[]}")) {
            serve(200 to body) { url, _ ->
                assertThrows(IOException::class.java) { runBlocking { PolicyGroupControllerClient(url) { secret }.snapshot() } }
            }
        }
    }
    @Test fun switchingEncodesTagAndJsonAndRequiresReadback() {
        serve(204 to "", 200 to """{"type":"Selector","now":"node\"b","all":["node\"b"]}""") { url, requests ->
            val group = PolicyGroup("group/a", PolicyGroupType.SELECTOR, "a", listOf("node\"b"))
            val selected = runBlocking { PolicyGroupControllerClient(url) { secret }.select(group, "node\"b") }
            assertEquals("node\"b", selected.now)
            assertTrue(requests[0].startsWith("PUT /proxies/group%2Fa HTTP/1.1"))
            assertEquals("node\"b", org.json.JSONObject(requests[0].substringAfter("\n\n")).getString("name"))
            assertTrue(requests[1].startsWith("GET /proxies/group%2Fa HTTP/1.1"))
            assertTrue(requests.all { it.contains("Authorization: Bearer $secret") })
        }
    }

    @Test fun switchingPreservesChineseMemberNameInRequestBody() {
        serve(204 to "", 200 to """{"type":"Selector","now":"香港节点","all":["香港节点"]}""") { url, requests ->
            val group = PolicyGroup("proxy", PolicyGroupType.SELECTOR, "a", listOf("香港节点"))
            val selected = runBlocking { PolicyGroupControllerClient(url) { secret }.select(group, "香港节点") }
            assertEquals("香港节点", selected.now)
            assertTrue(requests[0].startsWith("PUT /proxies/proxy HTTP/1.1"))
            assertEquals("香港节点", org.json.JSONObject(requests[0].substringAfter("\n\n")).getString("name"))
            assertTrue(requests[1].startsWith("GET /proxies/proxy HTTP/1.1"))
        }
    }

    @Test fun mismatchingReadbackIsFailure() {
        serve(204 to "", 200 to """{"type":"Selector","now":"a","all":["a","b"]}""") { url, _ ->
            assertThrows(IOException::class.java) { runBlocking {
                PolicyGroupControllerClient(url) { secret }.select(PolicyGroup("proxy", PolicyGroupType.SELECTOR, "a", listOf("a", "b")), "b")
            } }
        }
    }

    @Test fun automaticGroupsRejectSelectionWithoutRequest() {
        serve { url, requests ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                PolicyGroupControllerClient(url) { secret }.select(PolicyGroup("auto", PolicyGroupType.URLTEST, "a", listOf("a")), "a")
            } }
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun redirectsErrorsAndOversizedPayloadsFail() {
        for ((status, body) in listOf(302 to "{}", 500 to "{}", 200 to " ".repeat(65537))) {
            serve(status to body) { url, requests ->
                assertThrows(IOException::class.java) { runBlocking { PolicyGroupControllerClient(url) { secret }.snapshot() } }
                assertEquals(1, requests.size)
            }
        }
    }

    @Test fun malformedEntriesAreCountedAndLoadBalanceIsRecognized() {
        serve(200 to """{"proxies":{"bad":{"type":"Selector","all":[1]},"missing":{"type":"Selector"},"balance":{"type":"loadbalance","all":[]}}}""") { url, _ ->
            val snapshot = runBlocking { PolicyGroupControllerClient(url) { secret }.snapshot() }
            assertEquals(2, snapshot.skippedEntries)
            assertEquals(PolicyGroupType.LOADBALANCE, snapshot.groups.single().type)
            assertNull(snapshot.groups.single().now)
        }
    }

    @Test fun rejectsMissingCredentialsAndNonLoopbackControllerBeforeSending() {
        serve { url, requests ->
            assertThrows(IOException::class.java) { runBlocking { PolicyGroupControllerClient(url) { "" }.snapshot() } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                PolicyGroupControllerClient("http://example.org:9090/") { secret }.snapshot()
            } }
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun putHttpFailureDoesNotReportSuccess() {
        serve(403 to "{}") { url, requests ->
            assertThrows(IOException::class.java) { runBlocking {
                PolicyGroupControllerClient(url) { secret }.select(PolicyGroup("proxy", PolicyGroupType.SELECTOR, "a", listOf("b")), "b")
            } }
            assertEquals(1, requests.size)
        }
    }

}
