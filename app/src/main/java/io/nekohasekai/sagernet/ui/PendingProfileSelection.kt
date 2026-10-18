package io.nekohasekai.sagernet.ui

/** Identity, not the node ID, distinguishes repeated clicks on the same node. */
internal class PendingProfileSelection {
    class Request(val profileId: Long)

    private var pending: Request? = null

    fun begin(profileId: Long): Request {
        require(profileId > 0)
        return Request(profileId).also { pending = it }
    }

    fun complete(request: Request): Boolean {
        if (pending !== request) return false
        pending = null
        return true
    }

    fun selectedOr(confirmedId: Long): Long = pending?.profileId ?: confirmedId

    fun clear() {
        pending = null
    }
}
