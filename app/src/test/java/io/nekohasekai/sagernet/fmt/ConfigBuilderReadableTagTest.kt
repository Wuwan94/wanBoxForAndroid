package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.group.editor.GroupDraft
import io.nekohasekai.sagernet.group.editor.GroupEditor
import io.nekohasekai.sagernet.group.editor.GroupMode
import io.nekohasekai.sagernet.group.editor.UrlTestParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** N2: a subscription node named like an existing group must not break the ordinary build. */
class ConfigBuilderReadableTagTest {
    @Test fun subscriptionNodeNamedLikeNonMainGroupGetsSuffixedAndGroupKeepsItsTag() {
        // Main group "订阅A" is the active group; non-main group "香港" has a saved draft.
        val tags = ReadableTags(ReadableTags.reservedFor(listOf("订阅A", "香港", null, " "), listOf("香港")))
        val nodeTag = tags.allocate("香港")
        assertEquals("香港-1", nodeTag)
        assertEquals("香港-2", tags.allocate("香港"))
        assertEquals("proxy-1", tags.allocate("proxy"))
        assertEquals("东京", tags.allocate("东京"))

        // The draft patch for group "香港" now succeeds instead of throwing duplicate_tag.
        val config = com.google.gson.JsonParser.parseString(
            """{"outbounds":[{"tag":"proxy","type":"selector","outbounds":["东京"]},{"tag":"东京","type":"socks"},{"tag":"$nodeTag","type":"socks"}]}""").asJsonObject
        val draft = GroupDraft(2, "香港", GroupMode.SELECTOR, listOf(5, 6), UrlTestParameters("", 600, 100, "30m", false))
        val patched = GroupEditor.patchOutbounds(config, draft, "香港", mapOf(5L to nodeTag, 6L to "东京"), "")
        val outbounds = patched["outbounds"].asJsonArray.map { it.asJsonObject }
        val group = outbounds.single { it["tag"].asString == "香港" }
        assertEquals("selector", group["type"].asString)
        assertEquals(listOf(nodeTag, "东京"), group["outbounds"].asJsonArray.map { it.asString })
        assertNotEquals(group["tag"].asString, nodeTag)
        assertEquals(outbounds.size, outbounds.map { it["tag"].asString }.toSet().size)
    }
}
