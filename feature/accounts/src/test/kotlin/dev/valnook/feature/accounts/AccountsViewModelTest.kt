package dev.valnook.feature.accounts
import androidx.lifecycle.SavedStateHandle
import dev.valnook.domain.repository.*
import dev.valnook.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun prepare(){Dispatchers.setMain(dispatcher)}
    @After fun close(){Dispatchers.resetMain()}
    @Test fun edit_uses_stable_id_and_keeps_name_note()=runTest(dispatcher) {
        var result:Triple<Long?,String,String>?=null
        val repo=object:AccountRepository{
            override fun observe_accounts()=flowOf(emptyList<SavingsAccount>())
            override suspend fun save_account(id:Long?,name:String,note:String):Long{result=Triple(id,name,note);return id?:1}}
        val vm=AccountsViewModel(repo,SavedStateHandle())
        vm.begin(SavingsAccount(7,"旧名称","旧备注"));vm.field("name","新名称");vm.submit();runCurrent()
        assertEquals(Triple(7L,"新名称","旧备注"),result);assertTrue(vm.draft.value.completed)
    }
}

