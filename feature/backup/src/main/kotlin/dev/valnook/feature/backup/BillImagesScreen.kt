package dev.valnook.feature.backup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.valnook.designsystem.*
import dev.valnook.domain.cloud.CloudBackupService
import dev.valnook.domain.cloud.CloudImageStatus
import dev.valnook.domain.repository.BILL_IMAGE_CLEANUP_MONTHS
import dev.valnook.domain.repository.BillImageCleanup
import dev.valnook.domain.repository.BillImageStorage
import dev.valnook.domain.repository.BillImageUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Clock

data class BillImagesState(
    val usage: BillImageUsage = BillImageUsage(),
    val autoCleanMonths: Int? = null,
    val connected: Boolean = false,
    val cloud: CloudImageStatus = CloudImageStatus(),
    val busy: Boolean = false,
    /** What the last clean-up on this page did. */
    val cleared: BillImageCleanup? = null,
    val deleted: Int? = null,
)

/** Settings → Bill pictures: room they take here, the automatic clean-up, clearing by hand and fetching back. */
class BillImagesViewModel(private val storage: BillImageStorage, private val cloud: CloudBackupService,
    private val clock: Clock) : ViewModel() {
    private val action = MutableStateFlow(Triple(false, null as BillImageCleanup?, null as Int?))
    val state: StateFlow<BillImagesState> = combine(storage.observeUsage(), storage.observeAutoCleanMonths(),
        cloud.observeState().map { it.connected }, cloud.observeImages(), action) { usage, months, connected, images, (busy, cleared, deleted) ->
        BillImagesState(usage, months, connected, images, busy, cleared, deleted)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BillImagesState())

    fun setAutoClean(months: Int?) { viewModelScope.launch { storage.setAutoCleanMonths(months) } }

    /** [months] back from now, or everything when null; [everywhere] takes the pictures off their bills for good. */
    fun clear(months: Int?, everywhere: Boolean) = act {
        val before = months?.let { clock.millis() - it * 30L * 24 * 60 * 60 * 1000 }
        if (everywhere || !state.value.connected) action.value = Triple(false, null, storage.deleteEverywhere(before))
        else action.value = Triple(false, storage.clearDevice(before), null)
    }

    fun fetchAll() = act { cloud.fetchMissingImages(null); action.value = Triple(false, null, null) }

    fun stop() = cloud.stopImageTransfer()

    private fun act(block: suspend () -> Unit) {
        if (action.value.first) return
        action.value = Triple(true, null, null)
        viewModelScope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { action.value = Triple(false, null, null) }
        }
    }
}

/** "n pictures · x MB", for the settings entry. */
@Composable fun billImagesSummaryText(usage: BillImageUsage?): String =
    if (usage == null) "" else stringResource(R.string.bill_images_summary, usage.onDevice, sizeText(usage.onDeviceBytes))

/** "3.3 MB", or "48 KB" below one megabyte, where a tenth of a megabyte would read as nothing. */
internal fun sizeText(bytes: Long): String = if ((bytes + 1023) / 1024 < 1000) "${(bytes + 1023) / 1024} KB"
    else java.math.BigDecimal(bytes).divide(java.math.BigDecimal(1024 * 1024), 1, java.math.RoundingMode.HALF_UP).toPlainString() + " MB"

