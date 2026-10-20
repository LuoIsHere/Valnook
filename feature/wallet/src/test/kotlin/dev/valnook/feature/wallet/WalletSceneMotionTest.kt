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

    @Test fun glow_waits_for_the_fold_and_hands_over_without_a_jump() {
        val opening=(0..100).map{walletGlow(it/100f,false)}
        // Dark while the upper cards are still folding (up to ~250ms of 560ms), fully lit when the card arrives.
        assertTrue(opening.take(46).all{it==0f})
        assertEquals(1f,opening.last(),0f)
        assertTrue(opening.zipWithNext().all{(a,b)->b>=a&&b-a<=.03f})
        val leaving=(0..100).map{walletGlow(it/100f,true)}
        // The returning card starts exactly where the detail card was, then fades out in ~220ms of 620ms.
        assertEquals(opening.last(),leaving.first(),0f)
        assertTrue(leaving.drop(35).all{it==0f})
        assertTrue(leaving.zipWithNext().all{(a,b)->b<=a&&a-b<=.05f})
    }
}
