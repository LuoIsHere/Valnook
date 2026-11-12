package dev.valnook.feature.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/** The category being edited: a new one when [id] is null, under [parentId] when set. */
data class CategoryDraft(val id: Long?, val revision: Long?, val parentId: Long?, val builtinKey: String?, val name: String,
    val iconKey: String, val colorKey: String, val usage: Int = 0)

data class BillCategoriesState(
    val kind: BillCategoryKind = BillCategoryKind.EXPENSE,
    val categories: List<BillCategory> = emptyList(),
    val arranging: Long? = null,
    val arrangingTop: Boolean = false,
    val busy: Boolean = false,
    val error: ErrorCode? = null,
)

class BillCategoriesViewModel(private val bills: BillRepository, private val commands: FinancialCommands,
    private val saved: SavedStateHandle) : ViewModel() {
    private val kind = MutableStateFlow(saved.get<String>("kind")?.let(BillCategoryKind::valueOf) ?: BillCategoryKind.EXPENSE)
    private val ui = MutableStateFlow(BillCategoriesState())
    val state: StateFlow<BillCategoriesState> = combine(kind, bills.observeCategories(), ui) { k, all, u ->
        u.copy(kind = k, categories = all.filter { it.kind == k })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BillCategoriesState())

    fun setKind(value: BillCategoryKind) { kind.value = value; saved["kind"] = value.name; ui.value = ui.value.copy(arranging = null, arrangingTop = false) }
    fun arrangeTop(on: Boolean) { ui.value = ui.value.copy(arrangingTop = on, arranging = null) }
    fun arrange(parentId: Long?) { ui.value = ui.value.copy(arranging = parentId, arrangingTop = false) }

    suspend fun usage(categoryId: Long): Int = runCatching { bills.categoryUsage(categoryId) }.getOrDefault(0)

    private fun run(command: FinancialCommand, after: () -> Unit = {}) {
        if (ui.value.busy) return
        ui.value = ui.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { commands.execute(command); ui.value = ui.value.copy(busy = false); after() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: DomainException) { ui.value = ui.value.copy(busy = false, error = error.code) }
            catch (_: Exception) { ui.value = ui.value.copy(busy = false, error = ErrorCode.FORMAT) }
        }
    }

    /** A preset left with its own name keeps following the language. */
    fun save(draft: CategoryDraft, english: Boolean, after: () -> Unit) {
        val presetName = BillCategoryPresets.name(draft.builtinKey, english)
        val name = draft.name.trim().takeIf { it.isNotEmpty() && it != presetName }
        if (draft.builtinKey == null && name == null) { ui.value = ui.value.copy(error = ErrorCode.NAME); return }
        run(SaveBillCategory(UUID.randomUUID().toString(), draft.id, draft.revision, state.value.kind, draft.parentId, name,
            draft.iconKey, draft.colorKey), after)
    }

    fun setHidden(category: BillCategory, hidden: Boolean) =
        run(SetBillCategoryHidden(UUID.randomUUID().toString(), category.id, category.revision, hidden))

    fun reorder(parentId: Long?, orderedIds: List<Long>) =
        run(ReorderBillCategories(UUID.randomUUID().toString(), state.value.kind, parentId, orderedIds))

    fun delete(draft: CategoryDraft, moveTo: Long?, after: () -> Unit) {
        run(DeleteBillCategory(UUID.randomUUID().toString(), draft.id ?: return, draft.revision ?: return, moveTo), after)
    }

    fun clearError() { ui.value = ui.value.copy(error = null) }
}

