package dev.valnook.data.ai

import dev.valnook.domain.ai.AiAccountChoice
import dev.valnook.domain.ai.AiQueryCall
import dev.valnook.domain.ai.AiQueryGroup
import dev.valnook.domain.ai.AiQuerySort
import dev.valnook.domain.ai.AiRules
import dev.valnook.domain.model.Bill
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillCategoryKind
import dev.valnook.domain.model.BillCounting
import dev.valnook.domain.model.BillType
import dev.valnook.domain.repository.BillCounter
import dev.valnook.domain.repository.BillRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The two bill query tools as the model sees them. Names and descriptions are short English to save tokens. */
object AiQueryTools {
    const val SUMMARY = "query_summary"
    const val BILLS = "query_bills"
    val names = setOf(SUMMARY, BILLS)

    private fun prop(type: String, description: String? = null, values: List<String>? = null) = JsonObject(buildMap {
        put("type", JsonPrimitive(type))
        description?.let { put("description", JsonPrimitive(it)) }
        values?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
    })

    private fun tool(name: String, description: String, properties: Map<String, JsonObject>, required: List<String>) =
        JsonObject(mapOf("type" to JsonPrimitive("function"), "function" to JsonObject(mapOf(
            "name" to JsonPrimitive(name), "description" to JsonPrimitive(description),
            "parameters" to JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(properties),
                "required" to JsonArray(required.map(::JsonPrimitive))))))))

    private val range = linkedMapOf(
        "from" to prop("string", "First day, YYYY-MM-DD"),
        "to" to prop("string", "Last day, YYYY-MM-DD; at most ${AiRules.QUERY_MONTHS} months after from"),
    )
    private val filters = linkedMapOf(
        "category_id" to prop("integer", "A category or sub-category id"),
        "account_id" to prop("integer"),
        "keyword" to prop("string", "Text in the bill's note"),
    )

    val summaryTool: JsonObject = tool(SUMMARY,
        "Sum the user's expense or income in the base currency as the app's statistics count it, optionally grouped. " +
            "Up to ${AiRules.QUERY_SUMMARY_ROWS} rows.",
        linkedMapOf("bill_type" to prop("string", values = listOf("EXPENSE", "INCOME"))) + range +
            ("group_by" to prop("string", values = AiQueryGroup.entries.map { it.name })) + filters,
        listOf("bill_type", "from", "to"))

    val billsTool: JsonObject = tool(BILLS,
        "List the user's expense and income bills with date, category, amount and note. Up to ${AiRules.QUERY_BILL_ROWS}.",
        range + linkedMapOf("bill_type" to prop("string", values = listOf("EXPENSE", "INCOME"))) + filters +
            linkedMapOf("sort" to prop("string", values = AiQuerySort.entries.map { it.name }),
                "limit" to prop("integer", "1 to ${AiRules.QUERY_BILL_ROWS}")),
        listOf("from", "to"))
}

/** An argument the model gave that cannot be used; its message goes back to the model to correct. */
class AiQueryError(message: String) : IllegalArgumentException(message)

/**
 * Reads a query call's arguments against what the request sent. Unknown values are errors for the model to correct;
 * a range past [AiRules.QUERY_MONTHS] is cut to its last months, and dates the wrong way round are swapped.
 */
class AiQueryReader(private val categories: List<BillCategory>, accounts: List<AiAccountChoice>) {
    private val categoryById = categories.associateBy { it.id }
    private val sentAccounts = accounts.map { it.id }.toSet()

