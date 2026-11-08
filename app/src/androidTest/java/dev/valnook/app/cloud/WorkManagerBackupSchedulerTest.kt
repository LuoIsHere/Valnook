package dev.valnook.app.cloud

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asking for the cycle already waiting (as every start of the app does) keeps that work, so a run WorkManager has just
 * started is not cancelled; another cycle replaces it. The work is due in a day and its connection generation (-1)
 * matches no connection, so it never runs a backup.
 */
class WorkManagerBackupSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val work = WorkManager.getInstance(context)
    private val scheduler = WorkManagerBackupScheduler(context)
    private val now = System.currentTimeMillis()
    private val due = now + 24 * 3_600_000L

    @After fun cancel() = runBlocking { scheduler.cancel() }

    private suspend fun infos() = work.getWorkInfosForUniqueWorkFlow(WorkManagerBackupScheduler.WORK_NAME).first()
    private suspend fun waiting() = infos().filter { !it.state.isFinished }

    @Test fun the_same_cycle_keeps_its_work_and_another_cycle_replaces_it() = runBlocking {
        scheduler.replace(due, now, "cycle-a", 1, -1, 1)
        val first = waiting().single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        repeat(2) { scheduler.replace(due, now + 1_000, "cycle-a", 2 + it.toLong(), -1, 1) }
        assertEquals("Asked again for the same cycle: the same work", first.id, waiting().single().id)

        scheduler.replace(due + 3_600_000, now, "cycle-b", 3, -1, 2)
        val second = waiting().single()
        assertNotEquals(first.id, second.id)
        // Replaced work leaves the unique name; by its id it is cancelled, or already pruned.
        val replaced = work.getWorkInfoByIdFlow(first.id).first()
        assertTrue("${replaced?.state}", replaced == null || replaced.state == WorkInfo.State.CANCELLED)
    }

    @Test fun a_cycle_whose_work_is_gone_is_enqueued_again() = runBlocking {
        scheduler.replace(due, now, "cycle-a", 1, -1, 1)
        val first = waiting().single()
        work.cancelWorkById(first.id).result.get()
        scheduler.replace(due, now, "cycle-a", 1, -1, 1)
        val again = waiting().single()
        assertNotEquals(first.id, again.id)
    }
}
