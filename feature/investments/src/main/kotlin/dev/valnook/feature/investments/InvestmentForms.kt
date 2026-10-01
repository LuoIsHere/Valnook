package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.model.*
import java.time.*

@Composable fun TradeForm(vm: TradeFormViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    val instruments by vm.availableInstruments.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded || state.failed) {
        Text(if (state.failed) "记录读取失败，请返回后重试" else "正在读取")
        return
    }
    val title = when (state.mode) {
        TradeFormMode.CREATE -> if (state.direction == Direction.BUY) "买入" else "卖出"
        TradeFormMode.EDIT -> "修改交易"
        TradeFormMode.DELETE -> "删除交易"
        TradeFormMode.OPENING -> "期初持仓"
        TradeFormMode.OPENING_COST -> "期初成本"
    }
    FormLayout(title, submission.phase == SubmissionPhase.WORKING, submission.phase != SubmissionPhase.SUCCEEDED,
        vm::submit, if (submission.phase == SubmissionPhase.UNKNOWN) "核对并重试" else if (state.mode == TradeFormMode.DELETE) "确认删除" else "保存") {
        if (state.mode == TradeFormMode.CREATE || state.mode == TradeFormMode.OPENING) {
            ChoiceField("投资品", state.instrumentId?.toString().orEmpty(), instruments.map { it.id.toString() to (it.name + " · " + it.symbol) },
                { id -> instruments.firstOrNull { it.id.toString() == id }?.let(vm::chooseInstrument) }, submission.editable)
        } else Text(state.name)
        if (state.mode == TradeFormMode.DELETE) Text("删除后将重算后续成本，并修正此交易原有的现金影响。")
        else {
            if (state.mode == TradeFormMode.EDIT) ChoiceField("交易方向", state.direction.name,
                listOf(Direction.BUY.name to "买入", Direction.SELL.name to "卖出"),
                { value -> vm.update { it.copy(direction = Direction.valueOf(value)) } }, submission.editable)
            if (state.mode != TradeFormMode.OPENING_COST) Field("份额", state.quantityInput,
                { value -> vm.update { it.copy(quantityInput = value) } }, true, submission.editable)
            Field(if (state.mode == TradeFormMode.OPENING || state.mode == TradeFormMode.OPENING_COST) "期初成本单价" else "成交单价",
                state.executionPriceInput, { value -> vm.update { it.copy(executionPriceInput = value) } }, true,
                submission.editable && !state.unknownOpeningCost)
            if (state.mode == TradeFormMode.OPENING) {
                Row { Checkbox(state.unknownOpeningCost, { value -> vm.update { it.copy(unknownOpeningCost = value) } },
                    enabled = submission.editable)
                    Text("成本未知，稍后补全") }
            }
            if (state.mode != TradeFormMode.OPENING_COST) {
                DateField("记账日期", state.occurredAt.toLocalDate().toString(), { value -> vm.update {
                    it.copy(occurredAt = LocalDateTime.of(LocalDate.parse(value), it.occurredAt.toLocalTime()))
                } }, submission.editable)
                TimeField("记账时间", state.occurredAt.toLocalTime().toString(), { value -> vm.update {
                    it.copy(occurredAt = LocalDateTime.of(it.occurredAt.toLocalDate(), LocalTime.parse(value)))
                } }, submission.editable)
            }
            if (state.mode == TradeFormMode.CREATE || state.mode == TradeFormMode.EDIT) {
                vm.amountPreview()?.let { Text("成交金额 " + it) }
                Row { Checkbox(state.cashLinked, { value -> vm.update { it.copy(cashLinked = value) } },
                    enabled = submission.editable)
                    Text("联动该账户 " + state.currency?.code.orEmpty() + " 现金") }
            }
        }
        if (state.mode == TradeFormMode.CREATE || state.mode == TradeFormMode.EDIT || state.mode == TradeFormMode.DELETE)
            vm.cashImpactPreview()?.let { Text("本次该账户现金变化 " + it) }
        if (submission.error == ErrorCode.HISTORY_CONFLICT)
            Text("修改会使历史持仓为负，或使交易早于期初持仓。请核对日期和份额。", color = MaterialTheme.colorScheme.error)
        else ErrorMessage(submission.error?.name)
        if (submission.phase == SubmissionPhase.UNKNOWN) Text("结果待核对，重试沿用原操作 ID")
    }
}
@Composable fun InstrumentEditScreen(vm: InstrumentEditViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    val types by vm.types.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded) { Text(if (state.loadFailed) "标的读取失败" else "正在读取")
        return }
    FormLayout("标的资料与当前价", submission.phase == SubmissionPhase.WORKING,
        submission.phase != SubmissionPhase.SUCCEEDED, vm::submit) {
        Field("名称", state.name, { value -> vm.update { it.copy(name = value) } }, enabled = submission.editable)
        Field("代码", state.symbol, { value -> vm.update { it.copy(symbol = value) } }, enabled = submission.editable)
        ChoiceField("资产类型", state.typeId?.toString().orEmpty(), types.map { it.id.toString() to it.name },
            { value -> vm.update { it.copy(typeId = value.toLong()) } }, submission.editable)
        CurrencyChoice(state.currency.code, { value -> vm.update {
            it.copy(currency = Currency.of(value), currencyPriceConfirmed = false)
        } }, submission.editable && !state.currencyLocked, Currency.supported.map { it.code to it.name })
        if (state.currencyLocked) Text("币种已锁定：任一账户已保存期初持仓或交易")
        Field("当前每份价格（最多5位小数）", state.priceInput,
            { value -> vm.update { it.copy(priceInput = value) } }, true, submission.editable)
        if (!state.currencyLocked && state.expectedRevision != null) Row {
            Checkbox(state.currencyPriceConfirmed, { value -> vm.update { it.copy(currencyPriceConfirmed = value) } },
                enabled = submission.editable)
            Text("已核对当前价格的币种含义")
        }
        when (submission.error) {
            ErrorCode.CURRENCY_LOCKED -> Text("币种已永久锁定，不能因清仓或删除交易而更改。", color = MaterialTheme.colorScheme.error)
            ErrorCode.PRICE_CONFIRMATION -> Text("更换币种后，请核对当前价格并勾选确认。", color = MaterialTheme.colorScheme.error)
            else -> ErrorMessage(submission.error?.name)
        }
        if (submission.phase == SubmissionPhase.UNKNOWN) Text("结果待核对，重试使用原操作")
    }
}
@Composable fun TypeEditScreen(vm: TypeEditViewModel, onBack: () -> Unit) {
    val name by vm.name.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    FormLayout("资产类型", submission.phase == SubmissionPhase.WORKING, submission.phase != SubmissionPhase.SUCCEEDED, vm::submit) {
        Field("名称", name, vm::updateName, enabled = submission.editable)
        ErrorMessage(submission.error?.name)
    }
}