    fun read(name: String, arguments: String): AiQueryCall {
        val root = runCatching { AiJson.format.parseToJsonElement(arguments.ifBlank { "{}" }) }.getOrNull() as? JsonObject
            ?: throw AiQueryError("Arguments are not a JSON object.")
        fun text(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun id(key: String): Long? {
            val value = text(key) ?: return null
            return value.toLongOrNull() ?: value.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toLong()
                ?: throw AiQueryError("$key must be a number.")
        }
        fun <T : Enum<T>> choice(key: String, values: Array<T>): T? = text(key)?.let { value ->
            values.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw AiQueryError("$key must be one of ${values.joinToString { it.name }}.")
        }
        val summary = when (name) {
            AiQueryTools.SUMMARY -> true
            AiQueryTools.BILLS -> false
            else -> throw AiQueryError("Unknown tool $name.")
        }
        val type = choice("bill_type", BillType.entries.filter { it == BillType.EXPENSE || it == BillType.INCOME }.toTypedArray())
        if (summary && type == null) throw AiQueryError("bill_type is required: EXPENSE or INCOME.")
        var from = date(text("from"), "from", first = true)
        var to = date(text("to"), "to", first = false)
        if (from > to) from = to.also { to = from }
        val earliest = to.plusDays(1).minusMonths(AiRules.QUERY_MONTHS)
        val shortened = from < earliest
        if (shortened) from = earliest
        val categoryId = id("category_id")?.also { id ->
            val category = categoryById[id] ?: throw AiQueryError("category_id $id is not a listed category.")
            val kind = if (type == BillType.INCOME) BillCategoryKind.INCOME else BillCategoryKind.EXPENSE
            if (type != null && category.kind != kind) throw AiQueryError("category_id $id is not a${if (type == BillType.INCOME) "n income" else "n expense"} category.")
        }
        val accountId = id("account_id")?.also { id ->
            if (id !in sentAccounts) throw AiQueryError("account_id $id is not a listed account.")
        }
        val keyword = text("keyword").orEmpty().take(AiRules.QUERY_KEYWORD_MAX)
        return if (summary) AiQueryCall(true, type, from, to, choice("group_by", AiQueryGroup.entries.toTypedArray())
            ?: AiQueryGroup.NONE, categoryId, accountId, keyword, shortened = shortened)
        else {
            val limit = text("limit")?.let { it.toIntOrNull() ?: throw AiQueryError("limit must be a number.") }
                ?.coerceIn(1, AiRules.QUERY_BILL_ROWS) ?: AiRules.QUERY_BILL_ROWS
            AiQueryCall(false, type, from, to, AiQueryGroup.NONE, categoryId, accountId, keyword,
                choice("sort", AiQuerySort.entries.toTypedArray()) ?: AiQuerySort.TIME_DESC, limit, shortened)
        }
    }

    /** A day, or a month standing for its first or last day. */
    private fun date(value: String?, key: String, first: Boolean): LocalDate {
        value ?: throw AiQueryError("$key is required, as YYYY-MM-DD.")
        runCatching { return LocalDate.parse(value.take(10)) }
        runCatching { YearMonth.parse(value.take(7)).let { return if (first) it.atDay(1) else it.atEndOfMonth() } }
        throw AiQueryError("$key must be a date, YYYY-MM-DD.")
    }
}

/** What a query gave the model: [text] goes back as the tool result; [rows] is how many rows or bills it listed. */
data class AiQueryResult(val text: String, val rows: Int)

/**
 * Runs checked queries on bills counted as statistics count them ([BillCounting]), so every number agrees with the
 * statistics page. [accounts] are the accounts the request sent: bills of any other are grouped as other accounts and
 * their names are never given.
 */
class AiQueryRunner(categories: List<BillCategory>, accounts: List<AiAccountChoice>, private val english: Boolean) {
    private val categoryById = categories.associateBy { it.id }
    private val accountById = accounts.associateBy { it.id }

    fun run(call: AiQueryCall, counting: BillCounting): AiQueryResult {
        val base = counting.base ?: return AiQueryResult("No base currency is set in the app, so amounts cannot be counted.", 0)
        val matching = counting.bills.filter { bill ->
            counting.counts(bill) && (call.type == null || bill.type == call.type) &&
                counting.dateOf(bill).let { it >= call.from && it <= call.to } &&
                (call.categoryId == null || bill.categoryId == call.categoryId || bill.subCategoryId == call.categoryId) &&
                (call.accountId == null || bill.cashAccountId == call.accountId) &&
                (call.keyword.isEmpty() || bill.note.contains(call.keyword, ignoreCase = true))
        }
        val notes = buildList {
            if (call.shortened) add("The range was cut to ${AiRules.QUERY_MONTHS} months: ${call.from} to ${call.to}.")
            val missing = matching.count { counting.valueOf(it) == null }
            if (missing > 0) add("$missing bill(s) left out: no exchange rate to ${base.code} for their day.")
        }
        val digits = base.fraction_digits
        fun amount(value: BigDecimal) = value.setScale(digits, RoundingMode.HALF_UP).toPlainString()
        return if (call.summary) summary(call, counting, matching, base.code, notes, ::amount)
        else bills(call, counting, matching, base.code, notes, ::amount)
    }

    private fun summary(call: AiQueryCall, counting: BillCounting, matching: List<Bill>, base: String,
        notes: List<String>, amount: (BigDecimal) -> String): AiQueryResult {
        // Like the statistics page, a bill that came to nothing after refunds is not spending and is not counted.
        val valued = matching.mapNotNull { bill -> counting.valueOf(bill)?.takeIf { it.signum() > 0 }?.let { bill to it } }
        val head = "${call.type} ${call.from} to ${call.to}" + filterText(call) +
            ", in $base at each bill's own day's rate, net of refunds."
        if (valued.isEmpty()) return AiQueryResult((listOf(head, "No bills match.") + notes).joinToString("\n"), 0)
        val total = valued.fold(BigDecimal.ZERO) { sum, (_, value) -> sum + value }
        val lines = mutableListOf(head, "Total: ${amount(total)} (${valued.size} bills)")
        if (call.group == AiQueryGroup.NONE) return AiQueryResult((lines + notes).joinToString("\n"), 1)
        val groups = valued.groupBy { (bill, _) -> groupKey(call.group, bill, counting) }
            .map { (key, rows) -> Triple(key, rows.fold(BigDecimal.ZERO) { sum, (_, value) -> sum + value }, rows.size) }
            .sortedWith(when (call.group) {
                AiQueryGroup.MONTH, AiQueryGroup.DAY -> compareBy { it.first.order }
                AiQueryGroup.MONTH_CATEGORY -> compareBy<Triple<GroupKey, BigDecimal, Int>> { it.first.order }.thenByDescending { it.second }
                else -> compareByDescending<Triple<GroupKey, BigDecimal, Int>> { it.second }.thenBy { it.first.label }
            })
        val shown = groups.take(AiRules.QUERY_SUMMARY_ROWS)
        lines += "By ${call.group.name.lowercase()} (group | amount | bills):"
        shown.forEach { (key, value, count) -> lines += "${key.label} | ${amount(value)} | $count" }
        if (groups.size > shown.size) lines += "${groups.size - shown.size} more rows not shown."
        return AiQueryResult((lines + notes).joinToString("\n"), shown.size)
    }

