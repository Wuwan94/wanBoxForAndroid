// @author 雾晚
package io.nekohasekai.sagernet.utils

import java.io.IOException
import java.net.ConnectException

/** Marker for a locally disabled/unavailable update proxy; never contains credentials. */
internal class UpdateProxyUnavailable : IOException()

internal object UpdateFailureClassifier {
    /** Only a refused local connection (or our explicit disabled-proxy marker) gets this message. */
    fun isProxyUnavailable(error: Throwable): Boolean {
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is UpdateProxyUnavailable || current is ConnectException) return true
            current = current.cause
        }
        return false
    }
}
