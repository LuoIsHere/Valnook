package dev.valnook.feature.investments

import androidx.lifecycle.*
import dev.valnook.domain.command.*
import dev.valnook.domain.repository.*
import java.util.UUID

class TypeEditViewModel(private val typeId: Long?, initialName: String, commands: FinancialCommands,
    private val saved: SavedStateHandle) : ViewModel() {
    private val operationId = saved.get<String>("operationId") ?: UUID.randomUUID().toString().also { saved["operationId"] = it }
    val name = saved.getStateFlow("name", initialName)
    private val session = SubmissionSession(commands, viewModelScope,
        saved.get<String>("submission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) { saved["submission"] = it.name }
    val submission = session.state
    fun updateName(value: String) { if (submission.value.editable) saved["name"] = value }
    fun submit() = session.submit { SaveAssetType(operationId, typeId, name.value) }
    fun consumeSuccess(): Boolean {
        if (submission.value.phase != SubmissionPhase.SUCCEEDED || saved.get<Boolean>("consumed") == true) return false
        saved["consumed"] = true
        return true
    }
}