    private fun bills(call: AiQueryCall, counting: BillCounting, matching: List<Bill>, base: String,
        notes: List<String>, amount: (BigDecimal) -> String): AiQueryResult {
        val sorted = when (call.sort) {
            AiQuerySort.TIME_DESC -> matching.sortedWith(compareByDescending<Bill> { it.occurredAtMs }.thenByDescending { it.id })
            AiQuerySort.AMOUNT_DESC -> matching.sortedWith(compareByDescending<Bill, BigDecimal?>(nullsFirst()) { counting.valueOf(it) }
                .thenByDescending { it.occurredAtMs })
        }
        val shown = sorted.take(call.limit)
        val head = "Bills ${call.type ?: "EXPENSE and INCOME"} ${call.from} to ${call.to}" + filterText(call) +
            ", ${matching.size} match" + (if (shown.size < matching.size) ", showing ${shown.size}" else "") +
            " (${if (call.sort == AiQuerySort.AMOUNT_DESC) "largest" else "newest"} first)."
        if (shown.isEmpty()) return AiQueryResult((listOf(head, "No bills match.") + notes).joinToString("\n"), 0)
        val lines = mutableListOf(head, "time | type | category | amount paid | in $base, net of refunds | note")
        shown.forEach { bill ->
            val time = Instant.ofEpochMilli(bill.occurredAtMs).atZone(counting.zone).toLocalDateTime().format(TIME)
            val paid = BigDecimal.valueOf(bill.paidMinor, bill.currency.fraction_digits).toPlainString() + " " + bill.currency.code
            val value = counting.valueOf(bill)?.let(amount) ?: "no rate"
            val note = bill.note.replace(Regex("\\s+"), " ").trim().take(NOTE_MAX)
            lines += "$time | ${bill.type} | ${categoryText(bill)} | $paid | $value | $note"
        }
        return AiQueryResult((lines + notes).joinToString("\n"), shown.size)
    }

    private fun filterText(call: AiQueryCall) = buildString {
        call.categoryId?.let { append(", category ").append(categoryLabel(it)) }
        call.accountId?.let { append(", account ").append(accountLabel(it)) }
        if (call.keyword.isNotEmpty()) append(", note contains \"").append(call.keyword).append('"')
    }

    /** A group's name for the model and where it sorts: by time for months and days. */
    private data class GroupKey(val label: String, val order: String)

    private fun groupKey(group: AiQueryGroup, bill: Bill, counting: BillCounting): GroupKey {
        val day = counting.dateOf(bill)
        val month = YearMonth.from(day).toString()
        return when (group) {
            AiQueryGroup.NONE -> GroupKey("all", "")
            AiQueryGroup.CATEGORY -> GroupKey(bill.categoryId?.let(::categoryLabel) ?: "uncategorized", "")
            AiQueryGroup.SUB_CATEGORY -> GroupKey(bill.subCategoryId?.let(::categoryLabel)
                ?: ((bill.categoryId?.let(::categoryLabel) ?: "uncategorized") + " (no sub-category)"), "")
            AiQueryGroup.MONTH -> GroupKey(month, month)
            AiQueryGroup.MONTH_CATEGORY -> GroupKey("$month " + (bill.categoryId?.let(::categoryLabel) ?: "uncategorized"), month)
            AiQueryGroup.DAY -> GroupKey(day.toString(), day.toString())
            AiQueryGroup.ACCOUNT -> GroupKey(when (val id = bill.cashAccountId) {
                null -> "no account"
                else -> accountById[id]?.let { "${it.id} ${it.name}" } ?: "other accounts"
            }, "")
        }
    }

    private fun categoryText(bill: Bill): String =
        listOfNotNull(bill.categoryId?.let(::categoryLabel), bill.subCategoryId?.let(::categoryLabel))
            .joinToString(" / ").ifEmpty { "uncategorized" }

    private fun categoryLabel(id: Long) = categoryById[id]?.let { "$id ${it.name(english)}" } ?: "$id"

    private fun accountLabel(id: Long) = accountById[id]?.let { "$id ${it.name}" } ?: "$id"

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        const val NOTE_MAX = 60
    }
}

/** Runs a query on the bills it covers, read when it runs, at the rates statistics use. */
class AiBillQueries(private val bills: BillRepository, private val counter: BillCounter, private val zone: ZoneId) {
    suspend fun run(call: AiQueryCall, runner: AiQueryRunner): AiQueryResult {
        val list = bills.observeRange(call.from, call.to.plusDays(1), zone).first()
        return runner.run(call, counter.observe(list).first())
    }
}
