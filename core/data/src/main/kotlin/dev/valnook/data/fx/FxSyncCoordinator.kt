package dev.valnook.data.fx

import androidx.room.withTransaction
import dev.valnook.data.audit.AuditRecorder
import dev.valnook.data.database.FxRateEntity
import dev.valnook.data.database.FxRateHistoryEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.fx.FxSyncError
import dev.valnook.domain.fx.FxSyncNotice
import dev.valnook.domain.fx.FxSyncStatus
import dev.valnook.domain.fx.FxSyncTrigger
import dev.valnook.domain.model.Currency
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Runs the final write of a sync under the session owner's write lock, so it never interleaves with restore,
 * clear or another local write. Network requests always happen outside it.
 */
fun interface FxWriteGate {
    suspend fun write(block: suspend () -> Unit)
}

/** The session manager's view of automatic exchange rates for the real dataset. */
interface FxSyncEngine {
    fun observeStatus(): Flow<FxSyncStatus>
    fun observeNotice(): Flow<FxSyncNotice?>
    fun consumeNotice(id: String)
    /**
     * Supplies the gate for every sync write and starts keeping `fx_rates` covering newly used currencies while
     * automatic rates are on (plan 3.1). Called once by the session owner.
     */
    fun attach(gate: FxWriteGate)
    /** Starts a sync in the background, for triggers nobody waits on. Failures end up in the status. */
    fun request(trigger: FxSyncTrigger)
    suspend fun sync(trigger: FxSyncTrigger): FxSyncOutcome
    /** Writes the switch through [gate], which may check the caller's session; turning it on starts a sync. */
    suspend fun setAutoEnabled(on: Boolean, gate: FxWriteGate)
}

enum class FxSyncOutcome { SKIPPED, SUCCEEDED, FAILED, DISCARDED }

object DisabledFxSyncEngine : FxSyncEngine {
    override fun observeStatus(): Flow<FxSyncStatus> = flowOf(FxSyncStatus())
    override fun observeNotice(): Flow<FxSyncNotice?> = flowOf(null)
    override fun consumeNotice(id: String) = Unit
    override fun attach(gate: FxWriteGate) = Unit
    override fun request(trigger: FxSyncTrigger) = Unit
    override suspend fun sync(trigger: FxSyncTrigger) = FxSyncOutcome.SKIPPED
    override suspend fun setAutoEnabled(on: Boolean, gate: FxWriteGate) = Unit
}

fun createFxSyncCoordinator(database: ValnookDatabase, clock: Clock): FxSyncEngine =
    FxSyncCoordinator(database, FrankfurterClient(UrlConnectionFrankfurterTransport()), clock)

/**
 * Fetches the days from the last stored one (or the statistics baseline) to today in one go, stores one row per
 * day and currency as the source answered, and replaces today's rates in `fx_rates`. A sync is all or nothing.
 */