@Composable fun BillCategoriesScreen(vm: BillCategoriesViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val english = englishNames()
    var editing by remember { mutableStateOf<CategoryDraft?>(null) }
    val scope = rememberCoroutineScope()
    val tops = state.categories.filter { it.parentId == null }
    fun open(category: BillCategory) {
        scope.launch {
            editing = CategoryDraft(category.id, category.revision, category.parentId, category.builtinKey,
                category.customName ?: category.name(english), category.iconKey, category.colorKey, vm.usage(category.id))
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("bill-categories"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item {
            CapsuleChoiceRow(BillCategoryKind.entries, state.kind, vm::setKind,
                optionModifier = { Modifier.testTag("bill-categories-${it.name}") }) {
                Text(stringResource(if (it == BillCategoryKind.EXPENSE) R.string.bill_expense else R.string.bill_income))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bill_categories_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ vm.arrangeTop(!state.arrangingTop) }, Modifier.testTag("bill-categories-arrange")) {
                    Text(stringResource(if (state.arrangingTop) R.string.bill_done else R.string.bill_arrange))
                }
            }
        }
        state.error?.let { item { ErrorMessage(it.name) } }
        if (state.arrangingTop) item {
            ReorderList(tops.map { ReorderItem(it.id.toString(), categoryName(it)) }, { keys -> vm.reorder(null, keys.map(String::toLong)) },
                stringResource(R.string.bill_arrange), stringResource(R.string.bill_move_up), stringResource(R.string.bill_move_down),
                Modifier.heightIn(max = 2000.dp))
        } else tops.forEach { top ->
            val subs = state.categories.filter { it.parentId == top.id }
            item(key = "category-${top.id}") {
                GlassCard {
                    CategoryLine(top, { open(top) }, { vm.setHidden(top, !top.hidden) }, sub = false)
                    if (state.arranging == top.id) ReorderList(subs.map { ReorderItem(it.id.toString(), categoryName(it)) },
                        { keys -> vm.reorder(top.id, keys.map(String::toLong)) }, stringResource(R.string.bill_arrange),
                        stringResource(R.string.bill_move_up), stringResource(R.string.bill_move_down), Modifier.heightIn(max = 1200.dp))
                    else subs.forEach { sub -> FormDivider(); CategoryLine(sub, { open(sub) }, { vm.setHidden(sub, !sub.hidden) }, sub = true) }
                    FormDivider()
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton({ editing = CategoryDraft(null, null, top.id, null, "", top.iconKey, top.colorKey) },
                            Modifier.testTag("bill-add-sub-${top.id}")) { Text(stringResource(R.string.bill_add_subcategory)) }
                        Spacer(Modifier.weight(1f))
                        if (subs.size > 1) TextButton({ vm.arrange(if (state.arranging == top.id) null else top.id) }) {
                            Text(stringResource(if (state.arranging == top.id) R.string.bill_done else R.string.bill_arrange))
                        }
                    }
                }
            }
        }
        item {
            ActionButton({ editing = CategoryDraft(null, null, null, null, "", "shapes", "blue") },
                Modifier.fillMaxWidth().testTag("bill-add-category")) { Text(stringResource(R.string.bill_add_category)) }
        }
    }
    editing?.let { draft ->
        CategoryEditor(draft, state, tops, english, onSave = { vm.save(it, english) { editing = null } },
            onDelete = { moveTo -> vm.delete(draft, moveTo) { editing = null } },
            onDismiss = { editing = null; vm.clearError() })
    }
}

