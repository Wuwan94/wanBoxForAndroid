package io.nekohasekai.sagernet.group.editor

/** An editor session owns only memory. Cancel discards all form edits. */
class GroupEditSession(original: GroupDraft) {
    private val original = original.normalized()
    var draft = this.original
    fun cancel(): GroupDraft {
        draft = original
        return draft
    }
}
