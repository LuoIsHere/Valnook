package dev.valnook.feature.wallet

import org.junit.Assert.*
import org.junit.Test

class WalletSceneMotionTest {
    @Test fun return_unfolds_back_cards_with_the_selected_card_and_settles_without_overshoot() {
        val early=walletSceneFrame(.1f,true)
        assertEquals(1f,early.backFold,0f)
        assertEquals(1f,early.frontOffset,0f)
        val moving=walletSceneFrame(.6f,true)
        assertTrue(moving.frontOffset in 0f..1f)
        assertTrue(moving.frontOffset<1f)
        val finished=walletSceneFrame(1f,true)
        assertEquals(0f,finished.backFold,.0001f)
        assertEquals(0f,finished.frontOffset,.0001f)
        assertEquals(1f,finished.panelOffset,.0001f)
        // Solid cards must settle without overshooting their original overlap.
        val positions=(0..100).map{walletSceneFrame(it/100f,true).frontOffset}
        assertTrue(positions.all{it in 0f..1f})
        assertTrue(positions.zipWithNext().all{(a,b)->b<=a})
        val unfolded=(0..100).map{walletSceneFrame(it/100f,true).backFold}
        assertTrue(unfolded.zipWithNext().all{(a,b)->b<=a})
        assertTrue(walletSceneFrame(.5f,true).backFold in 0.01f..0.99f)
    }

    @Test fun opening_moves_the_foreground_out_and_delays_the_detail_panel() {
        val initial=walletSceneFrame(0f,false)
        assertEquals(0f,initial.backFold,0f)
        assertEquals(0f,initial.frontOffset,0f)
        assertEquals(1f,walletSceneFrame(.19f,false).panelOffset,0f)
        val finished=walletSceneFrame(1f,false)
        assertEquals(1f,finished.backFold,0f)
        assertEquals(1f,finished.frontOffset,.0001f)
        assertEquals(0f,finished.panelOffset,.0001f)
    }
}
