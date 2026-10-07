package dev.valnook.designsystem

import android.view.ViewParent
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import java.util.function.Consumer

/** Window-level blur exists only while the popup is present; its foreground stays sharp. */
@Composable
fun PopupBlurEffect(): Boolean {
    val view = LocalView.current
    val radius = with(LocalDensity.current) { GlassMaterial.blurRadius.roundToPx() }
    var blurEnabled by remember(view) { mutableStateOf(false) }
    DisposableEffect(view, radius) {
        var parent: ViewParent? = view.parent
        var provider: DialogWindowProvider? = view as? DialogWindowProvider
        while (provider == null && parent != null) {
            provider = parent as? DialogWindowProvider
            parent = parent.parent
        }
        val window = provider?.window
        val manager = view.context.getSystemService(WindowManager::class.java)
        if (window == null || manager == null) onDispose { } else {
            val oldRadius = window.attributes.blurBehindRadius
            val oldDim = window.attributes.dimAmount
            val hadFlag = window.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0
            val listener = Consumer<Boolean> { enabled ->
                blurEnabled = enabled
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    setBlurBehindRadius(if (enabled) radius else 0)
                    dimAmount = if (enabled) 0.18f else 0.32f
                }
            }
            manager.addCrossWindowBlurEnabledListener(listener)
            listener.accept(manager.isCrossWindowBlurEnabled)
            onDispose {
                manager.removeCrossWindowBlurEnabledListener(listener)
                window.attributes = window.attributes.apply {
                    setBlurBehindRadius(oldRadius)
                    dimAmount = oldDim
                }
                if (!hadFlag) window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
        }
    }
    return blurEnabled
}
