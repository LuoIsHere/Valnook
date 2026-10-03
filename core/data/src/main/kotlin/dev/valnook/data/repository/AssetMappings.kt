package dev.valnook.data.repository

import dev.valnook.data.database.*
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules

internal fun InvestmentWithType.toModel(): Investment {
    val position = asset
    return Investment(position.id, position.savings_account_id, asset_type_id, type_name, name, symbol,
        Currency.of(currency_code), position.holding_quantity_e8,
        DecimalRules.exact_long(java.math.BigDecimal.valueOf(current_price_e5).multiply(java.math.BigDecimal("1000"))),
        price_updated_at_ms, position.revision, position.last_activity_at_ms,
        position.instrument_id, position.remaining_cost, position.realized_profit,
        position.chronology_valid, position.algorithm_version)
}
internal fun TradeEntity.toModel(): Trade = Trade(id, investment_id, Direction.valueOf(direction),
    quantity_e8, execution_price_e8, amount_minor, Currency.of(currency_code), cash_linked, occurred_at_ms, revision,
    cash_account_id, fee_minor)

internal fun InstrumentWithType.toModel(): Instrument {
    val value = instrument
    return Instrument(value.id, value.name, value.symbol, value.asset_type_id, type_name,
        Currency.of(value.currency_code), value.current_price_e5, value.currency_locked, value.revision,
        value.price_updated_at_ms, value.symbol_locked)
}

internal fun settingsModel(settings: SettingsEntity?, rates: List<FxRateEntity>): AppSettings = AppSettings(
    settings?.base_currency?.let(Currency::of), rates.map {
        FxRate(Currency.of(it.source_currency), Currency.of(it.target_currency), it.rate.toBigDecimal())
    }, settings?.revision ?: 0,
    settings?.language?.let(AppLanguage::valueOf) ?: AppLanguage.SYSTEM,
    settings?.gain_loss_scheme?.let(GainLossColorScheme::valueOf) ?: GainLossColorScheme.GREEN_GAIN,
    parseNavigation(settings)
)

private fun parseNavigation(settings: SettingsEntity?): NavigationConfiguration {
    fun ids(raw: String?): List<NavigationItemId> = raw.orEmpty().split(',').mapNotNull {
        runCatching { NavigationItemId.valueOf(it) }.getOrNull()
    }
    val order = ids(settings?.navigation_order)
    val visible = ids(settings?.navigation_visible).toSet()
    return runCatching { NavigationConfiguration(order, visible) }.getOrElse { NavigationConfiguration() }
}
