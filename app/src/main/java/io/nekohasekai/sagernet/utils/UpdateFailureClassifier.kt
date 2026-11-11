// @author 雾晚
package io.nekohasekai.sagernet.utils

import java.io.IOException

/** Marker for a locally disabled/unavailable update proxy; never contains credentials. */
internal class UpdateProxyUnavailable : IOException()

internal object UpdateFailureClassifier {
    /**
     * Only the explicit marker gets the proxy message. [UpdateTransport] tags a
     * refused connection to the local proxy with [UpdateProxyUnavailable] at the
     * source; a bare [java.net.ConnectException] bubbling up from anywhere else
     * is not evidence about the proxy and must not be relabelled as one.
     */
    fun isProxyUnavailable(error: Throwable): Boolean {
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is UpdateProxyUnavailable) return true
            current = current.cause
        }
        return false
    }
}
