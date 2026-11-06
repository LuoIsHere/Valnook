package dev.valnook.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.StatisticsPoint
import dev.valnook.domain.repository.StatisticsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The account's net assets by day. A failed read keeps the last line: the balance above it comes from elsewhere. */
class AccountTrendViewModel(private val accountId: Long, private val repository: StatisticsRepository) : ViewModel() {
    private val mutable = MutableStateFlow<List<StatisticsPoint>>(emptyList())
    val points = mutable.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { repository.observeRevision().distinctUntilChanged().collect { refresh() } }
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                mutable.value = repository.loadAccountTrend(accountId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep what is on screen; the next change or return to this page reads again.
            }
        }
    }
}
