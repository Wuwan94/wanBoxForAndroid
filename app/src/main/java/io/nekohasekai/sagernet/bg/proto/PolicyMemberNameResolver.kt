package io.nekohasekai.sagernet.bg.proto

/** Names decorate runtime tags; they never create or remove members. */
internal class PolicyMemberNameResolver(
    profileTags: Map<Long, String>,
    profileNames: Map<Long, String>,
    private val groupTags: Set<String>,
    private val directLabel: String,
    private val blockLabel: String,
) {
    private val names = profileTags.mapNotNull { (id, tag) ->
        profileNames[id]?.let { tag to it }
    }.toMap()

    fun name(tag: String): String = when {
        names.containsKey(tag) -> names.getValue(tag)
        tag in groupTags -> tag
        tag == "direct" -> directLabel
        tag == "block" -> blockLabel
        else -> tag
    }
}
