package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UpdateTransportTest {
    @Test fun metadataAndAssetsUseSameExplicitProxyWithoutTargetDns() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val lines = executor.submit<List<String>> {
                (1..2).map {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader()
                        val first = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().write("HTTP/1.1 502 Fixture rejected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        first
                    }
                }
            }
            val temp = Files.createTempDirectory("update-proxy").toFile()
            try {
                UpdateTransport(server.localPort, null, null, Job()).use { client ->
                    for (asset in listOf(false, true)) {
                        try {
                            if (asset) client.download("https://asset.invalid/download.apk", File(temp, "apk"))
                            else client.text("https://metadata.invalid/releases")
                            fail("proxy error must not become success")
                        } catch (_: java.io.IOException) { }
                    }
                }
                assertEquals(listOf("CONNECT metadata.invalid:443 HTTP/1.1", "CONNECT asset.invalid:443 HTTP/1.1"), lines.get(3, TimeUnit.SECONDS))
                assertFalse(File(temp, "apk").exists())
            } finally { executor.shutdownNow(); temp.deleteRecursively() }
        }
    }
    @Test fun cancellationClosesStalledProxyBeforeReadTimeout() = runBlocking {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val accepted = CompletableDeferred<Unit>()
            val closed = executor.submit<Int> {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    accepted.complete(Unit)
                    socket.getInputStream().read()
                }
            }
            try {
                val job = launch(Dispatchers.IO) {
                    UpdateTransport(server.localPort, null, null, coroutineContext.job).use {
                        try { it.text("https://fixture.invalid/releases") }
                        catch (_: java.io.IOException) { ensureActive() }
                    }
                }
                withTimeout(3000) { accepted.await() }
                withTimeout(1000) { job.cancelAndJoin() }
                assertEquals(-1, closed.get(2, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun refusesCleartextAndInvalidPort() {
        try { UpdateTransport(0, null, null, Job()); fail() } catch (_: IllegalArgumentException) { }
        UpdateTransport(7890, null, null, Job()).use {
            try { it.text("http://fixture.invalid/releases"); fail() } catch (_: IllegalArgumentException) { }
        }
    }
}