@Composable fun BillImagesScreen(vm: BillImagesViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prompts = remember(context) { context.getSharedPreferences("valnook_backup_prompts", android.content.Context.MODE_PRIVATE) }
    var clearing by rememberSaveable { mutableStateOf(false) }
    var reminding by rememberSaveable { mutableStateOf(false) }
    val usage = state.usage
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().testTag("bill-images-page"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item {
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(Space.cardInset), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.bill_images_on_device, usage.onDevice, sizeText(usage.onDeviceBytes)),
                        Modifier.testTag("bill-images-on-device"), style = MaterialTheme.typography.titleMedium)
                    if (usage.onlyInCloud > 0) Text(stringResource(R.string.bill_images_only_cloud, usage.onlyInCloud),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    if (usage.notUploaded > 0) Text(stringResource(R.string.bill_images_not_uploaded, usage.notUploaded),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
        item {
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(Space.cardInset), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    SwitchRow(stringResource(R.string.bill_images_auto_clean), state.autoCleanMonths != null,
                        { on -> vm.setAutoClean(if (on) 3 else null) }, Modifier.testTag("bill-images-auto-clean"),
                        enabled = state.connected || state.autoCleanMonths != null, contentPadding = PaddingValues(0.dp))
                    Text(stringResource(if (state.connected) R.string.bill_images_auto_clean_hint else R.string.bill_images_auto_clean_needs_cloud),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    // Equal widths, one line each: a long name shrinks instead of wrapping on a narrow screen or large text.
                    state.autoCleanMonths?.let { selected ->
                        CapsuleChoiceRow(BILL_IMAGE_CLEANUP_MONTHS, selected, vm::setAutoClean, Modifier.fillMaxWidth(),
                            optionModifier = { Modifier.testTag("bill-images-auto-$it") }) { months ->
                            Text(stringResource(R.string.bill_images_months, months), style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, autoSize = TextAutoSize.StepBased(9.sp, MaterialTheme.typography.labelLarge.fontSize, .5.sp))
                        }
                    }
                }
            }
        }
        item {
            DangerButton({ clearing = true }, Modifier.fillMaxWidth().testTag("bill-images-clear"), enabled = !state.busy && usage.onDevice > 0) {
                Text(stringResource(R.string.bill_images_clear))
            }
        }
        if (state.connected && usage.onlyInCloud > 0) item {
            PrimaryButton({
                if (usage.onlyInCloud > FOREGROUND_REMINDER_ABOVE && !prompts.getBoolean("foreground_reminder_off", false)) reminding = true
                else vm.fetchAll()
            }, Modifier.fillMaxWidth().testTag("bill-images-fetch-all"), enabled = !state.busy && state.cloud.fetching == null) {
                Text(stringResource(R.string.bill_images_fetch_all))
            }
        }
        state.cloud.fetching?.let { (done, total) -> item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cloud_images_fetching, done, total), Modifier.weight(1f).testTag("bill-images-fetching"))
                TextButton(vm::stop) { Text(stringResource(R.string.cloud_images_stop)) }
            }
        } }
        state.cleared?.let { result -> item {
            Text(if (result.keptNotUploaded > 0) stringResource(R.string.bill_images_cleared_kept, result.removed, result.keptNotUploaded)
                else stringResource(R.string.bill_images_cleared, result.removed), Modifier.testTag("bill-images-result"), color = colors.primary)
        } }
        state.deleted?.let { count -> item {
            Text(stringResource(R.string.bill_images_deleted, count), Modifier.testTag("bill-images-result"), color = colors.primary)
        } }
    }
    if (clearing) ClearDialog(state.connected, onCancel = { clearing = false }) { months, everywhere ->
        clearing = false
        vm.clear(months, everywhere)
    }
    if (reminding) ForegroundReminderDialog(usage.onlyInCloud, fetching = true, onCancel = { reminding = false }) { off ->
        if (off) prompts.edit().putBoolean("foreground_reminder_off", true).apply()
        reminding = false
        vm.fetchAll()
    }
}

/** How far back to clear, and whether OneDrive's copies go too, with what each choice means. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ClearDialog(connected: Boolean, onCancel: () -> Unit, onConfirm: (months: Int?, everywhere: Boolean) -> Unit) {
    var months by rememberSaveable { mutableStateOf<Int?>(3) }
    var everywhere by rememberSaveable { mutableStateOf(false) }
    val forGood = everywhere || !connected
    AnimatedGlassDialog(true, onCancel) {
        DialogCard(stringResource(R.string.bill_images_clear), Modifier.testTag("bill-images-clear-dialog"), buttons = {
            TextButton(onCancel) { Text(stringResource(R.string.backup_cancel)) }
            ValnookTextButton(stringResource(if (forGood) R.string.bill_images_delete else R.string.bill_images_clear_action),
                { onConfirm(months, everywhere) }, Modifier.testTag("bill-images-clear-confirm"), danger = forGood)
        }) {
            Text(stringResource(R.string.bill_images_clear_range), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                BILL_IMAGE_CLEANUP_MONTHS.forEach { value ->
                    FilterChip(months == value, { months = value }, { Text(stringResource(R.string.bill_images_older_than, value)) },
                        Modifier.testTag("bill-images-clear-$value"))
                }
                FilterChip(months == null, { months = null }, { Text(stringResource(R.string.bill_images_all)) },
                    Modifier.testTag("bill-images-clear-all"))
            }
            if (connected) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).toggleable(everywhere, onValueChange = { everywhere = it })
                .testTag("bill-images-clear-everywhere"), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(everywhere, null)
                Text(stringResource(R.string.bill_images_also_cloud), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(when {
                !connected -> R.string.bill_images_clear_no_cloud
                everywhere -> R.string.bill_images_clear_everywhere
                else -> R.string.bill_images_clear_device
            }), style = MaterialTheme.typography.bodySmall,
                color = if (forGood) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
