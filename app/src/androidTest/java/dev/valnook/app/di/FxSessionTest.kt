package dev.valnook.app.di

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.fx.FxSyncEngine
import dev.valnook.data.fx.FxSyncOutcome
import dev.valnook.data.fx.FxWriteGate
import dev.valnook.domain.fx.FxSyncNotice
import dev.valnook.domain.fx.FxSyncStatus
import dev.valnook.domain.fx.FxSyncTrigger
import dev.valnook.domain.model.Currency
import dev.valnook.domain.repository.SaveFinancialSettings
import dev.valnook.domain.repository.SaveLanguage
import dev.valnook.domain.model.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Automatic rates belong to the real dataset: the demo never asks for a sync or changes the switch. */
@RunWith(AndroidJUnit4::class)
class FxSessionTest {
    private class RecordingEngine : FxSyncEngine {
        val calls = mutableListOf<String>()
        var gate: FxWriteGate? = null
        override fun observeStatus(): Flow<FxSyncStatus> = flowOf(FxSyncStatus(available = true, autoEnabled = true))
        override fun observeNotice(): Flow<FxSyncNotice?> = flowOf(null)
        override fun consumeNotice(id: String) { calls += "consume:$id" }
        override fun attach(gate: FxWriteGate) { this.gate = gate; calls += "attach" }
        override fun request(trigger: FxSyncTrigger) { calls += "request:$trigger" }
        override suspend fun sync(trigger: FxSyncTrigger): FxSyncOutcome { calls += "sync:$trigger"; return FxSyncOutcome.SKIPPED }
        override suspend fun setAutoEnabled(on: Boolean, gate: FxWriteGate) { gate.write { calls += "switch:$on" } }
        override suspend fun dismissNotice(gate: FxWriteGate) { gate.write { calls += "dismiss" } }
    }

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-28T04:00:00Z"), ZoneOffset.UTC)
    private lateinit var database: ValnookDatabase
    private val engine = RecordingEngine()
    private lateinit var manager: AppSessionManager

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
        manager = AppSessionManager(context, createDatabaseGraph(context, database, clock, currentBuildInfo(),
            persistentPrivateCards = false), database, clock, fxEngine = engine)
    }

    @After fun close() {
        runBlocking { manager.exitDemo() }
        database.close()
    }

    @Test fun real_session_requests_on_start_base_change_switch_and_manual_update() = runBlocking {
        assertEquals(listOf("attach"), engine.calls)
        manager.requestFxSync()
        val graph = manager.session.value.graph
        graph.settingsWriter.applyChange(SaveFinancialSettings(0, Currency.of("CNY"), emptyList()))
        graph.settingsWriter.applyChange(SaveLanguage(1, AppLanguage.ENGLISH))   // same base: no request
        graph.settingsWriter.applyChange(SaveFinancialSettings(2, Currency.of("CNY"), emptyList()))
        graph.settingsWriter.applyChange(SaveFinancialSettings(3, Currency.of("USD"), emptyList()))
        graph.fxSync.setAutoEnabled(true)
        graph.fxSync.refreshNow()
        graph.fxSync.consumeNotice("n1")
        assertEquals(listOf("attach", "request:START", "request:BASE_CHANGED", "request:BASE_CHANGED", "switch:true",
            "sync:MANUAL", "consume:n1"), engine.calls)
        assertTrue(graph.fxSync.observeStatus().first().available)
    }

    @Test fun demo_session_never_requests_or_switches() = runBlocking {
        manager.enterDemo()
        engine.calls.clear()
        val demo = manager.session.value.graph
        manager.requestFxSync()
        demo.settingsWriter.applyChange(SaveFinancialSettings(demo.settings.observeSettings().first().revision,
            Currency.of("USD"), emptyList()))
        demo.fxSync.setAutoEnabled(true)
        demo.fxSync.refreshNow()
        demo.fxSync.consumeNotice("n1")
        assertTrue(engine.calls.toString(), engine.calls.isEmpty())
        val status = demo.fxSync.observeStatus().first()
        assertFalse(status.available)
        assertFalse(status.autoEnabled)
    }

    @Test fun switch_write_needs_the_current_session() = runBlocking {
        val stale = manager.session.value.graph
        manager.enterDemo()
        manager.exitDemo()
        val error = runCatching { stale.fxSync.setAutoEnabled(true) }.exceptionOrNull()
        assertTrue(error.toString(), error is dev.valnook.domain.model.DomainException)
        assertFalse(engine.calls.any { it.startsWith("switch") })
    }
}
