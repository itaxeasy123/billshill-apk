package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The system-bar inset a full-screen [androidx.compose.ui.window.Dialog] has to apply itself.
 *
 * Compose's `WindowInsets.safeDrawing` reads as zero inside a Dialog here, so
 * `safeDrawingPadding()` is a no-op and a bottom action row gets laid out under the gesture
 * bar — measured at y=1567..1600 on a 1600px-tall screen, i.e. sliced in half.
 * `DialogProperties.decorFitsSystemWindows` changes nothing; both settings produced the
 * identical out-of-bounds placement.
 *
 * Read from the **Activity's** decor view rather than the dialog's own view, and without
 * caching. A dialog's view is not attached to a window on first composition, so it reports
 * no insets, and a value remembered at that moment stays zero for the life of the dialog —
 * which is exactly how the invoice editor's action row stayed under the gesture bar while
 * the trial-balance modal, composing a frame later, came out correct. The Activity's decor
 * view is always attached, and the system bars are a property of the display rather than of
 * either window.
 *
 * Returns 0.dp where there is genuinely no bar, so nothing is padded for a bar that is not
 * there.
 */
@Composable
fun dialogSystemBarInsets(): DialogInsets {
    val density = LocalDensity.current
    val fallbackView = LocalView.current
    val context = LocalContext.current

    val decorView = context.findActivity()?.window?.decorView ?: fallbackView
    val insets = ViewCompat.getRootWindowInsets(decorView)?.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    )

    return with(density) {
        DialogInsets(
            top = (insets?.top ?: 0).toDp(),
            bottom = (insets?.bottom ?: 0).toDp()
        )
    }
}

data class DialogInsets(val top: Dp = 0.dp, val bottom: Dp = 0.dp) {
    /**
     * What a full-screen Dialog must pad its bottom by, which is NOT just [bottom].
     *
     * Such a dialog's window is positioned below the status bar but sized to the whole
     * display, so it hangs off the bottom of the screen by exactly the status-bar height —
     * and the gesture bar then covers [bottom] more. Measured on a 1600px screen with a
     * 91px status bar and a 48px gesture bar: an action row padded by [bottom] alone still
     * sat 39px inside the gesture area, and padding by this sum is what clears it.
     */
    val fullScreenDialogBottom: Dp get() = top + bottom
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
