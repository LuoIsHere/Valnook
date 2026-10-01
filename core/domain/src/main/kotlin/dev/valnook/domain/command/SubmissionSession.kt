package dev.valnook.domain.command

import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class SubmissionPhase { IDLE, WORKING, SUCCEEDED, INVALID, UNKNOWN }
data class SubmissionState(val phase: SubmissionPhase = SubmissionPhase.IDLE, val error: ErrorCode? = null,
    val result: OperationResult? = null) {
    val editable: Boolean get() = phase == SubmissionPhase.IDLE || phase == SubmissionPhase.INVALID
}

/** Freeze inputs before launch. Unknown outcomes reconcile by original ID before retrying. */
class SubmissionSession(private val commands: FinancialCommands, private val scope: CoroutineScope,
    initialPhase: SubmissionPhase = SubmissionPhase.IDLE, private val persist: (SubmissionPhase) -> Unit = {}) {
    private val mutable = MutableStateFlow(SubmissionState(if (initialPhase == SubmissionPhase.WORKING) SubmissionPhase.UNKNOWN else initialPhase))
    val state: StateFlow<SubmissionState> = mutable.asStateFlow()
    private fun change(value: SubmissionState) { mutable.value = value
        persist(value.phase) }
    fun submit(snapshot: () -> FinancialCommand) {
        if (state.value.phase == SubmissionPhase.WORKING || state.value.phase == SubmissionPhase.SUCCEEDED) return
        val request = try { snapshot() } catch (error: DomainException) {
            change(SubmissionState(SubmissionPhase.INVALID, error.code))
            return
        } catch (_: IllegalArgumentException) {
            change(SubmissionState(SubmissionPhase.INVALID, ErrorCode.FORMAT))
            return
        }
        val reconcile = state.value.phase == SubmissionPhase.UNKNOWN
        change(SubmissionState(SubmissionPhase.WORKING))
        scope.launch {
            try {
                val result = if (reconcile) commands.operationResult(request.operation_id) ?: commands.execute(request)
                    else commands.execute(request)
                change(SubmissionState(SubmissionPhase.SUCCEEDED, result = result))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: DomainException) { change(SubmissionState(SubmissionPhase.INVALID, error.code)) }
            catch (_: Exception) { change(SubmissionState(SubmissionPhase.UNKNOWN)) }
        }
    }
}
