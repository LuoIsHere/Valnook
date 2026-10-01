package dev.valnook.feature.investments
import androidx.lifecycle.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.designsystem.DraftState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.time.*

class InvestmentsViewModel(private val account_id:Long,private val repository:InvestmentRepository,
    private val commands:FinancialCommands,private val clock:Clock,private val saved:SavedStateHandle):ViewModel() {
    private val limit=MutableStateFlow(50)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val portfolios=InvestmentSection.entries.associateWith { section->
        limit.flatMapLatest{repository.observe_investments(account_id,it,section)}
            .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    }
    val investments=portfolios.getValue(InvestmentSection.HOLDING)
    fun assets(section:InvestmentSection)=portfolios.getValue(section)
    val types=repository.observe_types().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    fun load_more_assets() {limit.value+=50}
    private val detail_id=saved.getStateFlow("detail_id",0L)
    @OptIn(ExperimentalCoroutinesApi::class)
    val detail=detail_id.flatMapLatest{if(it==0L)flowOf(null) else repository.observe_investment(it)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val profit=detail_id.flatMapLatest{if(it==0L)flowOf(null) else repository.observe_profit(it)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    private val _trades=MutableStateFlow<List<Trade>>(emptyList()); val trades=_trades.asStateFlow()
    private val _history_error=MutableStateFlow<String?>(null); val history_error=_history_error.asStateFlow()
    private var history_job:Job?=null
    private var page_job:Job?=null
    fun watch_history(investment_id:Long) {
        saved["detail_id"]=investment_id
        if(saved.get<Long>("history_id")==investment_id&&history_job?.isActive==true)return
        saved["history_id"]=investment_id
        history_job?.cancel()
        history_job=viewModelScope.launch {
            repository.observe_trade_revision(investment_id).collect { refresh_history() }
        }
    }
    fun refresh_history() {
        page_job?.cancel()
        page_job=viewModelScope.launch {
            try { _trades.value=repository.trade_page(requireNotNull(saved["history_id"]),null);_history_error.value=null }
            catch(e:CancellationException){throw e}
            catch(e:Exception){_history_error.value="STORAGE"}
        }
    }
    fun next_page() {
        if(page_job?.isActive==true)return
        val last=_trades.value.lastOrNull()?:return
        page_job=viewModelScope.launch {
            try { _trades.value=repository.trade_page(requireNotNull(saved["history_id"]),TradeCursor(last.occurred_at_ms,last.id));_history_error.value=null }
            catch(e:CancellationException){throw e}
            catch(e:Exception){_history_error.value="STORAGE"}
        }
    }

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

    val mode:String get()=saved["mode"] ?: "CREATE"
    fun begin(mode:String,asset:Investment?=null,type:AssetType?=null,trade:Trade?=null) {
        saved["mode"]=mode; saved["target_id"]=trade?.id ?: asset?.id ?: type?.id
        saved["revision"]=trade?.revision ?: asset?.revision
        val time=trade?.let{Instant.ofEpochMilli(it.occurred_at_ms).atZone(clock.zone).toLocalDateTime()} ?: LocalDateTime.now(clock)
        start(mapOf("name" to (asset?.name ?: type?.name ?: ""),"symbol" to (asset?.symbol ?: ""),
            "type" to (asset?.type_id?.toString() ?: types.value.firstOrNull()?.id?.toString() ?: ""),
            "currency" to (trade?.currency?.code ?: asset?.currency?.code ?: "CNY"),
            "quantity" to (trade?.let{R.format_e8(it.quantity_e8)} ?: "0"),
            "opening_cost" to (asset?.opening_cost_price_e8?.let{R.format_e8(it)} ?: ""),
            "price" to (trade?.let{R.format_e8(it.execution_price_e8)} ?: asset?.let{R.format_e8(it.current_price_e8)} ?: "0"),
            "direction" to (trade?.direction?.name ?: mode),
            "original_cash_minor" to (trade?.takeIf{it.cash_linked}?.let{if(it.direction==Direction.BUY)-it.amount_minor else it.amount_minor} ?: 0).toString(),
            "day" to time.toLocalDate().toString(),"time" to time.toLocalTime().toString().take(5),"linked" to (trade?.cash_linked ?: false).toString()))
    }
    suspend fun prepare_trade_source(id:Long) {
        if(mode=="EDIT_TRADE"&&saved.get<Long>("target_id")==id&&fields().isNotEmpty()&&!_draft.value.completed)return
        saved["mode"]="EDIT_TRADE";saved["target_id"]=id
        _draft.value=DraftState(busy=true)
        try {
            val trade=repository.get_trade(id) ?: throw DomainException(ErrorCode.NOT_FOUND)
            val asset=repository.observe_investment(trade.investment_id).first() ?: throw DomainException(ErrorCode.NOT_FOUND)
            if(asset.account_id!=account_id)throw DomainException(ErrorCode.NOT_FOUND)
            begin("EDIT_TRADE",asset,trade=trade)
        } catch(e:CancellationException){throw e}
          catch(e:DomainException){_draft.value=DraftState(error=e.code.name)}
          catch(e:Exception){_draft.value=DraftState(error="STORAGE")}
    }
    fun amount_preview():String? = runCatching {
        val currency=Currency.of(value("currency"))
        R.format_units(R.amount(R.parse_e8(value("quantity"),true),R.parse_e8(value("price"),true),currency,true),currency.fraction_digits)
    }.getOrNull()
    fun cash_change_preview():String?=runCatching {
        val currency=Currency.of(value("currency"))
        val replacement=if(mode=="DELETE_TRADE"||!value("linked").toBoolean())0L else {
            val amount=R.amount(R.parse_e8(value("quantity"),true),R.parse_e8(value("price"),true),currency,true)
            if(value("direction")=="BUY")-amount else amount
        }
        val delta=R.replace_contribution(0,value("original_cash_minor").ifEmpty{"0"}.toLong(),replacement)
        (if(delta>0)"+" else "")+R.format_display(delta,currency.fraction_digits)
    }.getOrNull()
    fun submit()=perform {
        val target=saved.get<Long>("target_id")
        when(mode) {
            "TYPE" -> repository.save_type(target,value("name"))
            "EDIT" -> repository.edit_investment(requireNotNull(target),value("name"),value("symbol"),value("type").toLong())
            "PRICE" -> repository.update_price(requireNotNull(target),R.parse_e8(value("price")))
            "COST" -> commands.execute(SetOpeningInvestmentCost(operation_id(),requireNotNull(target),
                requireNotNull(saved["revision"]),R.parse_e8(value("opening_cost"),true)))
            "DELETE_TRADE" -> commands.execute(DeleteInvestmentTrade(operation_id(),requireNotNull(target),requireNotNull(saved["revision"])))
            "EDIT_TRADE" -> commands.execute(EditInvestmentTrade(operation_id(),requireNotNull(target),requireNotNull(saved["revision"]),
                Direction.valueOf(value("direction")),R.parse_e8(value("quantity"),true),R.parse_e8(value("price"),true),
                LocalDateTime.of(LocalDate.parse(value("day")),LocalTime.parse(value("time"))).atZone(clock.zone).toInstant().toEpochMilli(),value("linked").toBoolean()))
            "BUY","SELL" -> commands.execute(RecordInvestmentTrade(operation_id(),requireNotNull(target),Direction.valueOf(mode),
                R.parse_e8(value("quantity"),true),R.parse_e8(value("price"),true),
                LocalDateTime.of(LocalDate.parse(value("day")),LocalTime.parse(value("time"))).atZone(clock.zone).toInstant().toEpochMilli(),value("linked").toBoolean()))
            else -> commands.execute(CreateInvestment(operation_id(),account_id,value("name"),value("symbol"),value("type").toLong(),
                value("currency"),R.parse_e8(value("quantity")),R.parse_e8(value("price")),
                if(R.parse_e8(value("quantity"))>0)R.parse_e8(value("opening_cost"),true) else null))
        }
    }
}
