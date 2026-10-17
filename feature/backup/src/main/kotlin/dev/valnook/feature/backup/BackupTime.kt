package dev.valnook.feature.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

private val LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

/**
 * Backups keep every time in UTC; only the screen shows them in [zone], with the offset written out so a
 * time can still be matched against UTC files and cloud metadata. Text that is not a UTC instant is shown as is.
 */
internal fun formatBackupTime(utc: String, zone: ZoneId): String {
    val instant = try { Instant.parse(utc.trim()) } catch (_: DateTimeParseException) { return utc }
    val local = instant.atZone(zone)
    return "${LOCAL_TIME.format(local)} (${utcOffsetLabel(local.offset.totalSeconds)})"
}

/** "UTC+8", "UTC-3:30", "UTC+0". */
private fun utcOffsetLabel(totalSeconds: Int): String {
    val sign = if (totalSeconds < 0) "-" else "+"
    val minutes = abs(totalSeconds) / 60
    return "UTC$sign${minutes / 60}" + if (minutes % 60 == 0) "" else ":%02d".format(Locale.ROOT, minutes % 60)
}

/** Reads the device zone on every call rather than caching it. */
internal fun backupTimeText(utc: String): String = formatBackupTime(utc, ZoneId.systemDefault())
