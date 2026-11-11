// @author 雾晚
package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class UpdateFailureClassifierTest {
    @Test fun disabledOrRefusedLocalProxyIsExplicit() {
        assertTrue(UpdateFailureClassifier.isProxyUnavailable(UpdateProxyUnavailable()))
        assertTrue(UpdateFailureClassifier.isProxyUnavailable(IOException("wrapped", ConnectException())))
        assertTrue(UpdateFailureClassifier.isProxyUnavailable(UpdateDownload.Failure(
            UpdateDownload.Reason.TRANSFER,
            IOException("proxy", ConnectException())
        )))
    }

    @Test fun remoteTimeoutAndOtherErrorsAreNotMisreportedAsProxyStartupFailure() {
        assertFalse(UpdateFailureClassifier.isProxyUnavailable(SocketTimeoutException()))
        assertFalse(UpdateFailureClassifier.isProxyUnavailable(IOException("HTTP 503")))
    }
}
