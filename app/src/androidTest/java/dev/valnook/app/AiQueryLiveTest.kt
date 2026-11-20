package dev.valnook.app

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.ai.*
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomBills
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.repository.RoomSettings
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.ai.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Real questions about bills, only with `-e liveAiQueries true`, against three months of synthetic bills in a database
 * in memory. Read from the app's cache and deleted once read: `ai_test_key`, `ai_live_connection` (base URL, model and
 * thinking-off parameters, one per line) and `ai_live_prompts.txt` (one question per line; "> " continues the previous
 * conversation, "[off] " asks with questions switched off). Reports each answer, its lookups and usage, and what the
 * app itself counts, never the key.
 */
class AiQueryLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun take(name: String): String {
        val file = File(context.cacheDir, name)
        return try { file.takeIf { it.isFile }?.readText().orEmpty() } finally { file.delete() }
    }

    private fun report(key: String, text: String) = instrumentation.sendStatus(0, Bundle().apply { putString(key, text) })

    @Test fun questions_are_answered_from_the_bills() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAiQueries") == "true")
        val key = take("ai_test_key").trim()
        val (baseUrl, model, thinkingOff) = take("ai_live_connection").lines().map { it.trim() }.let { Triple(it[0], it[1], it.getOrElse(2) { "" }) }
        val prompts = take("ai_live_prompts.txt").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (key.isEmpty() || prompts.isEmpty()) throw AssertionError("NO_KEY_OR_PROMPTS")

        val clock = Clock.systemDefaultZone()
        val zone = clock.zone
        val db = ValnookDatabase.inMemory(context)
        val chat = AiChatDatabase.inMemory(context)
        try {
            val commands = RoomFinancialCommands(db, clock)
            val settings = RoomSettings(db, clock)
            settings.applyChange(SaveFinancialSettings(0, Currency.of("CNY"), listOf(FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.2")))))
            suspend fun account(name: String, sub: String, currency: String) = commands.execute(SaveAccount(UUID.randomUUID().toString(),
                null, null, name, "", listOf(CashBalanceChange(currency, 10_000_000, null, name = sub)))).id
            account("合成银行", "储蓄卡", "CNY")
            account("合成美元户", "美元", "USD")
            val cash = RoomOverview(db).snapshot().cash
            val card = cash.single { it.name == "储蓄卡" }.id
            val dollars = cash.single { it.name == "美元" }.id
            fun category(key: String) = db.openHelper.readableDatabase.query("SELECT id FROM bill_categories WHERE builtin_key=?",
                arrayOf(key)).use { it.moveToFirst(); it.getLong(0) }
            val today = LocalDate.now(clock)
            val months = (0L..2L).map { YearMonth.from(today).minusMonths(it) }
            suspend fun bill(type: BillType, month: YearMonth, day: Int, amount: Long, sub: String, note: String,
                currency: String = "CNY", account: Long = card): Long {
                val at = month.atDay(day).atTime(12, 30).atZone(zone).toInstant().toEpochMilli()
                return commands.execute(SaveBill(UUID.randomUUID().toString(), null, null, BillDraft(type, at, amount, currency, 0,
                    account, amount, categoryId = category(sub.substringBeforeLast('.')), subCategoryId = category(sub), note = note))).id
            }
            // This month (days 1 to 10, all before the 20th).
            val now = months[0]
            bill(BillType.EXPENSE, now, 1, 3_500, "food.lunch", "合成午饭")
            bill(BillType.EXPENSE, now, 3, 4_200, "food.lunch", "合成午饭")
            bill(BillType.EXPENSE, now, 5, 12_800, "food.dinner", "合成聚餐")
            bill(BillType.EXPENSE, now, 5, 3_200, "transport.taxi", "合成打车")
            bill(BillType.EXPENSE, now, 8, 89_900, "shopping.electronics", "合成耳机")
            bill(BillType.EXPENSE, now, 9, 500, "food.drinks", "合成咖啡", "USD", dollars)
            bill(BillType.INCOME, now, 10, 1_500_000, "income.pay.salary", "合成工资")
            // Last month: a train ticket, a jacket partly refunded, meals.
            val last = months[1]
            bill(BillType.EXPENSE, last, 2, 6_000, "food.lunch", "合成午饭")
            bill(BillType.EXPENSE, last, 12, 55_300, "transport.rail", "合成高铁")
            val jacket = bill(BillType.EXPENSE, last, 12, 45_900, "shopping.clothing", "合成外套")
            bill(BillType.EXPENSE, last, 20, 30_000, "food.dinner", "合成生日宴")
            bill(BillType.INCOME, last, 10, 1_500_000, "income.pay.salary", "合成工资")
            val parent = RoomBills(db).observeBill(jacket).first()!!
            commands.execute(SettleBill(UUID.randomUUID().toString(), jacket, parent.revision, BillType.REFUND,
                SettlementDraft(last.atDay(15).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), 15_900, card, 15_900)))
            // Two months ago: rent and meals.
            val earlier = months[2]
            bill(BillType.EXPENSE, earlier, 1, 300_000, "housing.rent", "合成房租")
            bill(BillType.EXPENSE, earlier, 15, 8_800, "food.dinner", "合成晚饭")
            bill(BillType.INCOME, earlier, 10, 1_500_000, "income.pay.salary", "合成工资")

            val checks = MemoryAiCheckStore()
            val keys = MemoryAiKeyStore(key)
            val client = AiClient()
            val service = RoomAiService(db, keys, checks, clock, { false }, client)
            service.saveConnection(AiConnection(null, baseUrl, model, "", thinkingOff, ""), null)
            val connection = service.observeSettings().first().connection
            checks.save(AiCheckReport(connection.fingerprint, clock.millis(), mapOf(
                AiCheckItem.CONNECT to AiCheck(AiCheckState.PASSED), AiCheckItem.TOOLS to AiCheck(AiCheckState.PASSED))))
            service.setEnabled(true)
            val pictures = object : AiPictureSource, AiPicturePins {
                override suspend fun forModel(key: String) = ByteArray(0)
                override suspend fun thumbnail(key: String) = ByteArray(0)
                override fun pin(keys: Collection<String>) = Unit
                override suspend fun release(keys: Collection<String>) = Unit
            }
            val bills = RoomBills(db)
            val counter = BillCounter(bills, settings.observeSettings(), RoomOverview(db).observeFxContext(), zone)
            val queries = AiBillQueries(bills, counter, zone)
            val assistant = RoomAiAssistant(db, chat, service, keys, clock, { false }, pictures, pictures, client, queries)

            // What the app itself counts, to hold the answers against.
            val choices = service.observeChoices().first()
            val runner = AiQueryRunner(choices.categories, AiPrompt.sentAccounts(choices.accounts), english = false)
            months.forEach { month ->
                listOf(BillType.EXPENSE, BillType.INCOME).forEach { type ->
                    val call = AiQueryCall(true, type, month.atDay(1), month.atEndOfMonth(), AiQueryGroup.CATEGORY)
                    report("truth_${month}_$type", queries.run(call, runner).text)
                }
            }

            var conversation: Long? = null
            prompts.forEachIndexed { index, line ->
                val off = line.startsWith("[off] ")
                val follow = line.startsWith("> ")
                val text = line.removePrefix("[off] ").removePrefix("> ")
                service.saveQuery(!off)
                val started = System.currentTimeMillis()
                val id = assistant.send(if (follow) conversation else null, text, emptyList())
                conversation = id
                val reply = assistant.observeMessages(id).first().last()
                val lookups = reply.queries.joinToString("\n") { record ->
                    "  [${record.step}] " + (record.call?.let { c ->
                        listOfNotNull(if (c.summary) "summary" else "bills", c.type?.name, "${c.from}..${c.to}",
                            c.group.takeIf { c.summary }?.name, c.categoryId?.let { "cat=$it" }, c.accountId?.let { "acct=$it" },
                            c.keyword.takeIf { it.isNotEmpty() }?.let { "kw=$it" }, c.sort.takeIf { !c.summary }?.name,
                            "limit=${c.limit}".takeIf { !c.summary }).joinToString(" ")
                    } ?: "unreadable") + " -> " + (if (record.error) "ERROR" else "${record.rows} rows")
                }
                val drafts = reply.drafts.joinToString(" ") { "[${it.label}] ${it.draft.type} ${it.draft.amountMinor} ${it.draft.note}" }
                report("case_${index + 1}", "${if (off) "[关] " else ""}${if (follow) "（续）" else ""}$text\n→ ${reply.kind} " +
                    "${reply.problem ?: ""} ${(System.currentTimeMillis() - started) / 100 / 10.0}s 调用=${reply.usage?.calls} " +
                    "用量=${reply.usage?.total}\n  说：${reply.text}\n$lookups" + (if (drafts.isNotEmpty()) "\n  草稿：$drafts" else ""))
            }
        } finally {
            db.close()
            chat.close()
        }
    }
}
