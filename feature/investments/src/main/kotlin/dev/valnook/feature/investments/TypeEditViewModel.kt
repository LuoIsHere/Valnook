package dev.valnook.feature.investments

import androidx.lifecycle.*
import dev.valnook.domain.command.*
import dev.valnook.domain.repository.*
import java.util.UUID
import kotlinx.coroutines.flow.*

/** [instrumentCount]: how many instruments the class holds, said before deleting it. */
class TypeEditViewModel(private val typeId: Long?, initialName: String, commands: FinancialCommands,
    private val saved: SavedStateHandle, instrumentCount: Flow<Int> = flowOf(0)) : ViewModel() {
    private val operationId = saved.get<String>("operationId") ?: UUID.randomUUID().toString().also { saved["operationId"] = it }
    val name = saved.getStateFlow("name", initialName)
    private val session = SubmissionSession(commands, viewModelScope,
        saved.get<String>("submission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) { saved["submission"] = it.name }
    val submission = session.state
    fun updateName(value: String) { if (submission.value.editable) saved["name"] = value }
    fun submit() = session.submit { SaveAssetType(operationId, typeId, name.value) }

    val canDelete get() = typeId != null
    val instrumentCount = instrumentCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    private val deleteOperationId = saved.get<String>("deleteOperationId") ?: UUID.randomUUID().toString().also { saved["deleteOperationId"] = it }
    private val deleteSession = SubmissionSession(commands, viewModelScope,
        saved.get<String>("deleteSubmission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) { saved["deleteSubmission"] = it.name }
    val deletion = deleteSession.state
    fun delete() { typeId?.let { id -> deleteSession.submit { DeleteAssetType(deleteOperationId, id) } } }
    fun consumeSuccess(): Boolean {
        if ((submission.value.phase != SubmissionPhase.SUCCEEDED && deletion.value.phase != SubmissionPhase.SUCCEEDED) ||
            saved.get<Boolean>("consumed") == true) return false
        saved["consumed"] = true
        return true
    }
}
