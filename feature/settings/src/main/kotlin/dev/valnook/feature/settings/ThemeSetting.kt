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
import androidx.compose.ui.window.Dialog
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
    if (expanded) Dialog(onDismissRequest = { if (!busy) expanded = false }) {
        PopupBlurEffect()
        GlassCard(Modifier.testTag("theme-picker")) {
            Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.selectableGroup()) {
                    AppThemeMode.entries.forEach { choice ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("theme-option-${choice.name}").clip(RoundedCornerShape(12.dp))
                            .selectable(selected = choice == mode, enabled = !busy, role = Role.RadioButton) {
                                if (choice == mode) expanded = false else scope.launch {
                                    busy = true
                                    failed = false
                                    try {
                                        if (onChange(choice)) expanded = false else failed = true
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        android.util.Log.e("ThemeSettings", "Could not apply theme", error)
                                        failed = true
                                    } finally { busy = false }
                                }
                            }.padding(horizontal = Space.sm, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            RadioButton(selected = choice == mode, onClick = null, enabled = !busy)
                            Text(themeLabel(choice), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) HintMessage(stringResource(R.string.settings_theme_save_failed), isError = true)
                TextButton({ expanded = false }, Modifier.align(Alignment.End), enabled = !busy) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        }
    }
}

@Composable
internal fun themeLabel(mode: AppThemeMode): String = stringResource(when (mode) {
    AppThemeMode.SYSTEM -> R.string.settings_theme_system
    AppThemeMode.DARK -> R.string.settings_theme_dark
    AppThemeMode.LIGHT -> R.string.settings_theme_light
})