internal class FxSyncCoordinator(
    private val db: ValnookDatabase,
    private val client: FrankfurterClient,
    private val clock: Clock,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : FxSyncEngine {
    private val running = MutableStateFlow(false)
    private val notice = MutableStateFlow<FxSyncNotice?>(null)
    private val singleFlight = Mutex()
    private val audit = AuditRecorder(db, clock)
    @Volatile private var gate = FxWriteGate { it() }

    override fun observeStatus(): Flow<FxSyncStatus> = combine(
        db.invalidationTracker.createFlow("fx_local_state", "fx_rate_history", "app_settings", "cash_accounts",
            "term_deposits", "investments", "investment_trades", "instruments").map { readStatus() },
        running
    ) { status, active -> status.copy(running = active) }.flowOn(Dispatchers.IO)

    override fun observeNotice(): Flow<FxSyncNotice?> = notice

    override fun consumeNotice(id: String) {
        notice.update { if (it?.id == id) null else it }
    }

    override fun request(trigger: FxSyncTrigger) {
        scope.launch { runCatching { sync(trigger) } }
    }

    override suspend fun setAutoEnabled(on: Boolean, gate: FxWriteGate) {
        gate.write { db.fx().setAutoEnabled(on) }
        if (on) request(FxSyncTrigger.ENABLED)
    }

    override fun attach(gate: FxWriteGate) {
        this.gate = gate
        scope.launch {
            db.invalidationTracker.createFlow("cash_accounts", "term_deposits", "investment_trades", "fx_rate_history", "fx_local_state")
                .map { db.fx().inUseCurrencies().toSet() to (db.fx().localState()?.auto_enabled == true) }
                .distinctUntilChanged()
                .collect { (_, enabled) -> if (enabled) runCatching { materialize() } }
        }
    }

    override suspend fun sync(trigger: FxSyncTrigger): FxSyncOutcome {
        if (!singleFlight.tryLock()) return FxSyncOutcome.SKIPPED
        try {
            running.value = true
            return run(trigger)
        } finally {
            running.value = false
            singleFlight.unlock()
        }
    }

    private data class Snapshot(val base: String, val lastDay: Long?, val restoreId: String?, val baselineDay: Long?)

    private suspend fun snapshot(): Snapshot? = db.withTransaction {
        val state = db.fx().localState() ?: return@withTransaction null
        val base = db.fx().baseCurrency() ?: return@withTransaction null
        if (!state.auto_enabled) return@withTransaction null
        Snapshot(base, db.fx().lastHistoryDay(base), db.audit().maintenanceState().last_restore_attempt_id,
            db.statistics().state()?.let { day(it.baseline_at_ms) })
    }

    /** True when nothing a sync depends on changed while its requests were in flight. */
    private suspend fun unchanged(snapshot: Snapshot): Boolean =
        db.fx().localState()?.auto_enabled == true && db.fx().baseCurrency() == snapshot.base &&
            db.fx().lastHistoryDay(snapshot.base) == snapshot.lastDay &&
            db.audit().maintenanceState().last_restore_attempt_id == snapshot.restoreId

    private suspend fun run(trigger: FxSyncTrigger): FxSyncOutcome {
        val now = clock.millis()
        val today = LocalDate.now(clock).toEpochDay()
        val state = db.fx().localState() ?: return FxSyncOutcome.SKIPPED
        if (!due(trigger, state.last_success_day, state.last_attempt_at_ms, state.last_error, now, today)) {
            return FxSyncOutcome.SKIPPED
        }
        val snapshot = snapshot() ?: return FxSyncOutcome.SKIPPED
        val start = snapshot.lastDay?.plus(1) ?: snapshot.baselineDay ?: today
        val quotes = Currency.supported.map { it.code } - snapshot.base
        val rows = if (start > today) emptyList() else try {
            val first = client.rates(snapshot.base, quotes, LocalDate.ofEpochDay(start))
            val rest = if (start < today) client.series(snapshot.base, quotes, LocalDate.ofEpochDay(start + 1), LocalDate.ofEpochDay(today))
                else emptyList()
            expandDaily(snapshot.base, start, today, first + rest, now)
        } catch (error: FrankfurterException) {
            return fail(trigger, snapshot, now, error.error.toSyncError())
        }
        var outcome = FxSyncOutcome.DISCARDED
        var missing = emptyList<String>()
        var dataDate: Long? = null
        try {
            gate.write {
                db.withTransaction {
                    if (!unchanged(snapshot)) return@withTransaction
                    if (rows.isNotEmpty()) db.fx().insertHistory(rows)
                    // A clock set back before the stored days keeps the current rates.
                    val changed = start <= today && replaceTodayRates(snapshot.base, today, now)
                    dataDate = rows.maxOfOrNull { it.source_date } ?: db.fx().localState()?.data_date
                    check(db.fx().recordSuccess(today, now, dataDate) == 1)
                    // Statistics still value every day at today's rates, so a rate change reaches back to the baseline.
                    val from = if (changed) snapshot.baselineDay else if (rows.isNotEmpty()) start else null
                    if (from != null && db.statistics().state() != null) db.statistics().invalidate(from)
                    missing = missingOn(snapshot.base, today)
                    outcome = FxSyncOutcome.SUCCEEDED
                }
            }
        } catch (error: android.database.SQLException) {
            return fail(trigger, snapshot, now, FxSyncError.STORAGE)
        }
        if (outcome == FxSyncOutcome.SUCCEEDED && missing.isNotEmpty() && trigger != FxSyncTrigger.MANUAL) {
            notice.value = FxSyncNotice(UUID.randomUUID().toString(), null, missing, dataDate?.let(LocalDate::ofEpochDay))
        }
        return outcome
    }

    private suspend fun fail(trigger: FxSyncTrigger, snapshot: Snapshot, now: Long,
        error: FxSyncError): FxSyncOutcome {
        var recorded = false
        gate.write {
            db.withTransaction {
                if (unchanged(snapshot)) recorded = db.fx().recordFailure(now, error.name) == 1
            }
        }
        if (!recorded) return FxSyncOutcome.DISCARDED
        if (trigger != FxSyncTrigger.MANUAL) {
            notice.value = FxSyncNotice(UUID.randomUUID().toString(), error, emptyList(),
                db.fx().localState()?.data_date?.let(LocalDate::ofEpochDay))
        }
        return FxSyncOutcome.FAILED
    }

    /**
     * Overwrites today's rate for every currency in use and every currency already listed under [base], inverted and
     * rounded once to 12 places. Other bases' rows stay. Returns whether any value changed.
     */
    private suspend fun replaceTodayRates(base: String, today: Long, now: Long): Boolean {
        val todayRows = db.fx().historyOn(base, today).associateBy { it.quote_currency }
        val existing = db.fx().ratesTo(base).associateBy { it.source_currency }
        val targets = (db.fx().inUseCurrencies() + existing.keys).toSet() - base
        val updates = targets.mapNotNull { code ->
            val raw = todayRows[code] ?: return@mapNotNull null
            val value = currentRateValue(BigDecimal(raw.rate)).stripTrailingZeros()
            val current = existing[code]?.rate?.let(::BigDecimal)
            if (current != null && current.compareTo(value) == 0) null
            else FxRateEntity(code, base, value.toPlainString(), now)
        }
        return saveRates(updates, now)
    }

    /** Adds `fx_rates` rows for in-use currencies that have none yet, from their latest stored day. Never goes online. */
    private suspend fun materialize() {
        gate.write {
            db.withTransaction {
                if (db.fx().localState()?.auto_enabled != true) return@withTransaction
                val base = db.fx().baseCurrency() ?: return@withTransaction
                val existing = db.fx().ratesTo(base).map { it.source_currency }.toSet()
                val now = clock.millis()
                val added = (db.fx().inUseCurrencies().toSet() - existing - base).mapNotNull { code ->
                    val latest = db.fx().latestHistory(base, code) ?: return@mapNotNull null
                    FxRateEntity(code, base, currentRateValue(BigDecimal(latest.rate)).stripTrailingZeros().toPlainString(), now)
                }
                if (saveRates(added, now)) {
                    db.statistics().state()?.let { db.statistics().invalidate(day(it.baseline_at_ms)) }
                }
            }
        }
    }

    /** Writes rate rows as one system change to the financial settings: a new revision and one audit event. */
    private suspend fun saveRates(values: List<FxRateEntity>, now: Long): Boolean {
        if (values.isEmpty()) return false
        val before = audit.settingsJson()
        db.fx().saveRates(values)
        db.fx().bumpSettingsRevision()
        audit.recordSystemRates(before, audit.settingsJson(), now)
        return true
    }

    private suspend fun missingOn(base: String, day: Long): List<String> {
        val present = db.fx().historyOn(base, day).map { it.quote_currency }.toSet()
        return (db.fx().inUseCurrencies().toSet() - base - present).sorted()
    }

    private suspend fun readStatus(): FxSyncStatus {
        val state = db.fx().localState() ?: return FxSyncStatus(available = true)
        val base = db.fx().baseCurrency()
        val missing = if (state.auto_enabled && base != null && state.last_success_day != null)
            missingOn(base, state.last_success_day) else emptyList()
        return FxSyncStatus(
            available = true,
            autoEnabled = state.auto_enabled,
            noticeDismissed = state.notice_dismissed,
            lastSuccessAtMs = state.last_success_at_ms,
            dataDate = state.data_date?.let(LocalDate::ofEpochDay),
            lastAttemptAtMs = state.last_attempt_at_ms,
            lastError = state.last_error?.let { name -> FxSyncError.entries.firstOrNull { it.name == name } },
            missingCurrencies = missing
        )
    }

    private fun day(epochMs: Long) = Instant.ofEpochMilli(epochMs).atZone(clock.zone).toLocalDate().toEpochDay()

    companion object {
        const val RETRY_WAIT_MS = 30L * 60 * 1000
        const val MANUAL_DEBOUNCE_MS = 10_000L

        /**
         * Start-up syncs run once per local day and wait 30 minutes after a failure; a clock set back counts as the
         * wait having passed. Other triggers always run, except that manual updates are spaced 10 seconds apart.
         */
        internal fun due(trigger: FxSyncTrigger, lastSuccessDay: Long?, lastAttemptMs: Long?, lastError: String?,
            now: Long, today: Long): Boolean = when (trigger) {
            FxSyncTrigger.START -> lastSuccessDay != today &&
                !(lastError != null && lastAttemptMs != null && now - lastAttemptMs in 0 until RETRY_WAIT_MS)
            FxSyncTrigger.MANUAL -> !(lastAttemptMs != null && now - lastAttemptMs in 0 until MANUAL_DEBOUNCE_MS)
            else -> true
        }
    }
}

private fun FrankfurterError.toSyncError() = when (this) {
    FrankfurterError.TIMEOUT -> FxSyncError.TIMEOUT
    FrankfurterError.DNS, FrankfurterError.NETWORK -> FxSyncError.NETWORK
    FrankfurterError.RATE_LIMITED -> FxSyncError.RATE_LIMITED
    FrankfurterError.CLIENT, FrankfurterError.SERVER -> FxSyncError.SERVICE
    FrankfurterError.PARSE -> FxSyncError.PARSE
}

/**
 * One row per quote and day from [start] to [end]: the source's value for that day, or its nearest earlier value.
 * The single-day answer for [start] already carries each quote's nearest earlier value; quotes the source never
 * had by a day get no row for it. Rates are stored as plain decimal text.
 */
internal fun expandDaily(base: String, start: Long, end: Long, points: List<FrankfurterRate>, fetchedAtMs: Long):
    List<FxRateHistoryEntity> = points.groupBy { it.quote }.toSortedMap().flatMap { (quote, values) ->
    val sorted = values.sortedBy { it.date }
    var index = -1
    (start..end).mapNotNull { day ->
        while (index + 1 < sorted.size && sorted[index + 1].date.toEpochDay() <= day) index++
        sorted.getOrNull(index)?.let {
            FxRateHistoryEntity(base, quote, day, it.date.toEpochDay(), it.rate.toPlainString(), fetchedAtMs)
        }
    }
}
