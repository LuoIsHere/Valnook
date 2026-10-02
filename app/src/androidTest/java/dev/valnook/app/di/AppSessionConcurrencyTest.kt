package dev.valnook.app.di

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.OperationResult
import dev.valnook.domain.repository.DataMaintenance
import dev.valnook.domain.repository.FinancialCommand
import dev.valnook.domain.repository.FinancialCommands
import dev.valnook.domain.repository.SaveAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppSessionConcurrencyTest {
    private val context=ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock=Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"),ZoneOffset.UTC)
    private lateinit var database:ValnookDatabase

    @Before fun prepare(){
        database=Room.inMemoryDatabaseBuilder(context,ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed).build()
    }

    @After fun close(){database.close()}

    @Test fun write_already_inside_the_session_boundary_finishes_before_demo_switch()=runBlocking {
        val commands=BlockingCommands()
        val manager=AppSessionManager(context,createDatabaseGraph(database,clock).copy(commands=commands),database,clock)
        val original=manager.session.value
        val write=async { original.graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,
            "ordered write","",emptyList())) }
        commands.started.await()
        val switching=async { manager.enterDemo() }
        yield()
        assertFalse(switching.isCompleted)
        commands.release.complete(Unit)
        assertEquals(OperationResult("ACCOUNT",77),write.await())
        switching.await()
        assertEquals(DataMode.DEMO,manager.session.value.mode)
        expectExpired { original.graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,
            "late stale write","",emptyList())) }
        assertEquals(1,commands.calls)
        manager.exitDemo()
    }

    @Test fun request_waiting_behind_clear_is_rejected_after_the_new_generation_is_published()=runBlocking {
        val commands=CountingCommands()
        val maintenance=BlockingMaintenance()
        val manager=AppSessionManager(context,createDatabaseGraph(database,clock).copy(
            commands=commands,maintenance=maintenance),database,clock)
        val original=manager.session.value
        val challenge=manager.issueClearChallenge()
        val clearing=async { manager.clearRealData(challenge,challenge) }
        maintenance.started.await()
        val late=async { runCatching { original.graph.commands.execute(SaveAccount(
            UUID.randomUUID().toString(),null,null,"blocked stale write","",emptyList())) } }
        yield()
        assertFalse(late.isCompleted)
        maintenance.release.complete(Unit)
        clearing.await()
        val failure=late.await().exceptionOrNull()
        assertTrue(failure is DomainException)
        assertEquals(ErrorCode.SESSION_EXPIRED,(failure as DomainException).code)
        assertEquals(0,commands.calls)
        assertTrue(manager.session.value.id!=original.id)
    }

    private suspend fun expectExpired(block:suspend ()->Unit){
        try{block();throw AssertionError("Expected SESSION_EXPIRED")}
        catch(error:DomainException){assertEquals(ErrorCode.SESSION_EXPIRED,error.code)}
    }

    private class BlockingCommands:FinancialCommands{
        val started=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        var calls=0
        override suspend fun execute(command:FinancialCommand):OperationResult{
            calls++
            started.complete(Unit)
            release.await()
            return OperationResult("ACCOUNT",77)
        }
    }

    private class CountingCommands:FinancialCommands{
        var calls=0
        override suspend fun execute(command:FinancialCommand):OperationResult{
            calls++
            return OperationResult("ACCOUNT",88)
        }
    }

    private class BlockingMaintenance:DataMaintenance{
        val started=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        override suspend fun clearBusinessData(){
            started.complete(Unit)
            release.await()
        }
    }
}
