package dev.valnook.feature.wallet

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.snapshotFlow
import dev.valnook.domain.model.WalletCard
import dev.valnook.domain.repository.WalletRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Activity/session scoped, intentionally absent from SavedStateHandle and the database. */
class WalletOverviewViewModel(private val repository:WalletRepository):ViewModel() {
    val scroll=LazyListState()
    val cache=WalletImageCache(repository)
    private val mutableCards=MutableStateFlow<List<WalletCard>?>(null)
    val cards=mutableCards.asStateFlow()
    private val preloadEdge=MutableStateFlow(0)
    private var observation:Job?=null
    private var warming:Job?=null

    fun start(edge:Int) {
        preloadEdge.value=edge.coerceIn(160,1120)
        if(observation?.isActive!=true)observation=viewModelScope.launch {
            repository.observeCards().retryWhen { _,_->delay(1500);true }.collect{mutableCards.value=it}
        }
        if(warming?.isActive!=true)warming=viewModelScope.launch {
            combine(cards.filterNotNull(),preloadEdge,snapshotFlow{scroll.firstVisibleItemIndex}){items,width,index->Triple(items,width,index)}
                .collectLatest{(items,width,index)->
                    // Keep startup decoding bounded; scrolling raises nearby cards' priority.
                    val bytesPerCard=(width.toLong()*width*4/1.586).toLong()
                    val count=(16L*1024*1024/bytesPerCard).toInt().coerceIn(2,12)
                    val candidates=items.asReversed().drop((index-1).coerceAtLeast(0)).take(count)
                    coroutineScope{candidates.mapNotNull{it.imageKey}.distinct().map{key->async{
                        try { cache.load(key,width) }
                        catch(cancelled:CancellationException){throw cancelled}
                        catch(_:Exception){ /* A damaged card must not stop warming other cards. */ }
                    }}.awaitAll()}
                }
        }
    }

    fun stop(){observation?.cancel();warming?.cancel();cache.clear()}
    fun trimMemory(){warming?.cancel();cache.clear()}
    override fun onCleared(){stop()}
}
