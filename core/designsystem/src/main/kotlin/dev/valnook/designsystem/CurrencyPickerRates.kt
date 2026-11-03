package dev.valnook.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration

/** Display values supplied by the app; the picker does not load or calculate exchange rates. */
data class CurrencyPickerRates(val baseCode: String? = null, val rates: Map<String, String> = emptyMap())

val LocalCurrencyPickerRates = compositionLocalOf { CurrencyPickerRates() }

/** The interface's ISO 639 language code. Read through the configuration so a language change recomposes. */
@Composable fun interfaceLanguage(): String = LocalConfiguration.current.locales[0].language
