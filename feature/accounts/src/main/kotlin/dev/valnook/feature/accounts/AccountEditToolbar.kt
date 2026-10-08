package dev.valnook.feature.accounts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

data class AccountEditToolbarState(val busy: Boolean, val enabled: Boolean, val save: () -> Unit)

@Composable fun AccountEditSaveAction(state: AccountEditToolbarState?) {
    val label = stringResource(R.string.account_save)
    IconButton(onClick = { state?.save?.invoke() }, enabled = state?.enabled == true,
        modifier = Modifier.testTag("account-edit-save").semantics { contentDescription = label }) {
        if (state?.busy == true) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else {
            val color = LocalContentColor.current
            Canvas(Modifier.size(24.dp)) {
                drawLine(color, Offset(size.width * .18f, size.height * .5f), Offset(size.width * .42f, size.height * .74f), 2.dp.toPx(), StrokeCap.Round)
                drawLine(color, Offset(size.width * .42f, size.height * .74f), Offset(size.width * .84f, size.height * .25f), 2.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}
