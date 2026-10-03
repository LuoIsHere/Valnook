package dev.valnook.data.transaction

import dev.valnook.data.database.*
import dev.valnook.data.repository.valid_name
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*

internal class InstrumentCommandHandler(private val db: ValnookDatabase, private val cash: CashWriter,
    private val fault: (TransactionPoint) -> Unit) {
    suspend fun save(command: SaveInstrument, now: Long): OperationResult {
        val name = valid_name(command.name)
        if (command.symbol.length > 100) throw DomainException(ErrorCode.FORMAT)
        if (db.instruments().type(command.typeId) == null) throw DomainException(ErrorCode.NOT_FOUND)
        val currency = cash.currency(command.currencyCode)
        R.check_nonnegative(command.currentPriceE5)
        // The legacy presentation projection uses E8; keep that conversion bounded as well.
        R.exact_long(java.math.BigDecimal.valueOf(command.currentPriceE5).multiply(java.math.BigDecimal("1000")))
        val old = command.instrumentId?.let { db.instruments().instrument(it) ?: throw DomainException(ErrorCode.NOT_FOUND) }
        if (old?.revision != command.expectedRevision) throw DomainException(ErrorCode.STALE_RECORD)
        if (old != null && old.currency_code != currency.code) {
            if (old.currency_locked) throw DomainException(ErrorCode.CURRENCY_LOCKED)
            if (!command.currencyPriceConfirmed) throw DomainException(ErrorCode.PRICE_CONFIRMATION)
        }
        if (old != null && old.symbol_locked && old.symbol != command.symbol.trim()) {
            throw DomainException(ErrorCode.SYMBOL_LOCKED)
        }
        val value = InstrumentEntity(id = old?.id ?: 0, asset_type_id = command.typeId, name = name,
            symbol = command.symbol.trim(), currency_code = currency.code, current_price_e5 = command.currentPriceE5,
            currency_locked = old?.currency_locked ?: false, revision = R.add(old?.revision ?: 0, 1),
            symbol_locked = old?.symbol_locked ?: false,
            price_updated_at_ms = if (old?.current_price_e5 == command.currentPriceE5 && old.currency_code == currency.code)
                old.price_updated_at_ms else now,
            created_at_ms = old?.created_at_ms ?: now, updated_at_ms = now)
        val id = if (old == null) db.instruments().insertInstrument(value) else {
            if (db.instruments().updateInstrument(value) != 1) throw DomainException(ErrorCode.STALE_RECORD)
            old.id
        }
        if (old == null || old.current_price_e5 != value.current_price_e5 || old.currency_code != value.currency_code) {
            db.statistics().insertPrice(InstrumentPriceEntity(instrument_id = id,
                price_e5 = value.current_price_e5, currency_code = value.currency_code,
                effective_at_ms = now, created_at_ms = now))
        }
        fault(TransactionPoint.AFTER_BUSINESS)
        return OperationResult("INSTRUMENT", id)
    }

    suspend fun editPrice(command: EditInstrumentPrice, now: Long): OperationResult {
        val old = db.statistics().price(command.priceRecordId) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (old.is_deleted || old.revision != command.expectedRevision) throw DomainException(ErrorCode.STALE_RECORD)
        R.check_nonnegative(command.priceE5)
        if (db.statistics().editPrice(old.id, old.revision, command.priceE5, command.effectiveAtMs, now) != 1) {
            throw DomainException(ErrorCode.STALE_RECORD)
        }
        val latest = db.statistics().latestPrice(old.instrument_id, now)
        if (latest != null) db.instruments().updateCurrentPriceFromHistory(old.instrument_id,
            latest.price_e5, latest.effective_at_ms, now)
        fault(TransactionPoint.AFTER_BUSINESS)
        return OperationResult("INSTRUMENT_PRICE", old.id)
    }
}
