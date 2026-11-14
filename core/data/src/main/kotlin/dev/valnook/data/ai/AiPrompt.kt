package dev.valnook.data.ai

import dev.valnook.domain.ai.AiAccountChoice
import dev.valnook.domain.ai.AiRules
import dev.valnook.domain.ai.AiSettings
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillCategoryKind
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * What every request starts with, in a fixed order so services that cache a repeated beginning can: tools, the fixed
 * rules, the user's rules, categories and accounts, then the time, which changes on every request, last.
 */
object AiPrompt {
    /** Raised whenever the fixed rules change. */
    const val RULES_VERSION = 1

    /** The bill draft tool: the only one in bill requests. Names and descriptions are short English to save tokens. */
    val billTool: JsonObject = tool("create_bill_drafts",
        "Create bill drafts for the user to confirm in the app. Pass draft_id to replace an unconfirmed draft.",
        obj(
            "message" to prop("string", "Reply or question shown to the user"),
            "bills" to JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to obj(
                    "draft_id" to prop("string", "Id of an unconfirmed draft to replace"),
                    "type" to JsonObject(mapOf("type" to JsonPrimitive("string"),
                        "enum" to JsonArray(listOf("EXPENSE", "INCOME", "TRANSFER").map(::JsonPrimitive)))),
                    "amount" to prop("string", "Decimal amount paid, e.g. \"35.50\""),
                    "currency" to prop("string", "ISO 4217 code"),
                    "discount" to prop("string", "Decimal discount, if any"),
                    "category_id" to prop("integer", null),
                    "sub_category_id" to prop("integer", null),
                    "account_id" to prop("integer", null),
                    "to_account_id" to prop("integer", "Transfer destination"),
                    "occurred_at" to prop("string", "Local YYYY-MM-DDTHH:MM, or YYYY-MM-DD without a time"),
                    "note" to prop("string", null),
                    "counterparty" to prop("string", "Who pays back, or the other side of a transfer"),
                    "reimbursable" to prop("boolean", "Someone is to pay this expense back"),
                ),
                "required" to JsonArray(listOf(JsonPrimitive("type"))),
            ))))),
        listOf("bills"))

    /** The system message: everything before the conversation. */
    fun system(settings: AiSettings, categories: List<BillCategory>, accounts: List<AiAccountChoice>,
        now: LocalDateTime, baseCurrency: String?, english: Boolean): String = buildString {
        append(fixedRules(settings, english))
        personal(settings)?.let { append("\n\n").append(it) }
        append("\n\n").append(categoryList(categories, english))
        append("\n\n").append(accountList(accounts))
        append("\n\n").append(time(now, baseCurrency))
    }

    /** The request body of a bill request before the model and connection parameters. */
    fun billRequest(system: String, conversation: List<JsonObject>, thinking: Boolean): JsonObject = JsonObject(mapOf(
        "messages" to JsonArray(listOf(message("system", system)) + conversation),
        "tools" to JsonArray(listOf(billTool)),
        "max_tokens" to JsonPrimitive(if (thinking) AiRules.MAX_TOKENS_THINKING else AiRules.MAX_TOKENS_BILLS),
    ))

    fun message(role: String, content: String) = JsonObject(mapOf("role" to JsonPrimitive(role), "content" to JsonPrimitive(content)))

    internal fun fixedRules(settings: AiSettings, english: Boolean): String = listOf(
        "You are the bookkeeping assistant in Valnook, a personal finance app. Rules v$RULES_VERSION:",
        "- Turn what the user says or shows into bill drafts by calling create_bill_drafts. You never save anything; the user confirms every draft in the app.",
        "- Use only the category and account ids listed below. Leave out a field you are unsure of. Never invent amounts; leave unreadable parts out.",
        "- Amounts are decimal strings in the currency paid. Never convert currencies or add amounts up; the app does that.",
        "- Do not create refunds or reimbursement payments.",
        "- occurred_at: local \"YYYY-MM-DDTHH:MM\"; only \"YYYY-MM-DD\" when a day but no time was said; leave it out when nothing about time was said.",
        "- When something needed is missing, such as the amount, ask in message, or make a draft without it.",
        "- Never give investment or financial advice.",
        "- You cannot look up the user's bills, balances, investments or cards. If asked about their bills, say that turning on \"${if (english) "Ask about bills" else "对话查询"}\" in Settings lets you answer; other data cannot be asked about.",
        "- Write plain text, without Markdown.",
        "- Reply in ${if (english) "English" else "Simplified Chinese"}.",
        if (settings.chat) "- You may chat naturally and say a little more, staying friendly and concise."
        else "- Keep replies to one or two sentences. For small talk, answer briefly and steer back to bookkeeping.",
    ).joinToString("\n")

    /** Nicknames and the user's own rules; null when there are none. */
    internal fun personal(settings: AiSettings): String? {
        val lines = listOfNotNull(
            settings.userNickname.takeIf { it.isNotBlank() }?.let { "Call the user \"$it\"." },
            settings.aiNickname.takeIf { it.isNotBlank() }?.let { "Your name is \"$it\"." },
            settings.myRules.trim().takeIf { it.isNotEmpty() },
        )
        if (lines.isEmpty()) return null
        return "The user's own rules (they come first, except where they conflict with drafts, confirmation or advice above):\n" +
            lines.joinToString("\n")
    }

    /** Shown categories, by id: "12 Food: 121 Breakfast, 122 Lunch". */
    internal fun categoryList(categories: List<BillCategory>, english: Boolean): String {
        val shown = categories.filter { !it.hidden }
        val children = shown.filter { it.parentId != null }.groupBy { it.parentId }
        fun section(kind: BillCategoryKind, title: String) = title + "\n" + shown
            .filter { it.kind == kind && it.parentId == null }.sortedBy { it.id }
            .joinToString("\n") { parent ->
                "${parent.id} ${parent.name(english)}" + children[parent.id].orEmpty().sortedBy { it.id }
                    .takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = ": ") { "${it.id} ${it.name(english)}" }.orEmpty()
            }
        return section(BillCategoryKind.EXPENSE, "Expense categories (id name: sub-categories):") + "\n\n" +
            section(BillCategoryKind.INCOME, "Income categories:")
    }

    /** The favourite accounts, or all when none is a favourite: id, name and currency only. */
    internal fun accountList(accounts: List<AiAccountChoice>): String {
        val sent = accounts.filter { it.favorite }.ifEmpty { accounts }.sortedBy { it.id }
        if (sent.isEmpty()) return "Accounts: none."
        return "Accounts (id name currency):\n" + sent.joinToString("\n") { "${it.id} ${it.name} ${it.currencyCode}" }
    }

    internal fun time(now: LocalDateTime, baseCurrency: String?): String =
        "Now: ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}, " +
            now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "." +
            (baseCurrency?.let { " Base currency: $it." } ?: "")

    private fun tool(name: String, description: String, properties: JsonObject, required: List<String>) = JsonObject(mapOf(
        "type" to JsonPrimitive("function"),
        "function" to JsonObject(mapOf(
            "name" to JsonPrimitive(name), "description" to JsonPrimitive(description),
            "parameters" to JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to properties,
                "required" to JsonArray(required.map(::JsonPrimitive)))),
        )),
    ))

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))

    private fun prop(type: String, description: String?) = JsonObject(buildMap {
        put("type", JsonPrimitive(type))
        description?.let { put("description", JsonPrimitive(it)) }
    })
}