@Composable private fun CategoryLine(category: BillCategory, onOpen: () -> Unit, onToggleHidden: () -> Unit, sub: Boolean) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)
        .padding(start = if (sub) 40.dp else 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
        .testTag("bill-category-line-${category.builtinKey ?: category.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        CategoryIcon(category.iconKey, category.colorKey, size = if (sub) 30.dp else 38.dp)
        Column(Modifier.weight(1f)) {
            Text(categoryName(category), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (category.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (category.hidden) Text(stringResource(R.string.bill_category_hidden), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val hideLabel = stringResource(if (category.hidden) R.string.bill_category_show else R.string.bill_category_hide)
        IconButton(onToggleHidden, Modifier.semantics { contentDescription = hideLabel }
            .testTag("bill-category-hide-${category.builtinKey ?: category.id}")) {
            FaIcon(if (category.hidden) FaIcons.eyeSlash else FaIcons.eye, null, Modifier.size(16.dp),
                MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Name, icon and colour of a category; a category the user added can also be deleted, moving its bills first. */
@Composable private fun CategoryEditor(initial: CategoryDraft, state: BillCategoriesState, tops: List<BillCategory>, english: Boolean,
    onSave: (CategoryDraft) -> Unit, onDelete: (Long?) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var deleting by remember(initial) { mutableStateOf(false) }
    var moveTo by remember(initial) { mutableStateOf<Long?>(null) }
    val presetName = BillCategoryPresets.name(draft.builtinKey, english)
    AnimatedGlassDialog(true, onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        GlassSurface(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(12.dp).testTag("bill-category-editor"),
            shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    CategoryIcon(draft.iconKey, draft.colorKey, size = 44.dp, selected = true)
                    Text(stringResource(if (draft.id == null) (if (draft.parentId == null) R.string.bill_add_category else R.string.bill_add_subcategory)
                        else R.string.bill_edit_category), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onDismiss) { FaIcon(FaIcons.xmark, stringResource(R.string.bill_close), Modifier.size(16.dp)) }
                }
                GlassTextField(draft.name, { draft = draft.copy(name = it.take(BillRules.MAX_CATEGORY_NAME_LENGTH)) },
                    stringResource(R.string.bill_category_name), Modifier.fillMaxWidth().testTag("bill-category-name"))
                if (presetName != null && draft.name != presetName) TextButton({ draft = draft.copy(name = presetName) },
                    Modifier.testTag("bill-category-default-name")) { Text(stringResource(R.string.bill_category_default_name, presetName)) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(stringResource(R.string.bill_category_color), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        BillColors.keys.forEach { key ->
                            val selected = key == draft.colorKey
                            Box(Modifier.size(26.dp).clip(CircleShape).background(billColor(key))
                                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable(role = Role.RadioButton) { draft = draft.copy(colorKey = key) }
                                .semantics { this.selected = selected; contentDescription = key }.testTag("bill-color-$key"))
                        }
                    }
                    BillIcons.groups.forEach { (group, keys) ->
                        Text(stringResource(BillIcons.groupLabel(group)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BoxWithConstraints {
                            val columns = (maxWidth / 52.dp).toInt().coerceAtLeast(5)
                            Column {
                                keys.chunked(columns).forEach { row ->
                                    Row(Modifier.fillMaxWidth()) {
                                        row.forEach { key ->
                                            Box(Modifier.weight(1f).padding(3.dp).clip(RoundedCornerShape(12.dp))
                                                .clickable(role = Role.RadioButton) { draft = draft.copy(iconKey = key) }
                                                .semantics { selected = key == draft.iconKey }.testTag("bill-icon-$key"),
                                                contentAlignment = Alignment.Center) {
                                                CategoryIcon(key, draft.colorKey, size = 40.dp, selected = key == draft.iconKey)
                                            }
                                        }
                                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                    if (deleting) {
                        val targets = state.categories.filter { it.id != draft.id && it.parentId != draft.id }
                        if (draft.usage > 0) {
                            Text(stringResource(R.string.bill_category_move_to, draft.usage), style = MaterialTheme.typography.bodyMedium)
                            targets.forEach { target ->
                                val name = (target.parentId?.let { p -> tops.firstOrNull { it.id == p }?.let { categoryName(it) + " › " } } ?: "") +
                                    categoryName(target)
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { moveTo = target.id }
                                    .padding(8.dp).testTag("bill-move-to-${target.builtinKey ?: target.id}"),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                                    RadioButton(moveTo == target.id, { moveTo = target.id })
                                    Text(name)
                                }
                            }
                        } else Text(stringResource(R.string.bill_category_delete_unused))
                    }
                }
                state.error?.let { ErrorMessage(it.name) }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    if (draft.id != null && draft.builtinKey == null) {
                        if (!deleting) TextButton({ deleting = true }, Modifier.testTag("bill-category-delete")) {
                            Text(stringResource(R.string.bill_delete), color = MaterialTheme.colorScheme.error) }
                        else TextButton({ onDelete(moveTo) }, Modifier.testTag("bill-category-delete-confirm"),
                            enabled = !state.busy && (draft.usage == 0 || moveTo != null)) {
                            Text(stringResource(R.string.bill_delete_confirm), color = MaterialTheme.colorScheme.error) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton({ onSave(draft) }, Modifier.testTag("bill-category-save"), enabled = !state.busy && !deleting &&
                        (draft.name.isNotBlank() || draft.builtinKey != null)) { Text(stringResource(R.string.bill_save)) }
                }
            }
        }
    }
}
