package dev.valnook.feature.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.valnook.designsystem.GlassCard
import dev.valnook.designsystem.RingChart
import dev.valnook.designsystem.RingSlice
import dev.valnook.designsystem.Space
import dev.valnook.domain.calculation.AssetDistribution
import dev.valnook.domain.calculation.DistributionPart
import dev.valnook.domain.model.AssetTypeRules
import dev.valnook.domain.repository.OverviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.math.BigDecimal
import java.math.RoundingMode

/** Today's assets by account, asset type and currency; follows the data while the page is shown. */
class DistributionViewModel(overview: OverviewRepository) : ViewModel() {
    val state: StateFlow<AssetDistribution?> = overview.observeSnapshot().map(AssetDistribution::calculate)
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Rows shown before "show all" in a long list, as the account list. */
private const val COLLAPSED_ROWS = 8

/** The same six colours as the web admin's distribution: five for the largest parts, grey for the rest. */
private val LIGHT_PARTS = listOf(0xFFC99419, 0xFF4F7CAC, 0xFF2E8B7A, 0xFF9A6B9C, 0xFFC27C4E, 0xFF9A9AA2).map(::Color)
private val DARK_PARTS = listOf(0xFFE5B83D, 0xFF59B5E8, 0xFF5CC0AB, 0xFFA79BFF, 0xFFE2A077, 0xFF8A8A92).map(::Color)

@Composable
internal fun AssetDistributionSection(vm: DistributionViewModel) {
    val distribution by vm.state.collectAsStateWithLifecycle()
    val value = distribution ?: return
    val uncategorized = stringResource(R.string.statistics_type_default)
    Column(Modifier.fillMaxWidth().testTag("statistics-distribution"), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Text(stringResource(R.string.statistics_distribution), Modifier.padding(start = 4.dp, top = Space.sm).semantics { heading() },
            style = MaterialTheme.typography.titleMedium)
        DistributionCard("accounts", stringResource(R.string.statistics_by_account), value.accounts,
            note = stringResource(R.string.statistics_negative_left_out))
        DistributionCard("types", stringResource(R.string.statistics_by_asset_type),
            value.assetTypes.map { if (AssetTypeRules.isDefaultName(it.label)) it.copy(label = uncategorized) else it })
        DistributionCard("currencies", stringResource(R.string.statistics_by_currency), value.currencies)
    }
}

/**
 * One distribution: a ring of the parts above zero, largest first, and a row for each. Tapping a row picks out its
 * part on the ring and shows its share in the middle; tapping it again lets go.
 */
@Composable
private fun DistributionCard(key: String, title: String, all: List<DistributionPart>, note: String? = null) {
    // Two accounts may share a name: the n-th part with a label is told apart by n.
    val parts = remember(all) {
        val seen = mutableMapOf<String, Int>()
        all.filter { it.value.signum() > 0 }.sortedByDescending { it.value }.map { part ->
            val n = seen.merge(part.label, 1, Int::plus)!!
            Part(if (n == 1) part.label else "${part.label}#$n", part.label, part.value)
        }
    }
    val total = remember(parts) { parts.fold(BigDecimal.ZERO) { sum, part -> sum + part.value } }
    val palette = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) DARK_PARTS else LIGHT_PARTS
    var chosen by rememberSaveable(key) { mutableStateOf<String?>(null) }
    val selected = chosen?.takeIf { id -> parts.any { it.id == id } }
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    GlassCard {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.cardInset).testTag("statistics-distribution-$key"),
            verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Column(Modifier.padding(horizontal = Space.cardInset)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (parts.isEmpty()) {
                Text(stringResource(R.string.statistics_distribution_empty), Modifier.fillMaxWidth().padding(Space.cardInset),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
                return@Column
            }
            val colors = parts.indices.map { palette[it.coerceAtMost(palette.lastIndex)] }
            val slices = parts.mapIndexed { index, part ->
                RingSlice(part.id, fraction(part.value, total),
                    if (selected == null || selected == part.id) colors[index] else colors[index].copy(alpha = 0.25f))
            }
            val separator = if (LocalConfiguration.current.locales[0].language == "zh") "，" else ", "
            val description = stringResource(R.string.statistics_distribution_description, title,
                parts.take(5).joinToString(separator) { it.label + " " + shareText(it.value, total) })
            RingChart(slices, description, Modifier.align(Alignment.CenterHorizontally).width(160.dp)
                .padding(vertical = Space.sm).testTag("statistics-distribution-$key-ring"), thickness = 16.dp,
                animationKey = "statistics-distribution-$key") {
                val picked = parts.firstOrNull { it.id == selected }
                Column(Modifier.padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(picked?.let { shareText(it.value, total) } ?: pluralStringResource(R.plurals.statistics_item_count,
                        parts.size, parts.size), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        maxLines = 1, modifier = Modifier.testTag("statistics-distribution-$key-center"))
                    Text(picked?.label ?: stringResource(R.string.statistics_distribution_total), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val long = parts.size > COLLAPSED_ROWS + 1
            val shown = if (long && !expanded) parts.take(COLLAPSED_ROWS) else parts
            shown.forEachIndexed { index, part ->
                DistributionRow(part.label, shareText(part.value, total), colors[index], selected == part.id,
                    Modifier.testTag("statistics-distribution-$key-row-$index")) {
                    chosen = if (selected == part.id) null else part.id
                }
            }
            if (long) TextButton({ expanded = !expanded }, Modifier.align(Alignment.CenterHorizontally)
                .testTag("statistics-distribution-$key-more")) {
                Text(if (expanded) stringResource(R.string.statistics_show_less)
                    else pluralStringResource(R.plurals.statistics_show_all, parts.size, parts.size))
            }
        }
    }
}

private data class Part(val id: String, val label: String, val value: BigDecimal)

@Composable
private fun DistributionRow(label: String, share: String, color: Color, picked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = Space.sm).clip(RoundedCornerShape(12.dp))
        .background(if (picked) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { selected = picked }
        .padding(horizontal = Space.cardInset - Space.sm, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(share, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
    }
}

private fun fraction(part: BigDecimal, whole: BigDecimal): Float =
    if (whole.signum() <= 0) 0f else part.divide(whole, 6, RoundingMode.HALF_UP).toFloat()

/** "34.5%", and "<0.1%" for a sliver that would round to nothing, as on the bill statistics. */
internal fun shareText(part: BigDecimal, whole: BigDecimal): String {
    if (whole.signum() <= 0) return "0%"
    val percent = part.multiply(BigDecimal(100)).divide(whole, 1, RoundingMode.HALF_UP)
    return if (percent.signum() == 0 && part.signum() > 0) "<0.1%" else percent.toPlainString() + "%"
}
