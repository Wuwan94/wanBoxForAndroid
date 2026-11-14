package io.nekohasekai.sagernet.bg

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SmartAutoStopApplyErrorsTest {
    @Test fun codesAndSecretLikeOutputUseLocalizedFallback() {
        for (unsafe in listOf("root_required", "module_operation_failed", "Unable to apply.\nAuthorization: Bearer abc",
            "password=abc", "passwd=abc", "token=abc", "secret=abc", "api_key=abc", "API key: abc")) {
            assertEquals("Unsafe output: $unsafe", "localized safe error",
                SmartAutoStopApplyErrors.message(IOException(unsafe)) { "localized safe error" })
        }
    }

    @Test fun readableIOExceptionIsPreservedInFull() {
        val readable = "Unable to save rules.\nThe configuration directory is read-only."
        assertEquals(readable, SmartAutoStopApplyErrors.message(IOException(readable)) {
            fail("Readable Apply failure must not be replaced by generic module error")
            "unreachable"
        })
    }
}
