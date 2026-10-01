package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A shared, scrollable record layout; feature modules supply values and actions. */
@Composable fun RecordDetailLayout(title:String,rows:List<Pair<String,String>>,actions:@Composable ColumnScope.()->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=pageContentPadding(),verticalArrangement=Arrangement.spacedBy(Space.md)) {
        item{Text(title,style=MaterialTheme.typography.titleLarge)}
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal=Space.md)) {
                    rows.forEachIndexed {index,(label,value)->
                        DetailRow(label,value)
                        if(index<rows.lastIndex)HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        item{Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(Space.sm),content=actions)}
    }
}
