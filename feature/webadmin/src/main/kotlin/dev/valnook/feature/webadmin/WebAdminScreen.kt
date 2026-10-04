package dev.valnook.feature.webadmin

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.pageContentPadding
import dev.valnook.domain.webadmin.WebAdminClient
import dev.valnook.domain.webadmin.WebAdminError
import dev.valnook.domain.webadmin.WebAdminPhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun WebAdminScreen(vm: WebAdminViewModel, demoMode: Boolean = false) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pageContentPadding()),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Text(stringResource(if (demoMode) R.string.webadmin_description_demo else R.string.webadmin_description),
            style = MaterialTheme.typography.bodyMedium)
        Surface(color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium) {
            Text(stringResource(R.string.webadmin_plaintext_warning), Modifier.padding(Space.md),
                style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("webadmin-error")) }
        when (state.phase) {
            WebAdminPhase.CLOSED -> Button(vm::start, Modifier.fillMaxWidth().testTag("webadmin-start")) {
                Text(stringResource(R.string.webadmin_start))
            }
            WebAdminPhase.WAITING -> {
                Text(stringResource(R.string.webadmin_waiting), style = MaterialTheme.typography.titleLarge)
                SelectionContainer { Text(state.url.orEmpty(), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("webadmin-url")) }
                state.qrContent?.let { value ->
                    val image = remember(value) { qr(value, 640) }
                    Surface(color = androidx.compose.ui.graphics.Color.White, shape = MaterialTheme.shapes.small) {
                        Image(image.asImageBitmap(), stringResource(R.string.webadmin_qr_description),
                            Modifier.sizeIn(maxWidth = 280.dp, maxHeight = 280.dp).aspectRatio(1f)
                                .padding(12.dp).testTag("webadmin-qr"))
                    }
                }
                Text(stringResource(R.string.webadmin_pairing_code), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(state.pairingCode.orEmpty(),
                    style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().testTag("webadmin-code"), textAlign = TextAlign.Center) }
                Text(stringResource(R.string.webadmin_timeout), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(vm::stop, Modifier.fillMaxWidth().testTag("webadmin-stop")) {
                    Text(stringResource(R.string.webadmin_stop))
                }
            }
            WebAdminPhase.ACTIVE -> {
                Text(stringResource(R.string.webadmin_active), style = MaterialTheme.typography.titleLarge)
                ClientDetails(state.client)
                Button(vm::stop, Modifier.fillMaxWidth().testTag("webadmin-stop")) {
                    Text(stringResource(R.string.webadmin_end))
                }
            }
        }
    }
}

@Composable
fun WebAdminLockScreen(client: WebAdminClient?, onEnd: () -> Unit) {
    BackHandler(enabled = true) { /* ACTIVE can only be ended with the explicit action below. */ }
    Surface(Modifier.fillMaxSize().testTag("webadmin-lock"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.webadmin_lock_title), style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.md))
            Text(stringResource(R.string.webadmin_lock_message), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.lg))
            ClientDetails(client)
            Spacer(Modifier.height(Space.xl))
            Button(onEnd, Modifier.fillMaxWidth().testTag("webadmin-lock-end")) {
                Text(stringResource(R.string.webadmin_end))
            }
        }
    }
}

@Composable
private fun ClientDetails(client: WebAdminClient?) {
    if (client == null) return
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(client.label, style = MaterialTheme.typography.titleMedium)
        Text(client.remoteAddress, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.webadmin_connected_at, time(client.connectedAtMs)),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.webadmin_last_seen, time(client.lastSeenAtMs)),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun errorText(error: WebAdminError): String = when (error) {
    WebAdminError.NO_PRIVATE_LAN -> stringResource(R.string.webadmin_error_no_lan)
    WebAdminError.SESSION_BUSY -> stringResource(R.string.webadmin_error_busy)
    WebAdminError.MAINTENANCE_ACTIVE -> stringResource(R.string.webadmin_error_maintenance)
    WebAdminError.START_FAILED, WebAdminError.SESSION_EXPIRED,
    WebAdminError.INTERNAL -> stringResource(R.string.webadmin_error_start)
}

private fun qr(value: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
private fun time(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
