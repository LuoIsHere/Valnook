package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Top-level navigation actions share a transparent surface and symmetric spacing. */
@Composable fun TopBarAction(label:String,on_click:()->Unit,modifier:Modifier=Modifier) {
    TextButton(onClick=on_click,
        modifier=modifier.padding(horizontal=4.dp).heightIn(min=48.dp).widthIn(min=64.dp),
        contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)) {Text(label)}
}
