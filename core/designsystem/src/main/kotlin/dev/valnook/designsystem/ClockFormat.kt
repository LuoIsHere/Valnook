package dev.valnook.designsystem

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Hours and minutes as the system shows them: its 12- or 24-hour setting, in the app's language ("下午3:05", "15:05"). */
fun clockFormatter(locale: Locale, is24Hour: Boolean): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hm"), locale)

/** [clockFormatter] for this screen's language and the system's current setting. */
@Composable fun rememberClockFormatter(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    return remember(locale, is24Hour) { clockFormatter(locale, is24Hour) }
}
