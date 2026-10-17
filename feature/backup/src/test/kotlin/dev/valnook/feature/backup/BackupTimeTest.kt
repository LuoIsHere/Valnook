package dev.valnook.feature.backup

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupTimeTest {
    @Test fun utc_instants_show_in_the_given_zone_with_their_offset() {
        assertEquals("2026-10-17 15:15:30 (UTC+8)", formatBackupTime("2026-10-17T07:15:30Z", ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-10-17 07:15:30 (UTC+0)", formatBackupTime("2026-10-17T07:15:30Z", ZoneId.of("UTC")))
    }

    @Test fun fractions_day_changes_and_partial_hour_offsets_are_kept() {
        assertEquals("2026-10-18 01:05:09 (UTC+8)", formatBackupTime("2026-10-17T17:05:09.123Z", ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-10-16 21:30:00 (UTC-4)", formatBackupTime("2026-10-17T01:30:00Z", ZoneId.of("America/New_York")))
        assertEquals("2026-10-17 12:45:00 (UTC+5:30)", formatBackupTime("2026-10-17T07:15:00Z", ZoneId.of("Asia/Kolkata")))
        assertEquals("2026-10-16 20:45:00 (UTC-10:30)", formatBackupTime("2026-10-17T07:15:00Z", ZoneId.of("-10:30")))
    }

    @Test fun text_that_is_not_a_utc_instant_is_shown_unchanged() {
        assertEquals("", formatBackupTime("", ZoneId.of("Asia/Shanghai")))
        assertEquals("unknown", formatBackupTime("unknown", ZoneId.of("Asia/Shanghai")))
    }
}
