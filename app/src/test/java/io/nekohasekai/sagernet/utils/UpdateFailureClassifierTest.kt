// @author 雾晚
package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class UpdateFailureClassifierTest {
    @Test fun explicitProxyMarkerIsRecognisedAtAnyDepth() {
        assertTrue(UpdateFailureClassifier.isProxyUnavailable(UpdateProxyUnavailable()))
        assertTrue(UpdateFailureClassifier.isProxyUnavailable(IOException("wrapped", UpdateProxyUnavailable())))
        assertTrue(UpdateFailureClassifier.isProxyUnavailable(UpdateDownload.Failure(
            UpdateDownload.Reason.TRANSFER,
            UpdateProxyUnavailable().apply { initCause(ConnectException("refused")) }
        )))
    }

    @Test fun bareConnectExceptionIsNotProxyUnavailable() {
        // 只有 UpdateTransport 在源头打过标记的连接拒绝才算代理不可用；
        // 其他地方冒出的 ConnectException 不能被误报成代理问题。
        assertFalse(UpdateFailureClassifier.isProxyUnavailable(ConnectException("refused")))
        assertFalse(UpdateFailureClassifier.isProxyUnavailable(IOException("wrapped", ConnectException())))
        assertFalse(UpdateFailureClassifier.isProxyUnavailable(UpdateDownload.Failure(
            UpdateDownload.Reason.TRANSFER,
            IOException("proxy", ConnectException())
        )))
    }

    @Test fun remoteTimeoutAndOtherErrorsAreNotMisreportedAsProxyStartupFailure() {
        assertFalse(UpdateFailureClassifier.isProxyUnavailable(SocketTimeoutException()))
        assertFalse(UpdateFailureClassifier.isProxyUnavailable(IOException("HTTP 503")))
    }
}
