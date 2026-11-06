package dev.valnook.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class PickerRateTextTest {
    @Test fun fetched_rates_keep_six_significant_digits() {
        assertEquals("8.95897", pickerRateText("8.958967926895"))
        assertEquals("0.0422529", pickerRateText("0.042252926015"))
        assertEquals("162.346", pickerRateText("162.345678901234"))
    }

    @Test fun short_rates_are_unchanged() {
        assertEquals("7", pickerRateText("7"))
        assertEquals("6.5", pickerRateText("6.50"))
        assertEquals("1", pickerRateText("1"))
    }

    @Test fun large_rates_keep_every_whole_digit() {
        assertEquals("15234568", pickerRateText("15234567.89"))
        assertEquals("16250", pickerRateText("16250.000000"))
    }

    @Test fun text_that_is_not_a_number_is_kept() {
        assertEquals("—", pickerRateText("—"))
    }
}
