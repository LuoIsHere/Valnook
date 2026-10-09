package dev.valnook.feature.wallet

import org.junit.Assert.assertEquals
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
}
