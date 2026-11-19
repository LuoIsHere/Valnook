package dev.valnook.feature.cash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.repository.CashRepository
import dev.valnook.domain.repository.DeleteCashEntry
import dev.valnook.domain.repository.FinancialCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface CashEntryState {
    data object Loading:CashEntryState
    data object Missing:CashEntryState
    data object Failed:CashEntryState
    data class Ready(val entry:CashEntry):CashEntryState
}

/** A delete on the way, done (the page leaves), or refused with [error]. */
data class CashEntryDeletion(val deleting:Boolean=false,val deleted:Boolean=false,val error:ErrorCode?=null)

class CashEntryViewModel(cashAccountId:Long,entryId:Long,repository:CashRepository,
    private val commands:FinancialCommands?=null):ViewModel() {
    private val mutableDeletion=MutableStateFlow(CashEntryDeletion())
    val deletion=mutableDeletion.asStateFlow()
    private val attempts=MutableStateFlow(0)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state=attempts.flatMapLatest {
        repository.observeCashEntry(cashAccountId,entryId)
            .map<CashEntry?,CashEntryState>{entry->if(entry==null)CashEntryState.Missing else CashEntryState.Ready(entry)}
            .onStart {emit(CashEntryState.Loading)}
            .catch {emit(CashEntryState.Failed)}
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),CashEntryState.Loading)
    fun retry(){attempts.value++}

    /** Only a hand-made balance change; the balance moves back by its amount. */
    fun delete(entry:CashEntry) {
        val commands=commands?:return
        if(!entry.editable||mutableDeletion.value.deleting||mutableDeletion.value.deleted) return
        mutableDeletion.value=CashEntryDeletion(deleting=true)
        viewModelScope.launch {
            mutableDeletion.value=try {
                commands.execute(DeleteCashEntry(UUID.randomUUID().toString(),entry.id,entry.revision))
                CashEntryDeletion(deleted=true)
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:DomainException){CashEntryDeletion(error=error.code)}
            catch(_:Exception){CashEntryDeletion(error=ErrorCode.FORMAT)}
        }
    }
}
