package dev.valnook.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The three looks a button has in the app: no outlines, only fills. */
enum class ButtonStyle { PRIMARY, SECONDARY, DANGER }

/** Regular for page and card actions; compact where several sit in a line with other content. */
enum class ButtonSize { REGULAR, COMPACT }

/** The main action of a page, card or form, in the primary colour. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    size: ButtonSize = ButtonSize.REGULAR, icon: Int? = null) =
    ValnookButton(text, onClick, modifier, ButtonStyle.PRIMARY, size, enabled, icon)

/** Any other action, on a light tint of whatever it sits on. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    size: ButtonSize = ButtonSize.REGULAR, icon: Int? = null) =
    ValnookButton(text, onClick, modifier, ButtonStyle.SECONDARY, size, enabled, icon)

/** An action that deletes or clears, in red. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    size: ButtonSize = ButtonSize.REGULAR, icon: Int? = null) =
    ValnookButton(text, onClick, modifier, ButtonStyle.DANGER, size, enabled, icon)

/**
 * The one button of the app: a capsule with a fill and no outline, its label on one line. Its width and place come
 * from [modifier]; [icon] is a Font Awesome drawable shown before the label.
 */
@Composable
fun ValnookButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.SECONDARY, size: ButtonSize = ButtonSize.REGULAR, enabled: Boolean = true,
    icon: Int? = null) = ValnookButton(onClick, modifier, style, size, enabled) {
    if (icon != null) FaIcon(icon, null, Modifier.size(if (size == ButtonSize.COMPACT) 14.dp else 16.dp), LocalContentColor.current)
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** [ValnookButton] with its own [content], for a label that is more than one line of text. */
@Composable
fun ValnookButton(onClick: () -> Unit, modifier: Modifier = Modifier, style: ButtonStyle = ButtonStyle.SECONDARY,
    size: ButtonSize = ButtonSize.REGULAR, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (fill, tint) = when (style) {
        ButtonStyle.PRIMARY -> colors.primary to colors.onPrimary
        // A tint of the text colour, not a fixed fill: it shows on the page, on a card and on a bubble alike.
        ButtonStyle.SECONDARY -> colors.onSurface.copy(alpha = .10f) to colors.onSurface
        ButtonStyle.DANGER -> colors.errorContainer to colors.onErrorContainer
    }
    val compact = size == ButtonSize.COMPACT
    val padding = if (compact) PaddingValues(horizontal = 12.dp, vertical = 4.dp) else ButtonPadding
    Surface(onClick, modifier.semantics { role = Role.Button }, enabled = enabled, shape = CircleShape,
        color = if (enabled) fill else fill.copy(alpha = fill.alpha * if (style == ButtonStyle.PRIMARY) .12f else .5f),
        contentColor = if (enabled) tint else colors.onSurface.copy(alpha = .38f)) {
        Row(Modifier.defaultMinSize(minWidth = if (compact) 48.dp else 64.dp, minHeight = if (compact) 32.dp else 40.dp)
            .padding(padding), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            ProvideTextStyle(if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge) {
                content()
            }
        }
    }
}

/** [PrimaryButton] with its own [content]. */
@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    size: ButtonSize = ButtonSize.REGULAR, content: @Composable RowScope.() -> Unit) =
    ValnookButton(onClick, modifier, ButtonStyle.PRIMARY, size, enabled, content)

/** [SecondaryButton] with its own [content]. */
@Composable
fun SecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    size: ButtonSize = ButtonSize.REGULAR, content: @Composable RowScope.() -> Unit) =
    ValnookButton(onClick, modifier, ButtonStyle.SECONDARY, size, enabled, content)

/** [DangerButton] with its own [content]. */
@Composable
fun DangerButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    size: ButtonSize = ButtonSize.REGULAR, content: @Composable RowScope.() -> Unit) =
    ValnookButton(onClick, modifier, ButtonStyle.DANGER, size, enabled, content)

/** A text-only action, as in the buttons of a dialog; [danger] turns it red for one that deletes. */
@Composable
fun ValnookTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    danger: Boolean = false) {
    TextButton(onClick, modifier, enabled = enabled, colors = if (danger)
        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors()) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val ButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
