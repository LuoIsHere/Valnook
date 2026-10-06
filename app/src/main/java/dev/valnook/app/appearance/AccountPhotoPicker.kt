package dev.valnook.app.appearance

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.*
import dev.valnook.feature.accounts.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AccountPhotoPicker(onImage: (ByteArray) -> Unit, content: @Composable (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            selectedUri = uri.toString()
        }
    }
    content { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val uri = selectedUri
    if (uri != null) key(uri) {
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var failed by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var fit by rememberSaveable { mutableStateOf(false) }
        var zoom by rememberSaveable { mutableFloatStateOf(1f) }
        var panX by rememberSaveable { mutableFloatStateOf(0f) }
        var panY by rememberSaveable { mutableFloatStateOf(0f) }
        var open by remember { mutableStateOf(true) }
        LaunchedEffect(uri) {
            try { bitmap = withContext(Dispatchers.IO) { readAccountPhoto(context, Uri.parse(uri)) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        AnimatedGlassDialog(open, { if (!busy) open = false }, onClosed = {
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            selectedUri = null
        }) {
            GlassCard {
                Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.account_icon_adjust), style = MaterialTheme.typography.titleLarge)
                    val source = bitmap
                    if (source != null) {
                        Canvas(Modifier.size(220.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .testTag("account-photo-crop").pointerInput(fit, busy) {
                                detectTransformGestures { _, pan, change, _ ->
                                    if (!busy) {
                                        zoom = (zoom * change).coerceIn(1f, 4f)
                                        panX = (panX + pan.x / size.width).coerceIn(-2f, 2f)
                                        panY = (panY + pan.y / size.height).coerceIn(-2f, 2f)
                                    }
                                }
                            }) {
                            drawIntoCanvas { canvas -> canvas.nativeCanvas.drawBitmap(source, null,
                                accountPhotoRect(source.width, source.height, size.width, fit, zoom, panX, panY),
                                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)) }
                        }
                        Text(stringResource(R.string.account_icon_gesture), style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(fit, { fit = it; zoom = 1f; panX = 0f; panY = 0f }, enabled = !busy)
                            Text(stringResource(R.string.account_icon_fit))
                        }
                        Text(stringResource(R.string.account_icon_zoom), style = MaterialTheme.typography.labelLarge)
                        Slider(zoom, { zoom = it }, valueRange = 1f..4f, enabled = !busy, modifier = Modifier.testTag("account-photo-zoom"))
                    } else if (!failed) CircularProgressIndicator()
                    if (failed) HintMessage(stringResource(R.string.account_icon_failed), isError = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ open = false }, enabled = !busy) { Text(stringResource(R.string.account_order_cancel)) }
                        Button({
                            if (!busy && source != null) scope.launch {
                                busy = true; failed = false
                                try {
                                    val bytes = withContext(Dispatchers.Default) { encodeAccountPhoto(source, fit, zoom, panX, panY) }
                                    onImage(bytes); open = false
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { failed = true }
                                finally { busy = false }
                            }
                        }, enabled = open && source != null && !busy, modifier = Modifier.testTag("account-photo-confirm")) {
                            Text(stringResource(R.string.account_icon_use))
                        }
                    }
                }
            }
        }
    }
}
