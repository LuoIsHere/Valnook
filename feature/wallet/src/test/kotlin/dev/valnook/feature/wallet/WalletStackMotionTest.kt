package dev.valnook.feature.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletStackMotionTest {
    @Test fun stationary_card_and_small_boundary_jitter_do_not_reorder() {
        assertEquals(2,walletDropIndex(120f,2,8,60f,8f))
        assertEquals(2,walletDropIndex(157f,2,8,60f,8f))
        assertEquals(3,walletDropIndex(159f,2,8,60f,8f))
        assertEquals(3,walletDropIndex(149f,3,8,60f,8f))
        assertEquals(2,walletDropIndex(141f,3,8,60f,8f))
    }
    @Test fun large_moves_and_edge_scrolling_reach_every_slot_without_overflow() {
        assertEquals(0,walletDropIndex(-500f,25,50,60f,8f))
        assertEquals(49,walletDropIndex(9000f,25,50,60f,8f))
        assertEquals(29,walletDropIndex(1740f,25,50,60f,8f))
    }
    @Test fun scrolled_cards_hold_at_the_top_edge_until_the_next_slot_covers_them() {
        assertEquals(0f,walletPinOffset(120f,0f),0f)
        assertEquals(0f,walletPinOffset(120f,120f),0f)
        assertEquals(30f,walletPinOffset(120f,150f),0f)
        assertFalse(walletPinCovered(120f,179f,60f))
        assertTrue(walletPinCovered(120f,180f,60f))
    }
}
