package dev.valnook.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.GlassChoiceDialog
import dev.valnook.designsystem.LocalGainLossPalette
import dev.valnook.designsystem.Space
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.model.GainLossColorScheme

@Composable
internal fun AppearanceSettingsDialog(vm: SettingsViewModel, language: Boolean, visible: Boolean,
    onClose: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val savedChoice = if (language) state.savedSettings.language.name else state.savedSettings.gainLossColors.name
    LaunchedEffect(savedChoice, state.busy, pending) {
        if (pending != null && !state.busy && savedChoice == pending) { pending = null; onClose() }
    }
    val dismiss = { if (!state.busy) { pending = null; vm.discardAndReload(); onClose() }; Unit }
    val error = state.error?.let { settingsErrorMessage(it) }
    if (language) {
        GlassChoiceDialog(visible, stringResource(R.string.settings_language), AppLanguage.entries,
            state.savedSettings.language, { languageLabel(it) }, onSelect = {
                if (it == state.savedSettings.language) dismiss() else {
                    vm.discardAndReload(); vm.selectLanguage(it); pending = it.name; vm.saveLanguage()
                }
            }, onDismiss = dismiss, tag = "language-picker", optionTag = { "language-option-${it.name}" },
            busy = state.busy, error = error)
    } else {
        val palette = LocalGainLossPalette.current
        GlassChoiceDialog(visible, stringResource(R.string.settings_gain_loss_colors), GainLossColorScheme.entries,
            state.savedSettings.gainLossColors, { colorLabel(it) }, onSelect = {
                if (it == state.savedSettings.gainLossColors) dismiss() else {
                    vm.discardAndReload(); vm.selectGainLossColors(it); pending = it.name; vm.saveGainLossColors()
                }
            }, onDismiss = dismiss, tag = "colors-picker", optionTag = { "colors-option-${it.name}" },
            busy = state.busy, error = error, supporting = { choice ->
                val current = choice == state.savedSettings.gainLossColors
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    Text(stringResource(R.string.settings_color_gain), color = if (current) palette.gain else palette.loss,
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.settings_color_loss), color = if (current) palette.loss else palette.gain,
                        style = MaterialTheme.typography.bodySmall)
                }
            })
    }
}
