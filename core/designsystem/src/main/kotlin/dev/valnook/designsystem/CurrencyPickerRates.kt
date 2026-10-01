package dev.valnook.designsystem

import androidx.compose.runtime.compositionLocalOf

/** Display values supplied by the app; the picker does not load or calculate exchange rates. */
data class CurrencyPickerRates(val baseCode: String? = null, val rates: Map<String, String> = emptyMap())

val LocalCurrencyPickerRates = compositionLocalOf { CurrencyPickerRates() }
