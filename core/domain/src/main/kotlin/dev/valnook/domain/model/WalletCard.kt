package dev.valnook.domain.model

data class WalletCard(val id: Long, val name: String, val imageKey: String?,
    val boundCashAccountId: Long?, val displayOrder: Long, val createdAtMs: Long,
    val updatedAtMs: Long, val revision: Long, val bindingLost: Boolean = false, val faceStyle: WalletFaceStyle? = null)

data class WalletImage(val key: String, val bytes: ByteArray, val width: Int, val height: Int,
    val tint: Long)

/**
 * A card face kept in the face library. Faces stay after a card stops using them, until deleted from the library;
 * [cardNames] lists the cards that use it, in wallet order, and a face in use cannot be deleted.
 */
data class WalletFace(val key: String, val label: String, val width: Int, val height: Int, val tint: Long,
    val addedAtMs: Long, val cardNames: List<String>) {
    val inUse get() = cardNames.isNotEmpty()
}

object WalletRules {
    const val ASPECT = 1.586f
    const val MAX_IMAGE_BYTES = 512 * 1024
    const val MAX_INPUT_BYTES = 20 * 1024 * 1024
    const val MAX_PIXELS = 40_000_000L
    const val MAX_EDGE = 1600
    /** Faces the library holds; only adding a new face is refused at the limit. */
    const val MAX_FACES = 50
    fun name(value: String): String = value.trim().also {
        require(it.codePointCount(0, it.length) in 1..40)
    }
}
