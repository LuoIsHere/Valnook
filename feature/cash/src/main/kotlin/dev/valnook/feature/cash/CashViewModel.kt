package dev.valnook.feature.cash
import androidx.lifecycle.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.designsystem.DraftState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.time.*

class CashViewModel(private val account_id:Long,private val repository:CashRepository,
    private val commands:FinancialCommands,private val saved:SavedStateHandle,private val clock:Clock=Clock.systemDefaultZone()):ViewModel() {
    val balances=repository.observe_cash(account_id).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val selected=saved.getStateFlow("selected_currency","")
    private val history_limit=MutableStateFlow(50)
    @OptIn(ExperimentalCoroutinesApi::class)
    val entries=combine(selected,history_limit){code,limit->code to limit}.flatMapLatest{(code,limit)->
        if(code.isEmpty())flowOf(emptyList()) else repository.observe_entries(account_id,code,limit)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    fun watch_currency(code:String){saved["selected_currency"]=Currency.of(code).code;history_limit.value=50}
    fun load_more_entries(){history_limit.value+=50}
    val editing_entry:Boolean get()=saved.get<String>("mode")=="ENTRY"
    val used_currencies:Set<String> get()=balances.value.map{it.currency.code}.toSet()

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
            } catch(e:DateTimeException) {
                _draft.value=_draft.value.copy(busy=false,error="DATE")
            } catch(e:IllegalArgumentException) {
                _draft.value=_draft.value.copy(busy=false,error="FORMAT")
            } catch(e:Exception) {
                saved["locked"]=true
                _draft.value=_draft.value.copy(busy=false,error="STORAGE",locked=true)
            }
        }
    }

    fun begin(balance:CashBalance?) {
        saved["mode"]="SET"
        saved["revision"]=balance?.revision
        saved["existing"]=balance!=null
        val selected_code=balance?.currency?.code ?: Currency.supported.firstOrNull{it.code !in used_currencies}?.code.orEmpty()
        start(mapOf("currency" to selected_code,"before_minor" to (balance?.balance_minor ?: 0).toString(),
            "amount" to (balance?.let{R.format_units(it.balance_minor,it.currency.fraction_digits)} ?: "")))
    }
    fun begin_entry(entry:CashEntry) {
        if(!entry.editable)return
        saved["mode"]="ENTRY";saved["target_id"]=entry.id;saved["revision"]=entry.revision;saved["existing"]=true
        val time=Instant.ofEpochMilli(entry.occurred_at_ms).atZone(clock.zone)
        start(mapOf("currency" to entry.currency.code,
            "amount" to R.format_units(if(entry.delta_minor<0)-entry.delta_minor else entry.delta_minor,entry.currency.fraction_digits),
            "direction" to if(entry.delta_minor<0)"DECREASE" else "INCREASE",
            "original_delta_minor" to entry.delta_minor.toString(),"day" to time.toLocalDate().toString(),
            "time" to time.toLocalTime().toString().take(5),"note" to entry.note))
    }
    fun change_preview():String?=runCatching {
        val currency=Currency.of(value("currency"))
        val amount=R.parse_minor(value("amount"),currency)
        val delta=if(editing_entry)R.replace_contribution(0,value("original_delta_minor").toLong(),
            if(value("direction")=="DECREASE")-amount else amount)
        else R.replace_contribution(0,value("before_minor").toLong(),amount)
        (if(delta>0)"+" else "")+R.format_display(delta,currency.fraction_digits)
    }.getOrNull()
    val existing:Boolean get()=saved["existing"] ?: false
    fun submit()=perform {
        val amount=R.parse_minor(value("amount"),Currency.of(value("currency")))
        if(editing_entry)commands.execute(EditCashEntry(operation_id(),requireNotNull(saved["target_id"]),
            requireNotNull(saved["revision"]),if(value("direction")=="DECREASE")-amount else amount,
            LocalDateTime.of(LocalDate.parse(value("day")),LocalTime.parse(value("time"))).atZone(clock.zone).toInstant().toEpochMilli(),
            value("note")))
        else commands.execute(SetCashBalance(operation_id(),account_id,value("currency"),amount,saved["revision"]))
    }
}
