package dev.valnook.feature.investments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.Investment
import dev.valnook.domain.model.Trade
import dev.valnook.domain.repository.InvestmentRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

sealed interface TradeDetailState {
    data object Loading:TradeDetailState
    data object Missing:TradeDetailState
    data object Failed:TradeDetailState
    data class Ready(val asset:Investment,val trade:Trade):TradeDetailState
}

class TradeDetailViewModel(account_id:Long,trade_id:Long,repository:InvestmentRepository):ViewModel() {
    private val attempts=MutableStateFlow(0)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state=attempts.flatMapLatest {
        repository.observe_trade(account_id,trade_id).flatMapLatest {trade->
            if(trade==null)flowOf<TradeDetailState>(TradeDetailState.Missing)
            else repository.observe_investment(trade.investment_id).map {asset->
                if(asset==null||asset.account_id!=account_id)TradeDetailState.Missing else TradeDetailState.Ready(asset,trade)
            }
        }.onStart{emit(TradeDetailState.Loading)}.catch{emit(TradeDetailState.Failed)}
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),TradeDetailState.Loading)
    fun retry(){attempts.value++}
}
