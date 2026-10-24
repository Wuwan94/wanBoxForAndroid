// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonObject

/**
 * Domain-regex rejection remains in the App-generated legacy configuration.
 * Preserving its JSON is not evidence that ConfigBuilder and the pinned sing-box
 * core have equivalent matcher/action semantics, so Root projection stays disabled.
 */
internal fun projectRootRouteDomainRegexRejectRules(config: JsonObject): JsonObject? = null
