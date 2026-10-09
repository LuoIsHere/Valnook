package dev.valnook.feature.wallet

import androidx.lifecycle.SavedStateHandle
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.*

@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val clock=Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"),ZoneOffset.UTC)
    private val cards=MutableStateFlow(listOf(WalletCard(1,"A",null,10,0,0,0,1),WalletCard(2,"B",null,20,1,0,0,1)))
    private val revisions=MutableStateFlow(1L)
    private val requests=mutableListOf<Pair<Long,LedgerMonth>>()
    private val repository=object:WalletRepository {
        override fun observeCards()=cards
        override suspend fun image(key:String):WalletImage?=null
        override suspend fun save(id:Long?,expectedRevision:Long?,name:String,cashAccountId:Long?,imageKey:String?,image:WalletImage?)=error("unused")
        override suspend fun delete(id:Long,expectedRevision:Long)=error("unused")
        override suspend fun reorder(expected:List<Long>,ordered:List<Long>)=error("unused")
    }
    private val snapshot=AssetSnapshot(emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),AppSettings())
    private val overview=object:OverviewRepository {
        override fun observeSnapshot()=flowOf(snapshot)
        override suspend fun snapshot()=snapshot
    }
    private val pages=object:PagedCashRepository {
        override fun observeRevision(accountId:Long,currencyCode:String)=revisions
        override fun observeCashAccountRevision(cashAccountId:Long)=revisions
        override suspend fun page(accountId:Long,currencyCode:String,cursor:LedgerCursor?,size:Int):List<CashEntry> = error("History must not be loaded")
        override suspend fun cashAccountPage(cashAccountId:Long,cursor:LedgerCursor?,size:Int):List<CashEntry> = error("History must not be loaded")
        override suspend fun cashAccountMonthPage(cashAccountId:Long,month:LedgerMonth,cursor:LedgerCursor?,size:Int):List<CashEntry> {
            requests+=cashAccountId to month
            // An intentionally uncooperative source proves the generation guard independently of cancellation.
            withContext(NonCancellable){delay(if(month.month.monthValue==10)300 else 20)}
            return listOf(CashEntry(cashAccountId,1,Currency.of("USD"),1,month.startMs,CashSource.CASH_SET,null,null,"${month.month}",1,cashAccountId,"Cash"))
        }
    }
    @Before fun prepare(){Dispatchers.setMain(dispatcher)}
    @After fun close(){Dispatchers.resetMain()}
    @Test fun fast_month_switch_and_rebinding_ignore_late_results()=runTest(dispatcher) {
        val vm=WalletViewModel("test",repository,overview,pages,clock,SavedStateHandle());runCurrent()
        vm.select(1);runCurrent();vm.month(YearMonth.of(2026,9));advanceUntilIdle()
        assertEquals("2026-09",vm.ledger.value.rows.single().note)
        vm.month(YearMonth.of(2026,10));runCurrent();vm.select(2);advanceUntilIdle()
        assertEquals(20L,vm.ledger.value.rows.single().cashAccountId)
        cards.value=cards.value.map{if(it.id==2L)it.copy(boundCashAccountId=30)else it};advanceUntilIdle()
        assertEquals(30L,vm.ledger.value.rows.single().cashAccountId)
    }
    @Test fun account_refresh_preserves_month_and_new_selection_defaults_to_current_month()=runTest(dispatcher) {
        val vm=WalletViewModel("test",repository,overview,pages,clock,SavedStateHandle());runCurrent()
        vm.select(1);advanceUntilIdle();vm.month(YearMonth.of(2024,2));advanceUntilIdle()
        revisions.value++;advanceUntilIdle();assertEquals(YearMonth.of(2024,2),vm.ledger.value.month)
        vm.select(null);runCurrent();vm.select(1);advanceUntilIdle()
        assertEquals(YearMonth.of(2026,10),vm.ledger.value.month)
        assertTrue(requests.all{it.second.startMs<it.second.endMs})
    }
    @Test fun removing_binding_clears_rows_and_pending_requests()=runTest(dispatcher) {
        val vm=WalletViewModel("test",repository,overview,pages,clock,SavedStateHandle());runCurrent()
        vm.select(1);runCurrent();cards.value=cards.value.map{it.copy(boundCashAccountId=null)};advanceUntilIdle()
        assertTrue(vm.ledger.value.rows.isEmpty());assertFalse(vm.ledger.value.loading)
    }
}
