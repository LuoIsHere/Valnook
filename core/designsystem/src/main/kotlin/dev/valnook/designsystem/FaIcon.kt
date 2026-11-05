package dev.valnook.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R

/** Interface icons: Font Awesome Free, tinted by Compose. Account icons are in [accountSymbolResource]. */
object FaIcons {
    val check = R.drawable.fa_check
    val chevronDown = R.drawable.fa_chevron_down
    val chevronLeft = R.drawable.fa_chevron_left
    val chevronRight = R.drawable.fa_chevron_right
    val cloud = R.drawable.fa_cloud
    val copy = R.drawable.fa_copy
    val download = R.drawable.fa_download
    val eye = R.drawable.fa_eye
    val eyeSlash = R.drawable.fa_eye_slash
    val gripLines = R.drawable.fa_grip_lines
    val palette = R.drawable.fa_palette
    val pen = R.drawable.fa_pen
    val plusMinus = R.drawable.fa_plus_minus
    val rotate = R.drawable.fa_rotate
    val trash = R.drawable.fa_trash_can
    val xmark = R.drawable.fa_xmark
    internal val bars = R.drawable.fa_bars
    internal val ellipsis = R.drawable.fa_ellipsis
    internal val layers = R.drawable.fa_layer_group
    internal val plus = R.drawable.fa_plus
    internal val sort = R.drawable.fa_arrow_down_wide_short
}

@Composable fun FaIcon(@DrawableRes id: Int, contentDescription: String? = null,
    modifier: Modifier = Modifier.size(20.dp), tint: Color = LocalContentColor.current) {
    Icon(painterResource(id), contentDescription, modifier, tint)
}

/** Larger and darker than the toolbar's other icons by default: adding is the main action where it appears. */
@Composable fun AddIcon(modifier: Modifier = Modifier.size(28.dp), enabled: Boolean = true) {
    FaIcon(FaIcons.plus, null, modifier, MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
}

@Composable fun ChevronIcon(right: Boolean, contentDescription: String? = null, modifier: Modifier = Modifier.size(16.dp)) {
    FaIcon(if (right) FaIcons.chevronRight else FaIcons.chevronLeft, contentDescription, modifier)
}

/** The arrow at the end of a row that opens another page. */
@Composable fun RowChevron() {
    FaIcon(FaIcons.chevronRight, null, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Marks the chosen item in a list. */
@Composable fun SelectedIcon(modifier: Modifier = Modifier.size(18.dp), tint: Color = LocalContentColor.current) {
    FaIcon(FaIcons.check, stringResource(R.string.icon_selected), modifier, tint)
}
