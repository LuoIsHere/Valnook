package dev.valnook.feature.wallet

import androidx.compose.runtime.MonotonicFrameClock
import dev.valnook.domain.repository.WalletPrivateContent
import dev.valnook.domain.repository.WalletPrivateRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalletPrivateStateTest {
    @Test fun late_input_callbacks_cannot_replace_saving_drafts_or_reopen_saved_editor() = runTest {
        val state=state(Vault().apply { saveDelay=100 })
        state.flip();advanceUntilIdle();state.edit();advanceUntilIdle()
        state.confirmWarning(false);advanceUntilIdle()
        val original=state.draft!!
        state.save();state.updateDraft(original.copy(cvv1="1234"))
        assertEquals(original,state.draft)
        advanceUntilIdle();assertNull(state.draft)
        state.updateDraft(original);assertNull(state.draft)
    }
    @Test fun copy_feedback_expires_restarts_and_is_cleared_when_backgrounded() = runTest {
        val state=state(Vault())
        state.reportCopy(true);advanceTimeBy(1500);assertEquals(true,state.copyNotice)
        state.reportCopy(false);advanceTimeBy(1000);assertEquals(false,state.copyNotice)
        advanceUntilIdle();assertNull(state.copyNotice);assertFalse(state.lastCopySucceeded)
        state.reportCopy(true);state.conceal();assertNull(state.copyNotice)
    }
    private class Vault : WalletPrivateRepository {
        var value = WalletPrivateContent(number = "TEST-ONLY", revision = 1)
        var readDelay = 0L
        var dismissDelay = 0L
        var saveDelay = 0L
        var failSave = false
        override suspend fun read(cardId: Long): WalletPrivateContent {
            withContext(NonCancellable) { delay(readDelay) }
            return value
        }
        override suspend fun warningDismissed() = false
        override suspend fun dismissWarning() { withContext(NonCancellable) { delay(dismissDelay) } }
        override suspend fun save(cardId: Long, content: WalletPrivateContent): WalletPrivateContent {
            withContext(NonCancellable) { delay(saveDelay) }
            check(!failSave) { "Injected storage failure" }
            return content.copy(revision = content.revision + 1).also { value = it }
        }
    }

    private fun TestScope.state(vault: Vault): WalletPrivateState {
        val frames = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16)
                return onFrame(testScheduler.currentTime * 1_000_000)
            }
        }
        return WalletPrivateState(1, vault, CoroutineScope(coroutineContext + frames))
    }

    @Test fun rapid_flip_reverses_from_current_angle_and_finishes_on_last_requested_face() = runTest {
        val state = state(Vault())
        state.flip(); advanceTimeBy(240); runCurrent()
        val turningBack = state.angle.value
        assertTrue(turningBack > 0f && turningBack < 180f)
        state.flip(); runCurrent()
        assertEquals(turningBack, state.angle.value, .01f)
        advanceTimeBy(96); runCurrent()
        assertTrue(state.angle.value < turningBack)
        val turningFront = state.angle.value
        state.flip(); runCurrent()
        assertEquals(turningFront, state.angle.value, .01f)
        advanceUntilIdle()
        assertEquals(180f, state.angle.value, .01f); assertNotNull(state.content)
        state.flip(); advanceUntilIdle()
        assertEquals(0f, state.angle.value, .01f); assertNull(state.content)
    }

    @Test fun delayed_read_cannot_reveal_private_content_after_backgrounding() = runTest {
        val state = state(Vault().apply { readDelay = 300 })
        state.flip(); runCurrent(); assertTrue(state.loading)
        state.conceal(); advanceUntilIdle()
        assertNull(state.content); assertNull(state.draft); assertFalse(state.sensitive)
        assertFalse(state.loading); assertEquals(0f, state.angle.value, .01f)
    }

    @Test fun cancelling_warning_prevents_late_confirmation_from_opening_editor() = runTest {
        val state = state(Vault().apply { dismissDelay = 300 })
        state.flip(); advanceUntilIdle(); state.edit(); advanceUntilIdle()
        assertTrue(state.warning)
        state.confirmWarning(true); runCurrent()
        state.cancelWarning(); advanceUntilIdle()
        assertFalse(state.warning); assertNull(state.draft); assertNotNull(state.content)
    }

    @Test fun failed_save_preserves_draft_and_previous_content_and_can_be_retried() = runTest {
        val vault = Vault().apply { failSave = true }
        val state = state(vault)
        state.flip(); advanceUntilIdle(); state.edit(); advanceUntilIdle()
        state.confirmWarning(false); advanceUntilIdle()
        val draft = state.draft!!.copy(number = "NEW-TEST-ONLY")
        state.draft = draft; state.save(); advanceUntilIdle()
        assertTrue(state.error); assertFalse(state.saving); assertEquals(draft, state.draft)
        assertEquals("TEST-ONLY", state.content!!.number); assertEquals(state.content, vault.value)
        vault.failSave = false; state.save(); advanceUntilIdle()
        assertFalse(state.error); assertNull(state.draft); assertEquals(draft.number, state.content!!.number)
    }

    @Test fun completed_save_after_backgrounding_does_not_reopen_private_ui() = runTest {
        val vault = Vault().apply { saveDelay = 300 }
        val state = state(vault)
        state.flip(); advanceUntilIdle(); state.edit(); advanceUntilIdle()
        state.confirmWarning(false); advanceUntilIdle()
        state.draft = state.draft!!.copy(number = "NEW-TEST-ONLY")
        state.save(); runCurrent(); state.conceal(); advanceUntilIdle()
        assertNull(state.content); assertNull(state.draft); assertFalse(state.sensitive); assertFalse(state.saving)
        assertEquals("NEW-TEST-ONLY", vault.value.number)
    }

    @Test fun return_waits_until_front_animation_finishes_and_clears_plaintext() = runTest {
        val state = state(Vault())
        state.flip(); advanceUntilIdle()
        var returned = false
        // Use the state's frame clock through the same test helper scope for the return animation.
        val frames = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16); return onFrame(testScheduler.currentTime * 1_000_000)
            }
        }
        launch(frames) { state.front(); returned = true }
        advanceTimeBy(200); runCurrent()
        assertFalse(returned); assertTrue(state.angle.value > 0f)
        advanceUntilIdle()
        assertTrue(returned); assertEquals(0f, state.angle.value, .01f); assertNull(state.content)
    }
}
