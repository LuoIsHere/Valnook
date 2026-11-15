package dev.valnook.app

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.ai.*
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.repository.RoomSettings
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.ai.*
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.FxRate
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveBillFavorites
import dev.valnook.domain.repository.SaveFinancialSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/**
 * Real bookkeeping requests, only with `-e liveAiBills true`, against synthetic accounts in a database in memory.
 * Read from the app's cache and deleted once read: `ai_test_key` (the key), `ai_live_connection` (base URL, model and
 * thinking-off parameters, one per line) and `ai_live_prompts.txt` (one message per line; a line starting with "> "
 * continues the previous conversation). Reports each reply's drafts and usage, never the key.
 */
class AiBillsLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun take(name: String): String {
        val file = File(context.cacheDir, name)
        return try { file.takeIf { it.isFile }?.readText().orEmpty() } finally { file.delete() }
    }

    private fun report(key: String, text: String) = instrumentation.sendStatus(0, Bundle().apply { putString(key, text) })

    @Test fun messages_become_drafts() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAiBills") == "true")
        val key = take("ai_test_key").trim()
        val (baseUrl, model, thinkingOff) = take("ai_live_connection").lines().map { it.trim() }.let { Triple(it[0], it[1], it.getOrElse(2) { "" }) }
        val prompts = take("ai_live_prompts.txt").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (key.isEmpty() || prompts.isEmpty()) throw AssertionError("NO_KEY_OR_PROMPTS")

        val clock = Clock.systemDefaultZone()
        val db = ValnookDatabase.inMemory(context)
        val chat = AiChatDatabase.inMemory(context)
        try {
            val commands = RoomFinancialCommands(db, clock)
            RoomSettings(db, clock).applyChange(SaveFinancialSettings(0, Currency.of("CNY"), listOf(
                FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.2")),
                FxRate(Currency.of("HKD"), Currency.of("CNY"), BigDecimal("0.92")),
                FxRate(Currency.of("JPY"), Currency.of("CNY"), BigDecimal("0.048")))))
            suspend fun account(name: String, vararg subs: Triple<String, String, Long>) = commands.execute(SaveAccount(
                UUID.randomUUID().toString(), null, null, name, "", subs.map { (sub, currency, balance) ->
                    CashBalanceChange(currency, balance, null, name = sub) }))
            account("合成银行", Triple("储蓄卡", "CNY", 5_000_000))
            account("合成支付", Triple("余额", "CNY", 200_000))
            account("合成港卡", Triple("港币", "HKD", 1_000_000))
            account("合成美元户", Triple("美元", "USD", 300_000))
            account("合成证券", Triple("资金", "CNY", 2_000_000))
            account("现金", Triple("钱包", "CNY", 50_000))
            account("合成旧卡", Triple("闲置", "CNY", 0))
            val cash = RoomOverview(db).snapshot().cash
            commands.execute(SaveBillFavorites(UUID.randomUUID().toString(),
                listOf(cash.single { it.name == "储蓄卡" }.id, cash.single { it.name == "余额" }.id)))
            db.openHelper.writableDatabase.execSQL("UPDATE cash_accounts SET show_on_accounts_page=0 WHERE id=?",
                arrayOf(cash.single { it.name == "闲置" }.id))

            val checks = MemoryAiCheckStore()
            val keys = MemoryAiKeyStore(key)
            val client = AiClient()
            val service = RoomAiService(db, keys, checks, clock, { false }, client)
            service.saveConnection(AiConnection(null, baseUrl, model, "", thinkingOff, ""), null)
            val connection = service.observeSettings().first().connection
            checks.save(AiCheckReport(connection.fingerprint, clock.millis(), mapOf(
                AiCheckItem.CONNECT to AiCheck(AiCheckState.PASSED), AiCheckItem.TOOLS to AiCheck(AiCheckState.PASSED))))
            service.setEnabled(true)
            service.saveFeatures(billChat = true, shareText = true, shareImage = false, thinking = false, chat = false, sendHabits = false)
            val pictures = object : AiPictureSource, AiPicturePins {
                override suspend fun forModel(key: String) = ByteArray(0)
                override suspend fun thumbnail(key: String) = ByteArray(0)
                override fun pin(keys: Collection<String>) = Unit
                override suspend fun release(keys: Collection<String>) = Unit
            }
            val assistant = RoomAiAssistant(db, chat, service, keys, clock, { false }, pictures, pictures, client)

            // What the model is offered, by id, to read the drafts against.
            val system = AiJson.format.parseToJsonElement(service.promptPreview()).jsonObject["messages"]!!.jsonArray[0]
                .jsonObject["content"]!!.jsonPrimitive.content
            report("lists", system.substringAfter("Expense categories").substringBefore("Now:"))
            val choices = service.observeChoices().first()
            val accounts = choices.accounts.associate { it.id to it.name }
            val categories = choices.categories.associate { it.id to it.name(english = false) }

            var conversation: Long? = null
            prompts.forEachIndexed { index, line ->
                val follow = line.startsWith("> ")
                val text = line.removePrefix("> ")
                val started = System.currentTimeMillis()
                val id = assistant.send(if (follow) conversation else null, text, emptyList())
                conversation = id
                val reply = assistant.observeMessages(id).first().last()
                val drafts = reply.drafts.filter { it.state == AiDraftState.PENDING }.joinToString("\n") { card ->
                    val d = card.draft
                    listOfNotNull("  [${card.label}] ${d.type}",
                        d.amountMinor?.let { "金额=$it ${d.currencyCode}" } ?: "金额=缺",
                        d.toAmountMinor?.let { "到账=$it" },
                        d.discountMinor.takeIf { it != 0L }?.let { "优惠=$it" },
                        "分类=${listOfNotNull(d.categoryId, d.subCategoryId).joinToString("/") { categories[it] ?: "#$it" }}",
                        "账户=${d.cashAccountId?.let(accounts::get) ?: "-"}",
                        d.toCashAccountId?.let { "转入=${accounts[it]}" },
                        "时间=${d.occurredAt}",
                        d.note.takeIf { it.isNotEmpty() }?.let { "备注=$it" },
                        d.counterparty.takeIf { it.isNotEmpty() }?.let { "对方=$it" },
                        "报销=${d.reimbursable}".takeIf { d.reimbursable },
                        d.rate?.let { "汇率=${it.toPlainString()}" },
                        d.accountAmountMinor?.let { "账户金额=$it" },
                        d.flags.takeIf { it.isNotEmpty() }?.let { "标记=$it" }).joinToString(" ")
                }
                report("case_${index + 1}", "${if (follow) "（续）" else ""}$text\n→ ${reply.kind} ${reply.problem ?: ""} " +
                    "无草稿=${reply.noDrafts} 思考=${reply.reasoning?.length ?: 0}字 " +
                    "${(System.currentTimeMillis() - started) / 100 / 10.0}s 用量=${reply.usage?.total}\n  说：${reply.text}\n$drafts")
            }
        } finally {
            db.close()
            chat.close()
        }
    }
}
