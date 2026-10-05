package dev.valnook.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Decorative: the containing button supplies the expanded/collapsed semantics. */
@Composable
fun ExpansionChevron(expanded: Boolean, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(160), label = "expansion-arrow")
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(16.dp).graphicsLayer { rotationZ = rotation }) {
        drawLine(color, Offset(size.width * .25f, size.height * .4f),
            Offset(size.width * .5f, size.height * .65f), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(size.width * .5f, size.height * .65f),
            Offset(size.width * .75f, size.height * .4f), 1.5.dp.toPx(), StrokeCap.Round)
    }
}

/** Retains the window and its blur through exit; closing content cannot be activated again. */
@Composable
fun AnimatedGlassDialog(visible: Boolean, onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible
    if (visible || visibility.currentState || !visibility.isIdle) {
        Dialog(onDismissRequest = { if (visible) onDismissRequest() }, properties = properties) {
            PopupBlurEffect()
            val view = LocalView.current
            DisposableEffect(view) {
                var parent: android.view.ViewParent? = view.parent
                var provider = view as? DialogWindowProvider
                while (provider == null && parent != null) {
                    provider = parent as? DialogWindowProvider
                    parent = parent.parent
                }
                val window = provider?.window
                val oldAnimations = window?.attributes?.windowAnimations
                window?.setWindowAnimations(0)
                onDispose { if (oldAnimations != null) window?.setWindowAnimations(oldAnimations) }
            }
            AnimatedVisibility(visibleState = visibility,
                enter = fadeIn(tween(180)) + scaleIn(tween(200), initialScale = .96f),
                exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = .98f)) {
                Box(if (visible) Modifier else Modifier.clearAndSetSemantics { }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }) { content() }
            }
        }
    }
}
