package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.widget.InsetsPadding
import io.nekohasekai.sagernet.widget.calculateSafeAreaPadding
import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeToEdgePaddingTest {

    @Test
    fun safeAreaInsetsAreAddedWithoutDiscardingOriginalSpacing() {
        val original = InsetsPadding(left = 12, top = 8, right = 16, bottom = 20)
        val safeInsets = InsetsPadding(left = 24, top = 28, right = 12, bottom = 34)

        assertEquals(
            InsetsPadding(left = 36, top = 36, right = 28, bottom = 54),
            calculateSafeAreaPadding(original, safeInsets),
        )
    }

    @Test
    fun applyingTheSameInsetsToTheOriginalPaddingDoesNotAccumulate() {
        val original = InsetsPadding(left = 4, top = 10, right = 6, bottom = 18)
        val landscapeAndNavigationInsets = InsetsPadding(left = 30, top = 0, right = 0, bottom = 24)
        val firstDispatch = calculateSafeAreaPadding(original, landscapeAndNavigationInsets)
        val repeatedDispatch = calculateSafeAreaPadding(original, landscapeAndNavigationInsets)

        assertEquals(InsetsPadding(left = 34, top = 10, right = 6, bottom = 42), firstDispatch)
        assertEquals(firstDispatch, repeatedDispatch)
    }
}
