package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.route.OutboundReferenceValidator

/** Sanitized diagnostic snapshot, never a config or proxy entity. */
data class OutboundReferenceWarning(
    val requestToken: String,
    val startIfStopped: Boolean,
    val issues: List<OutboundReferenceValidator.Issue>,
)
