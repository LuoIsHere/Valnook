package dev.valnook.designsystem

import dev.valnook.core.designsystem.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillIconsTest {
    @Test fun every_key_has_its_own_drawable_and_unknown_keys_fall_back() {
        val keys = BillIcons.groups.flatMap { it.second }
        assertEquals("a key is listed twice", keys.size, keys.toSet().size)
        val resources = keys.map(BillIcons::resource)
        assertEquals("two keys share a drawable", keys.size, resources.toSet().size)
        keys.filter { it != "circle-question" }.forEach {
            assertNotEquals("$it falls back", R.drawable.fa_circle_question, BillIcons.resource(it))
        }
        assertEquals(R.drawable.fa_circle_question, BillIcons.resource("no-such-icon"))
    }

    @Test fun every_group_has_a_label_and_icons() {
        BillIcons.groups.forEach { (group, keys) ->
            assertTrue(group, keys.isNotEmpty())
            if (group != "other") assertNotEquals(group, R.string.bill_icon_group_other, BillIcons.groupLabel(group))
        }
    }
}
