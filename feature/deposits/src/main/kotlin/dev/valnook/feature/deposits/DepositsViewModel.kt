package dev.valnook.feature.deposits
import androidx.lifecycle.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.designsystem.DraftState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.time.*

class DepositsViewModel(private val account_id:Long,private val repository:DepositRepository,
    private val commands:FinancialCommands,private val clock:Clock,private val saved:SavedStateHandle):ViewModel() {
    private val limit=MutableStateFlow(50)
    @OptIn(ExperimentalCoroutinesApi::class)
    val deposits=limit.flatMapLatest{repository.observe_deposits(account_id,it)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    @OptIn(ExperimentalCoroutinesApi::class)
    val settled=limit.flatMapLatest{repository.observe_deposits(account_id,it,true)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    fun load_more() { limit.value+=50 }
    private val _today=MutableStateFlow(LocalDate.now(clock).toEpochDay()); val today=_today.asStateFlow()
    fun refresh_today() { _today.value=LocalDate.now(clock).toEpochDay() }

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

    fun begin(deposit:TermDeposit?) {
        saved["editing"]=false
        saved["target_id"]=deposit?.id
        val date=LocalDate.now(clock)
        start(mapOf("currency" to (deposit?.currency?.code ?: "CNY"),"principal" to (deposit?.let{R.format_units(it.principal_minor,it.currency.fraction_digits)} ?: ""),
            "rate" to "","start" to date.toString(),"end" to date.plusMonths(3).toString(),"linked" to "false",
            "return" to (deposit?.let{R.format_units(R.add(it.principal_minor,it.expected_interest_minor),it.currency.fraction_digits)} ?: "")))
    }
    val editing:Boolean get()=saved["editing"] ?: false
    val closing:Boolean get()=saved.get<Long>("target_id")!=null&&!editing
    val was_closed:Boolean get()=saved["was_closed"] ?: false
    fun begin_edit(deposit:TermDeposit) {
        saved["editing"]=true;saved["target_id"]=deposit.id;saved["revision"]=deposit.revision;saved["was_closed"]=deposit.closed
        val old_effect=R.add(if(deposit.open_cash_linked)-deposit.principal_minor else 0,
            if(deposit.close_cash_linked==true)R.add(deposit.principal_minor,deposit.expected_interest_minor) else 0)
        start(mapOf("currency" to deposit.currency.code,"principal" to R.format_units(deposit.principal_minor,deposit.currency.fraction_digits),
            "rate" to R.format_e8(deposit.annual_rate_percent_e8),"start" to LocalDate.ofEpochDay(deposit.start_epoch_day).toString(),
            "end" to LocalDate.ofEpochDay(deposit.end_epoch_day).toString(),"linked" to deposit.open_cash_linked.toString(),
            "close_linked" to (deposit.close_cash_linked ?: false).toString(),"original_effect" to old_effect.toString()))
    }
    suspend fun prepare_source(id:Long) {
        if(editing&&saved.get<Long>("target_id")==id&&fields().isNotEmpty()&&!_draft.value.completed)return
        saved["editing"]=true;saved["target_id"]=id
        _draft.value=DraftState(busy=true)
        try {
            val deposit=repository.get_deposit(id) ?: throw DomainException(ErrorCode.NOT_FOUND)
            if(deposit.account_id!=account_id)throw DomainException(ErrorCode.NOT_FOUND)
            begin_edit(deposit)
        } catch(e:CancellationException){throw e}
          catch(e:DomainException){_draft.value=DraftState(error=e.code.name)}
          catch(e:Exception){_draft.value=DraftState(error="STORAGE")}
    }
    fun return_preview():String?=runCatching {
        val currency=Currency.of(value("currency"))
        R.format_units(R.add(R.parse_minor(value("principal"),currency,true),R.parse_minor(requireNotNull(preview()),currency)),currency.fraction_digits)
    }.getOrNull()
    fun cash_change_preview():String?=runCatching {
        val currency=Currency.of(value("currency"));val principal=R.parse_minor(value("principal"),currency,true)
        val replacement=R.add(if(value("linked").toBoolean())-principal else 0,
            if(was_closed&&value("close_linked").toBoolean())R.parse_minor(requireNotNull(return_preview()),currency) else 0)
        val delta=R.replace_contribution(0,value("original_effect").toLong(),replacement)
        (if(delta>0)"+" else "")+R.format_display(delta,currency.fraction_digits)
    }.getOrNull()
    fun preview():String? = runCatching {
        val currency=Currency.of(value("currency"))
        R.format_units(R.interest(R.parse_minor(value("principal"),currency,true),R.parse_e8(value("rate")),
            LocalDate.parse(value("start")).toEpochDay(),LocalDate.parse(value("end")).toEpochDay()),currency.fraction_digits)
    }.getOrNull()
    fun submit()=perform {
        val target=saved.get<Long>("target_id")
        if(editing)commands.execute(EditTermDeposit(operation_id(),requireNotNull(target),requireNotNull(saved["revision"]),
            R.parse_minor(value("principal"),Currency.of(value("currency")),true),R.parse_e8(value("rate")),
            LocalDate.parse(value("start")).toEpochDay(),LocalDate.parse(value("end")).toEpochDay(),value("linked").toBoolean(),
            if(was_closed)value("close_linked").toBoolean() else null))
        else if(target!=null) commands.execute(CloseTermDeposit(operation_id(),target,value("linked").toBoolean()))
        else commands.execute(OpenTermDeposit(operation_id(),account_id,value("currency"),
            R.parse_minor(value("principal"),Currency.of(value("currency")),true),R.parse_e8(value("rate")),
            LocalDate.parse(value("start")).toEpochDay(),LocalDate.parse(value("end")).toEpochDay(),value("linked").toBoolean()))
    }
}
