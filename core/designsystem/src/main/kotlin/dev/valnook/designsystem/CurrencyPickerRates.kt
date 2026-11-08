package dev.valnook.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Display values supplied by the app; the picker does not load or calculate exchange rates. With [shown] false the
 * picker leaves rates out altogether, as during first-run setup, where none have been fetched or entered yet.
 */
data class CurrencyPickerRates(val baseCode: String? = null, val rates: Map<String, String> = emptyMap(),
    val shown: Boolean = true)

val LocalCurrencyPickerRates = compositionLocalOf { CurrencyPickerRates() }

/**
 * The picker only shows a rate for reference, so six significant digits are enough and keep it on one line; fetched
 * rates carry up to twelve decimal places. Whole digits are never dropped; anything that is not a plain number is
 * shown as it is.
 */
fun pickerRateText(rate: String): String {
    val value = rate.toBigDecimalOrNull() ?: return rate
    val wholeDigits = value.precision() - value.scale()
    val shown = if (wholeDigits >= 6) value.setScale(0, java.math.RoundingMode.HALF_UP)
        else value.round(java.math.MathContext(6, java.math.RoundingMode.HALF_UP))
    return shown.stripTrailingZeros().toPlainString()
}

/** The interface's ISO 639 language code. Read through the configuration so a language change recomposes. */
@Composable fun interfaceLanguage(): String = LocalConfiguration.current.locales[0].language
