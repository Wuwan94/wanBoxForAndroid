package io.nekohasekai.sagernet.widget

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.WeakHashMap

/** Shared edge-to-edge window setup and safe-area padding for app-owned content roots. */
object WindowInsetsPolicy {
    private val originalPaddingByView = WeakHashMap<View, InsetsPadding>()

    fun configureWindow(window: Window, lightSystemBars: Boolean? = null) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        lightSystemBars?.let { light ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    /**
     * Applies system-bar and display-cutout insets to a view's padding while preserving its XML or
     * programmatic baseline. Insets are not consumed, so deliberate child consumers (such as the
     * config editor's IME container) can still combine keyboard and navigation-bar geometry.
     */
    fun applySafeAreaPadding(
        view: View,
        includeTop: Boolean = true,
        includeHorizontal: Boolean = true,
        includeBottom: Boolean = true,
    ) {
        val originalPadding = synchronized(originalPaddingByView) {
            originalPaddingByView.getOrPut(view) {
                InsetsPadding(
                    left = view.paddingLeft,
                    top = view.paddingTop,
                    right = view.paddingRight,
                    bottom = view.paddingBottom,
                )
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val safeInsets = InsetsPadding(
                left = if (includeHorizontal) bars.left else 0,
                top = if (includeTop) bars.top else 0,
                right = if (includeHorizontal) bars.right else 0,
                bottom = if (includeBottom) bars.bottom else 0,
            )
            val padding = calculateSafeAreaPadding(originalPadding, safeInsets)
            target.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
