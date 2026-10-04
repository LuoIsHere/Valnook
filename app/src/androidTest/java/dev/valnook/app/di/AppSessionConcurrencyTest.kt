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
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import dev.valnook.domain.portability.PortabilityErrorCode
import dev.valnook.domain.portability.PortabilityException
import dev.valnook.domain.webadmin.WebAdminError
import dev.valnook.domain.webadmin.WebAdminException

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
        val manager=AppSessionManager(context,createDatabaseGraph(context,database,clock,currentBuildInfo()).copy(commands=commands),database,clock)
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
        val manager=AppSessionManager(context,createDatabaseGraph(context,database,clock,currentBuildInfo()).copy(
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

    @Test fun demo_rejects_backup_capability_and_invalidates_real_file_callbacks()=runBlocking {
        val manager=AppSessionManager(context,
            createDatabaseGraph(context,database,clock,currentBuildInfo()),database,clock)
        val realPortability=manager.session.value.graph.portability
        manager.enterDemo()
        val demoPortability=manager.session.value.graph.portability
        assertFalse(demoPortability.backupAndRestoreAllowed)
        try {
            demoPortability.createBackup(UUID.randomUUID().toString(),ByteArrayOutputStream())
            throw AssertionError("Demo backup should be rejected")
        } catch (error:PortabilityException) {
            assertEquals(PortabilityErrorCode.DEMO_RESTRICTED,error.errorCode)
        }
        try {
            realPortability.exportWorkbook(UUID.randomUUID().toString(),
                dev.valnook.domain.model.AppLanguage.ENGLISH,false,ByteArrayOutputStream())
            throw AssertionError("Stale real callback should be rejected")
        } catch (error:DomainException) {
            assertEquals(ErrorCode.SESSION_EXPIRED,error.code)
        }
        manager.exitDemo()
    }

    @Test fun restore_requires_a_fresh_challenge_and_rotates_the_session_after_one_commit()=runBlocking {
        val graph=createDatabaseGraph(context,database,clock,currentBuildInfo())
        val manager=AppSessionManager(context,graph,database,clock)
        val original=manager.session.value
        original.graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,
            "portable source","",emptyList()))
        val output=ByteArrayOutputStream()
        original.graph.portability.createBackup(UUID.randomUUID().toString(),output)
        original.graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,
            "must be replaced","",emptyList()))
        val preview=original.graph.portability.prepareRestore(
            ByteArrayInputStream(output.toByteArray()),"candidate.val_backup")
        var challenge=original.graph.portability.issueRestoreChallenge(preview.candidateId)
        try {
            original.graph.portability.commitRestore(preview.candidateId,challenge.value,"WRONG!")
            throw AssertionError("Wrong confirmation should fail")
        } catch (error:PortabilityException) {
            assertEquals(PortabilityErrorCode.CONFIRMATION_MISMATCH,error.errorCode)
        }
        challenge=original.graph.portability.issueRestoreChallenge(preview.candidateId)
        original.graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,
            "changed after challenge","",emptyList()))
        try {
            original.graph.portability.commitRestore(preview.candidateId,challenge.value,challenge.value)
            throw AssertionError("A changed dataset should invalidate the confirmation")
        } catch (error:PortabilityException) {
            assertEquals(PortabilityErrorCode.STALE_PREVIEW,error.errorCode)
        }
        challenge=original.graph.portability.issueRestoreChallenge(preview.candidateId)
        original.graph.portability.commitRestore(preview.candidateId,challenge.value,challenge.value)
        assertTrue(manager.session.value.id!=original.id)
        assertEquals(listOf("portable source"),
            dev.valnook.data.repository.RoomOverview(database).snapshot().accounts.map { it.name })
        expectExpired { original.graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,
            "stale page","",emptyList())) }
    }

    @Test fun web_lease_is_the_only_writer_and_release_rotates_back_to_mobile()=runBlocking {
        val manager=AppSessionManager(context,createDatabaseGraph(context,database,clock,currentBuildInfo()),database,clock)
        val mobileBefore=manager.session.value
        manager.reserveWebAdminServer("server-a")
        val lease=manager.acquireWebWriteLease("web-a")
        assertTrue(manager.session.value.id!=mobileBefore.id)

        try {
            manager.session.value.graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,
                "blocked mobile","",emptyList()))
            throw AssertionError("Mobile write must be blocked while Web owns the lease")
        } catch(error:DomainException) {
            assertEquals(ErrorCode.WEB_ADMIN_ACTIVE,error.code)
        }
        try {
            manager.acquireWebWriteLease("web-b")
            throw AssertionError("A second Web writer must be rejected")
        } catch(error:WebAdminException) {
            assertEquals(WebAdminError.SESSION_BUSY,error.error)
        }
        try {
            manager.enterDemo()
            throw AssertionError("Demo switch must be blocked while Web owns the lease")
        } catch(error:DomainException) {
            assertEquals(ErrorCode.WEB_ADMIN_ACTIVE,error.code)
        }
        try {
            manager.issueClearChallenge()
            throw AssertionError("Clear must be blocked while Web owns the lease")
        } catch(error:DomainException) {
            assertEquals(ErrorCode.WEB_ADMIN_ACTIVE,error.code)
        }

        val operation=UUID.randomUUID().toString()
        val receipt=manager.executeWebCommand("web-a",lease.dataGeneration,
            SaveAccount(operation,null,null,"web account","",emptyList()))
        assertEquals("ACCOUNT",receipt.result.kind)
        assertEquals("WEB_ADMIN",database.audit().eventForOperation(operation)?.source)
        try {
            manager.executeWebCommand("web-a",lease.dataGeneration,
                SaveAccount(UUID.randomUUID().toString(),null,null,"stale web","",emptyList()))
            throw AssertionError("Stale Web generation must be rejected")
        } catch(error:DomainException) {
            assertEquals(ErrorCode.STALE_RECORD,error.code)
        }
        try {
            manager.executeWebCommand("web-b",receipt.dataGeneration,
                SaveAccount(UUID.randomUUID().toString(),null,null,"wrong owner","",emptyList()))
            throw AssertionError("Wrong Web owner must be rejected")
        } catch(error:DomainException) {
            assertEquals(ErrorCode.SESSION_EXPIRED,error.code)
        }

        val lockedSession=manager.session.value.id
        manager.releaseWebWriteLease("web-a")
        assertTrue(manager.session.value.id!=lockedSession)
        try {
            manager.executeWebCommand("web-a",receipt.dataGeneration,
                SaveAccount(UUID.randomUUID().toString(),null,null,"late web","",emptyList()))
            throw AssertionError("Released Web session must stay revoked")
        } catch(error:DomainException) {
            assertEquals(ErrorCode.SESSION_EXPIRED,error.code)
        }
        val mobileResult=manager.session.value.graph.commands.execute(SaveAccount(
            UUID.randomUUID().toString(),null,null,"mobile resumed","",emptyList()))
        assertEquals("ACCOUNT",mobileResult.kind)
        manager.releaseWebAdminServer("server-a")
    }

    @Test fun demo_web_admin_writes_only_the_disposable_demo_database()=runBlocking {
        val manager=AppSessionManager(context,createDatabaseGraph(context,database,clock,currentBuildInfo()),database,clock)
        val realNamesBefore=dev.valnook.data.repository.RoomOverview(database).snapshot().accounts.map { it.name }
        manager.enterDemo()
        manager.reserveWebAdminServer("demo-server")

        try {
            manager.exitDemo()
            throw AssertionError("Demo exit must be blocked while the Web server is waiting")
        } catch(error:DomainException) {
            assertEquals(ErrorCode.WEB_ADMIN_ACTIVE,error.code)
        }

        val demoBefore=manager.webAdminReads().snapshot().accounts.map { it.name }
        val lease=manager.acquireWebWriteLease("demo-web")
        val operation=UUID.randomUUID().toString()
        val receipt=manager.executeWebCommand("demo-web",lease.dataGeneration,
            SaveAccount(operation,null,null,"Web demo only","",emptyList()))
        assertEquals("ACCOUNT",receipt.result.kind)
        val demoAfter=manager.webAdminReads().snapshot().accounts.map { it.name }
        assertTrue("Web demo only" in demoAfter)
        assertEquals(demoBefore.size+1,demoAfter.size)
        assertEquals(realNamesBefore,
            dev.valnook.data.repository.RoomOverview(database).snapshot().accounts.map { it.name })

        manager.releaseWebWriteLease("demo-web")
        assertEquals(DataMode.DEMO,manager.session.value.mode)
        manager.releaseWebAdminServer("demo-server")
        manager.exitDemo()
        assertEquals(DataMode.REAL,manager.session.value.mode)
        assertEquals(realNamesBefore,
            dev.valnook.data.repository.RoomOverview(database).snapshot().accounts.map { it.name })
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
