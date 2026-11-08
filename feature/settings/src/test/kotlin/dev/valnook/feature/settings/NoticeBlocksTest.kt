package dev.valnook.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The notices file splits into titles, kept lines and joined prose, as the third-party page shows them. */
class NoticeBlocksTest {
    private val rule = "=".repeat(78)
    private val sample = """
        THIRD-PARTY NOTICES

        Valnook's own code is licensed under the MIT License (see LICENSE). The Valnook
        app also contains the third-party material listed here, which stays under its
        own terms.

        $rule
        1. ICON ARTWORK
        $rule

        Font Awesome Free 7.3.1
        Copyright 2026 Fonticons, Inc.
        Used in: core/designsystem/src/main/res/drawable/fa_*.xml
                 app/src/main/assets/webadmin/symbols.js

        Apache License 2.0 (text in section 5)

          androidx.activity:activity:1.13.0
          androidx.annotation:annotation:1.9.1

        --- jakarta.inject:jakarta.inject-api:2.0.1 ---
    """.trimIndent()

    @Test fun titles_lists_and_prose_come_apart() {
        assertEquals(listOf(
            NoticeBlock.Heading("THIRD-PARTY NOTICES"),
            NoticeBlock.Prose("Valnook's own code is licensed under the MIT License (see LICENSE). The Valnook " +
                "app also contains the third-party material listed here, which stays under its own terms."),
            NoticeBlock.Heading("1. ICON ARTWORK"),
            NoticeBlock.Lines("Font Awesome Free 7.3.1\nCopyright 2026 Fonticons, Inc.\n" +
                "Used in: core/designsystem/src/main/res/drawable/fa_*.xml\napp/src/main/assets/webadmin/symbols.js"),
            NoticeBlock.Lines("Apache License 2.0 (text in section 5)"),
            NoticeBlock.Lines("androidx.activity:activity:1.13.0\nandroidx.annotation:annotation:1.9.1"),
            NoticeBlock.Heading("jakarta.inject:jakarta.inject-api:2.0.1"),
        ), noticeBlocks(sample))
    }

    @Test fun windows_line_endings_read_the_same() {
        assertEquals(noticeBlocks(sample), noticeBlocks(sample.replace("\n", "\r\n")))
    }
}
