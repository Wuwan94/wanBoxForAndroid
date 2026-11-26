package io.nekohasekai.sagernet.widget

/** Pixel padding on each edge, independent of Android View APIs. */
data class InsetsPadding(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

/**
 * Resolves safe-area padding from the view's original layout padding and the latest system insets.
 * Callers must always pass the original padding so repeated inset dispatch is idempotent.
 */
fun calculateSafeAreaPadding(
    originalPadding: InsetsPadding,
    safeInsets: InsetsPadding,
): InsetsPadding = InsetsPadding(
    left = originalPadding.left + safeInsets.left,
    top = originalPadding.top + safeInsets.top,
    right = originalPadding.right + safeInsets.right,
    bottom = originalPadding.bottom + safeInsets.bottom,
)
