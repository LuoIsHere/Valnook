package dev.valnook.domain.repository

/**
 * SECURITY: Device-only values. Never add this type to snapshots, audit payloads, saved state or portability DTOs.
 * 安全边界：包括背面/侧面配色在内，均不得进入备份、Excel、网页响应、日志或页面状态恢复。
 * Redacted toString is intentional; do not generate a toString that exposes constructor properties.
 * toString 必须保持脱敏，不得恢复为自动输出全部属性的实现。
 */
data class WalletPrivateContent(
    val number: String = "",
    val expiry: String = "",
    val cvv1: String = "",
    val cvv2: String = "",
    val backColor: Long = 0xff41464fL,
    val edgeColor: Long = 0xff858b94L,
    val revision: Long = 0,
    val showNumberSpacing: Boolean = true
) {
    override fun toString() = "WalletPrivateContent([redacted])"
    // Legacy decoding alone may preserve old CVVs; every new save uses the strict default.
    // 仅旧密文读取允许保留历史 CVV；新保存必须使用默认的 0–4 位数字校验，不能借兼容逻辑绕过。
    fun validated(legacyCvv: Boolean = false): WalletPrivateContent {
        require(number.codePointCount(0, number.length) <= 38 && number.none { it.isISOControl() })
        require(expiry.length <= 16 && cvv1.length <= 16 && cvv2.length <= 16)
        require(listOf(expiry, cvv1, cvv2).none { text -> text.any { it.isISOControl() } })
        require(legacyCvv || (walletCvvValid(cvv1) && walletCvvValid(cvv2)))
        require(backColor in 0xff000000L..0xffffffffL && edgeColor in 0xff000000L..0xffffffffL)
        return this
    }
}

interface WalletPrivateRepository {
    suspend fun read(cardId: Long): WalletPrivateContent
    suspend fun save(cardId: Long, content: WalletPrivateContent): WalletPrivateContent
    suspend fun warningDismissed(): Boolean
    suspend fun dismissWarning()
}

/** Formatting never changes the stored value or treats card numbers as numeric values. */
fun walletCvvValid(value: String): Boolean = value.length <= 4 && value.all { it in '0'..'9' }

fun walletNumberLines(number: String, showSpacing: Boolean = true): List<String> {
    val chars = number.codePoints().toArray().map { String(Character.toChars(it)) }
    if (chars.isEmpty()) return emptyList()
    if (!showSpacing) return chars.chunked(19).map { it.joinToString("") }
    if (chars.size == 16) return listOf(chars.chunked(4).joinToString(" ") { it.joinToString("") })
    return chars.chunked(19).mapIndexed { index, row ->
        if (index == 0 && row.size > 6) row.take(6).joinToString("") + " " + row.drop(6).joinToString("")
        else row.joinToString("")
    }
}
