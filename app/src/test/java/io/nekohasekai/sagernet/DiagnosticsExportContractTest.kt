// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.DiagnosticsExport
import io.nekohasekai.sagernet.bg.RootSpeedTestResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

/** B5（N2）：脱敏诊断包导出契约；B5（N1）：Root 测试会话客户端契约。 */
class DiagnosticsExportContractTest {

    @Test
    fun fileNameUsesSafeRfc3339LocalTimestamp() {
        val timestamp = OffsetDateTime.of(2026, 11, 18, 12, 34, 56, 0, ZoneOffset.ofHours(8))
        assertEquals(
            "wanbox-diagnostics-2026-11-18T12-34-56+08-00.tar.gz",
            DiagnosticsExport.fileName(timestamp),
        )
        assertTrue(DiagnosticsExport.isDiagnosticsFileName(DiagnosticsExport.fileName(timestamp)))
        assertFalse(DiagnosticsExport.isDiagnosticsFileName("wanbox-diagnostics-2026-11-18.tar.gz.bak"))
        assertFalse(DiagnosticsExport.isDiagnosticsFileName("other-2026-11-18.tar.gz"))
        assertEquals(
            File(File("/storage/emulated/0/Download"), "wanbox-diagnostics-2026-11-18T12-34-56+08-00.tar.gz"),
            DiagnosticsExport.destinationFile(File("/storage/emulated/0/Download"), timestamp),
        )
    }

    @Test
    fun settingsEntryAndExactSection10Strings() {
        val settings = source("main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt")
        assertTrue(settings.contains("key = \"exportDiagnostics\""))
        assertTrue(settings.contains("R.string.export_diagnostics_confirm"))
        assertTrue(settings.contains("exportDiagnosticsPackage()"))
        assertTrue(settings.contains("DiagnosticsExport.destinationFile"))
        assertTrue(settings.contains("RootModuleClient.exportDiagnostics"))
        assertTrue(settings.contains("BuildConfig.VERSION_CODE_RAW"))
        assertTrue(settings.contains("BuildConfig.PRE_VERSION_NAME"))
        assertTrue(settings.contains("BuildConfig.PRE_VERSION_CODE_RAW"))
        assertTrue(settings.contains("BuildConfig.SINGBOX_VERSION"))

        val expected = mapOf(
            "export_diagnostics" to ("Export diagnostics package" to "导出诊断包"),
            "export_diagnostics_confirm" to (
                "The package contains desensitized runtime config, logs and status. " +
                    "Subscription URLs, node credentials, server addresses, SSIDs and MAC addresses are redacted. " +
                    "Routing domains are kept. Review before sharing." to
                    "诊断包包含脱敏后的运行时配置、日志与状态。订阅地址、节点凭据、服务器地址、Wi-Fi 名称与 MAC 地址已脱敏；路由域名会保留。请检查后再对外分享。"
                ),
            "export_diagnostics_success" to (
                "Diagnostics package exported: %1\$s (%2\$s)" to "诊断包已导出：%1\$s（%2\$s）"
                ),
            "export_diagnostics_failed" to (
                "Failed to export diagnostics package: %1\$s" to "导出诊断包失败：%1\$s"
                ),
            "stopped_nodes_hint" to (
                "Service stopped. Nodes, subscriptions and rules can still be browsed, edited and speed-tested." to
                    "服务已停止。节点、订阅与规则仍可浏览、编辑与测速。"
                ),
        )
        listOf("values", "values-zh-rCN").forEach { directory ->
            val strings = stringValues("main/res/$directory/strings.xml")
            expected.forEach { (key, translations) ->
                val want = if (directory == "values") translations.first else translations.second
                assertEquals("wrong $key in $directory", want, strings[key])
            }
        }
    }

    @Test
    fun commandAllowlistCoversNewResultCommands() {
        val client = source("main/java/io/nekohasekai/sagernet/bg/RootModuleClient.kt")
        assertTrue(client.contains("\"diagnostics export\""))
        assertTrue(client.contains("node (urltest|speedtest)"))
        assertTrue(client.contains("doCallRaw(\"diagnostics export\""))
        assertTrue(client.contains("doCallRaw(\"node urltest \$profileId\""))
        assertTrue(client.contains("doCallRaw(\"node speedtest \$profileId\""))
        assertTrue(client.contains("300_000L"))
        assertTrue(client.contains("fun exportDiagnostics("))
        assertTrue(client.contains("fun urlTest("))
        assertTrue(client.contains("fun speedTest("))
    }

    @Test
    fun rootSpeedTestResultParsesGoFieldNames() {
        // 与 libcore --speed-test 的 stdout 逐字段对照（Go 无 JSON tag，字段名原样输出）。
        val json = JSONObject(
            """{"ProfileKey":"7","Mode":"simple_download","Stage":"complete",""" +
                """"DownloadBitsPerSecond":123456,"UploadBitsPerSecond":0,"DownloadBytes":1048576,""" +
                """"UploadBytes":0,"LatencyMs":12,"ServerID":"","ServerName":"fixture",""" +
                """"ServerCountry":"CN","ServerSponsor":"","Error":"","Cancelled":false,"Done":true}""",
        )
        val result = RootSpeedTestResult.parse(json)
        assertEquals("7", result.profileKey)
        assertEquals("simple_download", result.mode)
        assertEquals("complete", result.stage)
        assertEquals(123456L, result.downloadBitsPerSecond)
        assertEquals(1048576L, result.downloadBytes)
        assertEquals(12L, result.latencyMs)
        assertEquals("fixture", result.serverName)
        assertEquals("CN", result.serverCountry)
        assertEquals("", result.error)
        assertFalse(result.cancelled)
        assertTrue(result.done)
    }

    @Test
    fun buildConfigFieldsExposeRawVersions() {
        val appGradle = File("../app/build.gradle.kts").readText()
        assertTrue(appGradle.contains("VERSION_CODE_RAW"))
        assertTrue(appGradle.contains("SINGBOX_VERSION"))
        val helpers = File("../buildSrc/src/main/kotlin/Helpers.kt").readText()
        assertTrue(helpers.contains("PRE_VERSION_CODE_RAW"))
    }

    private fun stringValues(relativePath: String): Map<String, String> {
        val elements = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/$relativePath"))
            .getElementsByTagName("string")
        return (0 until elements.length).associate { index ->
            val attributes = elements.item(index).attributes
            attributes.getNamedItem("name").nodeValue to elements.item(index).textContent
        }
    }

    private fun source(relativePath: String): String = File("src/$relativePath").readText()
}
