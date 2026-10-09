package dev.valnook.domain.model

data class WalletCard(val id: Long, val name: String, val imageKey: String?,
    val boundCashAccountId: Long?, val displayOrder: Long, val createdAtMs: Long,
    val updatedAtMs: Long, val revision: Long, val bindingLost: Boolean = false)

data class WalletImage(val key: String, val bytes: ByteArray, val width: Int, val height: Int,
    val tint: Long)

object WalletRules {
    const val ASPECT = 1.586f
    const val MAX_IMAGE_BYTES = 512 * 1024
    const val MAX_INPUT_BYTES = 20 * 1024 * 1024
    const val MAX_PIXELS = 40_000_000L
    const val MAX_EDGE = 1600
    fun name(value: String): String = value.trim().also {
        require(it.codePointCount(0, it.length) in 1..40)
    }
}
