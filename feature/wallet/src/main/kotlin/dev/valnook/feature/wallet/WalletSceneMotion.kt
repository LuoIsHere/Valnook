package dev.valnook.feature.wallet

internal const val WalletEnterDuration=560
internal const val WalletReturnDuration=620
internal const val WalletReturnCardDelay=80
internal const val WalletReturnCardDuration=420

internal data class WalletSceneFrame(val backFold:Float,val frontOffset:Float,val panelOffset:Float)

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
