package dev.valnook.feature.accounts
import androidx.lifecycle.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.designsystem.DraftState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.time.*

class AccountsViewModel(private val repository:AccountRepository,private val saved:SavedStateHandle):ViewModel() {
    val accounts=repository.observe_accounts().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())

    private fun fields() = saved.get<HashMap<String,String>>("fields")?.toMap() ?: emptyMap()
    private val _draft=MutableStateFlow(DraftState(fields(),completed=saved["completed"] ?: false,locked=saved["locked"] ?: false))
    val draft=_draft.asStateFlow()
    fun consume_completion():Boolean {
        if(!_draft.value.completed||saved.get<Boolean>("completion_consumed")==true)return false
        saved["completion_consumed"]=true
        return true
    }
    fun field(key:String,value:String) {
        if(_draft.value.busy||_draft.value.locked||_draft.value.completed) return
        saved["fields"]=HashMap(_draft.value.fields + (key to value))
        _draft.value=_draft.value.copy(fields=fields(),error=null)
    }
    private fun start(values:Map<String,String>) {
        saved["operation_id"]=UUID.randomUUID().toString()
        saved["fields"]=HashMap(values); saved["completed"]=false; saved["locked"]=false
        saved["completion_consumed"]=false
        _draft.value=DraftState(values)
    }
    private fun value(key:String)=_draft.value.fields[key].orEmpty()
    private fun operation_id():String = saved.get<String>("operation_id") ?: UUID.randomUUID().toString().also { saved["operation_id"]=it }
    private fun perform(action:suspend ()->Unit) {
        if(_draft.value.busy||_draft.value.completed) return
        _draft.value=_draft.value.copy(busy=true,error=null)
        viewModelScope.launch {
            try {
                action()
                saved["completed"]=true; saved["locked"]=false
                _draft.value=_draft.value.copy(busy=false,completed=true,locked=false)
            } catch(e:CancellationException) { throw e }
              catch(e:DomainException) {
                saved["locked"]=false
                _draft.value=_draft.value.copy(busy=false,error=e.code.name,locked=false)
            } catch(e:IllegalArgumentException) {
                _draft.value=_draft.value.copy(busy=false,error="FORMAT")
            } catch(e:Exception) {
                saved["locked"]=true
                _draft.value=_draft.value.copy(busy=false,error="STORAGE",locked=true)
            }
        }
    }

    fun begin(account:SavingsAccount?) {
        saved["target_id"]=account?.id
        start(mapOf("name" to (account?.name ?: ""),"note" to (account?.note ?: "")))
    }
    fun submit()=perform { repository.save_account(saved["target_id"],value("name"),value("note")) }
}
