package dev.valnook.feature.webadmin

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.valnook.designsystem.FaIcon
import dev.valnook.designsystem.FaIcons
import dev.valnook.designsystem.FormDivider
import dev.valnook.designsystem.FormSection
import dev.valnook.designsystem.GlassCard
import dev.valnook.designsystem.HintMessage
import dev.valnook.designsystem.PrimaryButton
import dev.valnook.designsystem.SecondaryButton
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.StatusTag
import dev.valnook.designsystem.TagTone
import dev.valnook.designsystem.pageContentPadding
import dev.valnook.core.designsystem.R as DesignR
import dev.valnook.domain.webadmin.WebAdminClient
import dev.valnook.domain.webadmin.WebAdminError
import dev.valnook.domain.webadmin.WebAdminPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings → Web administration: one card that follows the session (off, waiting for pairing, connected) and the
 * rules of use underneath. While connected the whole app is covered by [WebAdminLockScreen] instead.
 */
@Composable
fun WebAdminScreen(vm: WebAdminViewModel, demoMode: Boolean = false) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pageContentPadding()),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        state.error?.let { HintMessage(errorText(it), isError = true, modifier = Modifier.testTag("webadmin-error")) }
        GlassCard(prominent = true) {
            Column(Modifier.fillMaxWidth().padding(Space.cardInset).animateContentSize(tween(220)),
                verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                Header(state.phase, demoMode)
                AnimatedContent(state.phase, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                    label = "webadmin-phase") { phase ->
                    when (phase) {
                        WebAdminPhase.CLOSED -> PrimaryButton(vm::start, Modifier.fillMaxWidth().testTag("webadmin-start")) {
                            Text(stringResource(R.string.webadmin_start))
                        }
                        WebAdminPhase.WAITING -> Pairing(state.url.orEmpty(), state.pairingCode.orEmpty(), state.qrContent)
                        WebAdminPhase.ACTIVE -> Unit
                    }
                }
            }
        }
        if (state.phase != WebAdminPhase.CLOSED) SecondaryButton(vm::stop, Modifier.fillMaxWidth().testTag("webadmin-stop")) {
            Text(stringResource(if (state.phase == WebAdminPhase.ACTIVE) R.string.webadmin_end else R.string.webadmin_stop))
        }
        Notes()
    }
}

@Composable
private fun Header(phase: WebAdminPhase, demoMode: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        Surface(Modifier.size(44.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(contentAlignment = Alignment.Center) { FaIcon(DesignR.drawable.fa_laptop, null, Modifier.size(22.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(stringResource(R.string.webadmin_hero), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
                when (phase) {
                    WebAdminPhase.CLOSED -> StatusTag(stringResource(R.string.webadmin_off), TagTone.NEUTRAL)
                    WebAdminPhase.WAITING -> StatusTag(stringResource(R.string.webadmin_waiting), TagTone.ATTENTION)
                    WebAdminPhase.ACTIVE -> StatusTag(stringResource(R.string.webadmin_active), TagTone.POSITIVE)
                }
            }
            Text(stringResource(if (demoMode) R.string.webadmin_description_demo else R.string.webadmin_description),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Two steps on a computer: open the address, type the code. The QR code serves a phone or tablet instead. */
@Composable
private fun Pairing(url: String, code: String, qrContent: String?) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Step(1, stringResource(R.string.webadmin_step_open)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(start = Space.md), verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(url, Modifier.testTag("webadmin-url"), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                    }
                    IconButton({
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(url, url))
                    }, Modifier.testTag("webadmin-copy-url")) {
                        FaIcon(FaIcons.copy, stringResource(R.string.webadmin_copy_url), Modifier.size(18.dp))
                    }
                }
            }
        }
        Step(2, stringResource(R.string.webadmin_step_code)) {
            val label = stringResource(R.string.webadmin_pairing_code)
            Row(Modifier.widthIn(max = 360.dp).fillMaxWidth().testTag("webadmin-code").semantics(mergeDescendants = true) {
                contentDescription = "$label $code"
            }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                code.forEachIndexed { index, digit ->
                    if (index == 3) Spacer(Modifier.width(6.dp))
                    Surface(Modifier.weight(1f).heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(digit.toString(), style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace))
                        }
                    }
                }
            }
        }
        qrContent?.let { value ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(stringResource(R.string.webadmin_scan), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Drawn pixel by pixel, so off the main thread; the white square holds its place meanwhile.
                val image by produceState<Bitmap?>(null, value) { this.value = withContext(Dispatchers.Default) { qr(value, 640) } }
                // Always white for scanners; the outline keeps it apart from a light card.
                Surface(color = Color.White, shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    val square = Modifier.size(184.dp).padding(10.dp)
                    image?.let { Image(it.asImageBitmap(), stringResource(R.string.webadmin_qr_description), square.testTag("webadmin-qr")) }
                        ?: Box(square)
                }
            }
        }
        Text(stringResource(R.string.webadmin_timeout), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Step(number: Int, title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Surface(Modifier.size(22.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Box(contentAlignment = Alignment.Center) {
                    Text(number.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        content()
    }
}

/** What to know before starting, one line each. */
@Composable
private fun Notes() {
    FormSection(stringResource(R.string.webadmin_notes)) {
        val notes = listOf(DesignR.drawable.fa_wifi to R.string.webadmin_note_network,
            DesignR.drawable.fa_shield_halved to R.string.webadmin_note_plain,
            DesignR.drawable.fa_mobile_screen to R.string.webadmin_note_lock,
            DesignR.drawable.fa_user_clock to R.string.webadmin_note_end)
        notes.forEachIndexed { index, (icon, text) ->
            if (index > 0) FormDivider(inset = 52.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.cardInset, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                FaIcon(icon, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
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
            PrimaryButton(onEnd, Modifier.fillMaxWidth().testTag("webadmin-lock-end")) {
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
    WebAdminError.PORT_IN_USE -> stringResource(R.string.webadmin_error_port, dev.valnook.domain.webadmin.WEB_DEFAULT_PORT)
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
