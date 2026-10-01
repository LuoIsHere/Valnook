package dev.valnook.feature.accounts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.core.designsystem.R
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as Decimal
import java.time.*

@Composable fun AccountsScreen(vm:AccountsViewModel,on_open:(Long)->Unit,on_form:()->Unit) {
    val rows by vm.accounts.collectAsStateWithLifecycle()
    AccountsContent(rows,on_open,{vm.begin(null);on_form()})
}
@Composable fun AccountsContent(rows:List<SavingsAccount>,on_open:(Long)->Unit,on_add:()->Unit) {
    LazyColumn(contentPadding=PaddingValues(Space.md),verticalArrangement=Arrangement.spacedBy(Space.md)) {
        item { Text(stringResource(R.string.accounts),style=MaterialTheme.typography.headlineSmall) }
        if(rows.isEmpty()) item {EmptyState(stringResource(R.string.empty_accounts))}
        items(rows,key={it.id}) { account ->
            ActionButton(onClick={on_open(account.id)},modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical=Space.sm),verticalArrangement=Arrangement.spacedBy(Space.sm)) {
                    Text(account.name,style=MaterialTheme.typography.titleLarge)
                    if(account.note.isNotBlank())Text(account.note,style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {Button(onClick=on_add,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.add_account))}}
    }
}
@Composable fun AccountForm(vm:AccountsViewModel,on_back:()->Unit) {
    val state by vm.draft.collectAsStateWithLifecycle()
    LaunchedEffect(state.completed){if(state.completed&&vm.consume_completion())on_back()}
    FormPanel(stringResource(R.string.accounts),state,on_back,vm::submit) {
        Field(stringResource(R.string.name),state.fields["name"].orEmpty(),{vm.field("name",it)},enabled=!state.busy&&!state.locked)
        Field(stringResource(R.string.note),state.fields["note"].orEmpty(),{vm.field("note",it)},enabled=!state.busy&&!state.locked)
    }
}
@Preview(showBackground=true,widthDp=360)
@Preview(showBackground=true,widthDp=420,fontScale=2f)
@Composable fun AccountsPreview() { ValnookTheme { AccountsContent(listOf(SavingsAccount(1,"合成储蓄账户 · 一个很长的账户名称","多币种资产")),{},{}) } }
@Preview(showBackground=true,uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable fun EmptyAccountsPreview() {ValnookTheme{AccountsContent(emptyList(),{},{})}}
