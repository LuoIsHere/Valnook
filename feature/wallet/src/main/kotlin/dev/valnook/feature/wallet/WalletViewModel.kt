package dev.valnook.feature.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*

data class WalletLedger(val month: YearMonth, val rows: List<CashEntry> = emptyList(),
    val loading: Boolean = true, val more: Boolean = false, val appending: Boolean = false,
    val failed: Boolean = false, val appendFailed: Boolean = false, val generation: Long = 0)
data class WalletDraft(val id: Long?, val revision: Long?, val name: String, val boundId: Long?,
    val imageKey: String?, val image: WalletImage? = null)

class WalletViewModel(val sessionId: String, val repository: WalletRepository,
    private val overview: OverviewRepository, private val pages: PagedCashRepository,
    private val clock: Clock, private val saved: SavedStateHandle,
    preparedCards:StateFlow<List<WalletCard>?>?=null) : ViewModel() {
    val visit = saved.getStateFlow("wallet-visit",0L)
    val today = MutableStateFlow(LocalDate.now(clock.withZone(ZoneId.systemDefault())))
    val selected = saved.getStateFlow<Long?>("wallet-selected",null)
    private val mutableCards=MutableStateFlow<List<WalletCard>?>(preparedCards?.value)
    val cards=mutableCards.asStateFlow()
    val failed=MutableStateFlow(false)
    val snapshot=MutableStateFlow<AssetSnapshot?>(null)
    val draft=MutableStateFlow<WalletDraft?>(null)
    val busy=MutableStateFlow(false)
    val operationFailed=MutableStateFlow(false)
    val bounds=MutableStateFlow(LedgerBounds(null,null))
    val ledger=MutableStateFlow(WalletLedger(YearMonth.parse(saved["wallet-month"] ?: YearMonth.now(clock).toString())))
    var zone: ZoneId = ZoneId.systemDefault(); private set
    private var boundId: Long?=null
    private var boundCard: Long?=null
    private var generation=0L
    private var loadJob: Job?=null
    private var boundsJob: Job?=null
    private var revisionJob: Job?=null
    private var observedRevision: Long?=null
    val currentCard get()=cards.value?.firstOrNull { it.id==selected.value }
    val currentAccount get()=snapshot.value?.cash?.firstOrNull { it.id==currentCard?.boundCashAccountId }

    init {
        viewModelScope.launch {
            (preparedCards?.filterNotNull()?:repository.observeCards()).retryWhen { _, _ -> failed.value=true; delay(1500); true }.collect {
                mutableCards.value=it; failed.value=false
                if(selected.value!=null && it.none { card->card.id==selected.value }) select(null)
                updateBinding()
            }
        }
        viewModelScope.launch {
            overview.observeSnapshot().retryWhen { _, _ -> failed.value=true; delay(1500); true }.collect { snapshot.value=it; updateBinding() }
        }
        viewModelScope.launch { selected.collect { updateBinding() } }
    }

    fun select(id: Long?) { if(id!=selected.value && id!=null)saved["wallet-visit"]=visit.value+1; saved["wallet-selected"]=id; operationFailed.value=false }
    private fun updateBinding() {
        if(cards.value==null)return
        val id=currentCard?.boundCashAccountId
        if(boundCard==selected.value && boundId==id)return
        val restoring=boundCard==null && selected.value!=null && saved.get<Long>("wallet-bound")==id
        boundCard=selected.value;boundId=id; saved["wallet-bound"]=id
        loadJob?.cancel();boundsJob?.cancel();revisionJob?.cancel();generation++
        bounds.value=LedgerBounds(null,null);observedRevision=null
        val month=if(restoring) ledger.value.month else YearMonth.now(clock.withZone(zone))
        saved["wallet-month"]=month.toString();ledger.value=WalletLedger(month,generation=generation)
        if(id==null){ledger.value=ledger.value.copy(loading=false);return}
        boundsJob=viewModelScope.launch { pages.observeMonthBounds(id).collect { bounds.value=it } }
        revisionJob=viewModelScope.launch {
            pages.observeCashAccountRevision(id).collect { revision ->
                if(revision!=observedRevision){observedRevision=revision;load(false,keepRows=ledger.value.rows.isNotEmpty())}
            }
        }
    }
    fun month(value: YearMonth) {
        if(value==ledger.value.month)return
        saved["wallet-month"]=value.toString()
        ledger.value=WalletLedger(value,generation=++generation)
        load(false)
    }
    fun refreshTime() {
        val current=ZoneId.systemDefault()
        if(current!=zone){zone=current;load(false)}
        today.value=LocalDate.now(clock.withZone(zone))
    }
    fun retry()=load(false)
    fun more() { if(ledger.value.more && !ledger.value.loading && !ledger.value.appending)load(true) }
    private fun load(append: Boolean, keepRows: Boolean=false) {
        val id=boundId?:return
        val previous=ledger.value
        loadJob?.cancel()
        val ticket=++generation
        val range=LedgerMonth(previous.month,zone)
        ledger.value=previous.copy(rows=if(append || keepRows) previous.rows else emptyList(),
            loading=!append,appending=append,failed=false,appendFailed=false,generation=ticket)
        loadJob=viewModelScope.launch {
            try {
                val last=if(append)previous.rows.lastOrNull() else null
                val loaded=pages.cashAccountMonthPage(id,range,last?.let{LedgerCursor(it.occurred_at_ms,it.id)},51)
                if(ticket!=generation || boundId!=id || range.zone!=zone)return@launch
                val rows=if(append)previous.rows+loaded.take(50) else loaded.take(50)
                ledger.value=WalletLedger(range.month,rows.distinctBy{it.id},false,loaded.size>50,generation=ticket)
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(ticket==generation)ledger.value=ledger.value.copy(loading=false,appending=false,failed=!append,appendFailed=append)}
        }
    }
    fun edit(card: WalletCard?=null) {
        operationFailed.value=false
        draft.value=WalletDraft(card?.id,card?.revision,card?.name.orEmpty(),card?.boundCashAccountId,card?.imageKey)
    }
    fun save() {
        val value=draft.value?:return
        mutate {
            val id=repository.save(value.id,value.revision,value.name,value.boundId,value.imageKey,value.image)
            mutableCards.value=repository.observeCards().first()
            draft.value=null;select(id)
        }
    }
    fun delete(card: WalletCard)=mutate { repository.delete(card.id,card.revision);select(null) }
    fun reorder(expected: List<Long>, ids: List<Long>)=mutate { repository.reorder(expected,ids); mutableCards.value=repository.observeCards().first() }
    private fun mutate(action:suspend ()->Unit) {
        if(busy.value)return
        busy.value=true;operationFailed.value=false
        viewModelScope.launch { try{action()}catch(c:CancellationException){throw c}
            catch(_:Exception){operationFailed.value=true}finally{busy.value=false} }
    }
}
