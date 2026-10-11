package dev.valnook.domain

import dev.valnook.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class WalletFaceStyleTest {
    @Test fun all_styles_round_trip_without_private_fields_or_image_data() {
        WalletFaceBackground.entries.forEach { background -> WalletFaceGeometry.entries.forEach { geometry ->
            val style=WalletFaceStyle(background,geometry)
            assertEquals(style,WalletFaceStyle.decode(style.encode()))
            assertTrue(style.encode().length<100)
        } }
    }
    @Test fun reject_unknown_versions_shapes_transparent_colors_and_extra_fields() {
        listOf("2|SOLID|NONE|ff000000|ff000000|ffffffff","1|SOLID|UNKNOWN|ff000000|ff000000|ffffffff",
            "1|SOLID|NONE|00000000|ff000000|ffffffff",WalletFaceStyle().encode()+"|extra").forEach {
            assertTrue(it,runCatching{WalletFaceStyle.decode(it)}.isFailure)
        }
    }
}
