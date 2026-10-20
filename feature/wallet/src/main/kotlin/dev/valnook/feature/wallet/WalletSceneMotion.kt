package dev.valnook.feature.wallet

internal const val WalletEnterDuration=560
internal const val WalletReturnDuration=620
internal const val WalletReturnCardDelay=80
internal const val WalletReturnCardDuration=420

internal data class WalletSceneFrame(val backFold:Float,val frontOffset:Float,val panelOffset:Float)

/**
 * Strength of the detail-only card glow. It comes in once the upper cards have mostly folded away, so it never
 * washes over them, and fades smoothly as soon as the card starts back. Both ends meet at 1 for the hand-over.
 */
internal fun walletGlow(progress:Float,returning:Boolean):Float {
    fun smooth(value:Float)=value.coerceIn(0f,1f).let{it*it*(3f-2f*it)}
    return if(returning)1f-smooth(progress/.35f) else smooth((progress-.45f)/.55f)
}

/** The lower stack and detail panel move as solid surfaces, never cross-fade. */
internal fun walletSceneFrame(progress:Float,returning:Boolean):WalletSceneFrame {
    fun phase(start:Float,end:Float)=((progress-start)/(end-start)).coerceIn(0f,1f)
    if(!returning) {
        return WalletSceneFrame(WalletSelectionEasing.transform(progress.coerceIn(0f,1f)),
            WalletSelectionEasing.transform(phase(0f,.72f)),
            1f-WalletSelectionEasing.transform(phase(.2f,1f)))
    }
    return WalletSceneFrame(1f-WalletSelectionEasing.transform(phase(
        WalletReturnCardDelay.toFloat()/WalletReturnDuration,
        (WalletReturnCardDelay+WalletReturnCardDuration).toFloat()/WalletReturnDuration)),
        1f-WalletSelectionEasing.transform(phase(.28f,.94f)),
        WalletSelectionEasing.transform(phase(0f,.38f)))
}
