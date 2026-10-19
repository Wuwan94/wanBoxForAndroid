// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.ModuleApiClient
import io.nekohasekai.sagernet.bg.RootModuleClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 管理面协议兼容：模块响应写 schema，历史快照写 schemaVersion；
 * 事件帧只有 state 没有 ok。两者都必须按真实字段解析，不能补成功或忽略版本。
 * @author 雾晚
 */
class ModuleProtocolCompatTest {

    @Test fun currentSchemaFieldIsAccepted() {
        val status = RootModuleClient.parseResponse(
            """{"schema":1,"ok":true,"state":{"phase":"connected","profileId":7,"profileName":"n"}}"""
        )
        assertTrue(status.connected)
        assertEquals(7L, status.profileId)
    }

    @Test fun legacySchemaVersionIsStillAccepted() {
        val status = RootModuleClient.parseResponse(
            """{"schemaVersion":1,"ok":true,"state":{"phase":"stopped"}}"""
        )
        assertTrue(!status.connected)
    }

    @Test fun conflictingSchemaFieldsAreRejected() {
        val error = assertThrows(IOException::class.java) {
            RootModuleClient.parseResponse(
                """{"schema":1,"schemaVersion":2,"ok":true,"state":{"phase":"stopped"}}"""
            )
        }
        assertEquals("module_protocol_mismatch", error.message)
    }

    @Test fun unknownSchemaIsRejected() {
        assertEquals("module_protocol_mismatch", failure("""{"schema":2,"ok":true,"state":{"phase":"stopped"}}"""))
        assertEquals("module_protocol_mismatch", failure("""{"schema":"1","ok":true,"state":{"phase":"stopped"}}"""))
        assertEquals("module_protocol_mismatch", failure("""{"ok":true,"state":{"phase":"stopped"}}"""))
    }

    @Test fun eventFrameWithoutOkIsAccepted() {
        val status = RootModuleClient.parseResponse(
            """{"schema":1,"state":{"phase":"connected","profileId":9}}"""
        )
        assertTrue(status.connected)
        assertEquals(9L, status.profileId)
    }

    @Test fun failureResponseStillThrowsItsCode() {
        assertEquals(
            "revision_conflict",
            failure("""{"schema":1,"ok":false,"error":"revision_conflict","state":{"phase":"stopped"}}""")
        )
    }

    @Test fun credentialRequiresNumericLoopbackAndHexFingerprint() {
        val ok = ModuleApiClient.parseCredentialText(
            """{"listen":"127.0.0.1:34567","token":"${"a".repeat(64)}","certSha256":"${"b".repeat(64)}"}"""
        )
        assertEquals("https://127.0.0.1:34567", ok.baseUrl)
        assertTrue(!ok.toString().contains("a".repeat(64)))
        assertTrue(ok.toString().contains("<redacted>"))
    }

    @Test fun credentialRejectsHostnameZeroPortAndShortSecrets() {
        for (bad in listOf(
            """{"listen":"localhost:34567","token":"${"a".repeat(64)}","certSha256":"${"b".repeat(64)}"}""",
            """{"listen":"127.0.0.1:0","token":"${"a".repeat(64)}","certSha256":"${"b".repeat(64)}"}""",
            """{"listen":"127.0.0.1:34567","token":"short","certSha256":"${"b".repeat(64)}"}""",
            """{"listen":"127.0.0.1:34567","token":"${"a".repeat(64)}","certSha256":"${"B".repeat(64)}"}""",
        )) {
            assertEquals("module_credentials_invalid", failureCredential(bad))
        }
    }

    private fun failure(json: String): String =
        assertThrows(IOException::class.java) { RootModuleClient.parseResponse(json) }.message.orEmpty()

    private fun failureCredential(json: String): String =
        assertThrows(IOException::class.java) { ModuleApiClient.parseCredentialText(json) }.message.orEmpty()
}
