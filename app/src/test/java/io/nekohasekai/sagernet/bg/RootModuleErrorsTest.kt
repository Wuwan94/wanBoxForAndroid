package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.R
import org.junit.Assert.assertEquals
import org.junit.Test

class RootModuleErrorsTest {
    /** Fail-closed fallback: a duplicate runtime tag explains which entries to rename. */
    @Test fun duplicateTagIsNotReportedAsGenericFailure() {
        assertEquals(R.string.root_module_duplicate_tag, RootModuleErrors.resource("duplicate_tag"))
    }
}
