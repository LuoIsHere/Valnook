package dev.valnook.designsystem

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's modal bottom sheet. Its content runs down behind the navigation bar, so put [sheetBottomPadding] at the
 * end of what scrolls; the keyboard, which the page's own padding cannot reach in the sheet's window, lifts it.
 * A page's overlay spaces do not apply inside, and what is behind is blurred where the device allows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValnookBottomSheet(onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest, modifier, sheetState, containerColor = containerColor,
        contentWindowInsets = {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal).union(WindowInsets.ime)
        }) {
        PopupBlurEffect()
        CompositionLocalProvider(LocalPageBottomSpace provides 0.dp, LocalPageTopSpace provides 0.dp) { content() }
    }
}

/**
 * Bottom space for the scrolling content of a sheet that reaches the screen's bottom edge: [extra], plus the
 * navigation bar where the keyboard does not already cover it. Read at layout, so the keyboard's animation does not
 * recompose the sheet.
 */
@Composable
fun sheetBottomPadding(extra: Dp = 0.dp): PaddingValues =
    WindowInsets.navigationBars.exclude(WindowInsets.ime).only(WindowInsetsSides.Bottom)
        .add(WindowInsets(bottom = extra)).asPaddingValues()
