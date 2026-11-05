package dev.valnook.feature.accounts

import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.FaIcon
import dev.valnook.designsystem.FaIcons

data class AccountEditToolbarState(val busy: Boolean, val enabled: Boolean, val save: () -> Unit)

@Composable fun AccountEditSaveAction(state: AccountEditToolbarState?) {
    val label = stringResource(R.string.account_save)
    IconButton(onClick = { state?.save?.invoke() }, enabled = state?.enabled == true,
        modifier = Modifier.testTag("account-edit-save").semantics { contentDescription = label }) {
        if (state?.busy == true) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else {
            FaIcon(FaIcons.check, modifier = Modifier.size(22.dp))
        }
    }
}
