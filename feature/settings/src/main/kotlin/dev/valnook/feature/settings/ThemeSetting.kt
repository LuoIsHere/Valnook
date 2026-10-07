package dev.valnook.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ThemeSetting(mode: AppThemeMode, onChange: suspend (AppThemeMode) -> Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SettingEntry(stringResource(R.string.settings_theme), themeLabel(mode),
        { expanded = true; failed = false }, Modifier.testTag("settings-theme"))
    GlassChoiceDialog(expanded, stringResource(R.string.settings_theme), AppThemeMode.entries, mode,
        label = { themeLabel(it) }, tag = "theme-picker", optionTag = { "theme-option-${it.name}" },
        busy = busy, error = if (failed) stringResource(R.string.settings_theme_save_failed) else null,
        onDismiss = { expanded = false }, onSelect = { choice ->
            if (choice == mode) expanded = false else scope.launch {
                busy = true
                failed = false
                try {
                    if (onChange(choice)) expanded = false else failed = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                } finally { busy = false }
            }
        })
}

@Composable
internal fun themeLabel(mode: AppThemeMode): String = stringResource(when (mode) {
    AppThemeMode.SYSTEM -> R.string.settings_theme_system
    AppThemeMode.DARK -> R.string.settings_theme_dark
    AppThemeMode.LIGHT -> R.string.settings_theme_light
})
