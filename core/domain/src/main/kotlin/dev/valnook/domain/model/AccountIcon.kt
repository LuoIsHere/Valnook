package dev.valnook.domain.model

enum class AccountIconType { SYMBOL, IMAGE }

data class AccountIcon(val type: AccountIconType = AccountIconType.SYMBOL, val value: String = "account_balance") {
    val symbol: String get() = if (type == AccountIconType.SYMBOL) value else "account_balance"
    val imageKey: String? get() = value.takeIf { type == AccountIconType.IMAGE }
}

/** Null change preserves the stored icon. Image bytes never enter overview snapshots or audit text. */
data class AccountIconChange(val icon: AccountIcon, val image: ByteArray? = null) {
    override fun toString(): String = "AccountIconChange(icon=$icon, imageBytes=${image?.size ?: 0})"
}

object AccountSymbols {
    val keys = setOf("account_balance", "account_balance_wallet", "credit_card", "savings",
        "payments", "wallet", "trending_up", "show_chart", "monitoring", "business_center",
        "home", "apartment", "storefront", "shopping_bag", "work", "paid", "currency_exchange",
        "public", "flight", "directions_car", "school", "family_restroom", "favorite", "star")
    const val MAX_IMAGE_BYTES = 128 * 1024
}
