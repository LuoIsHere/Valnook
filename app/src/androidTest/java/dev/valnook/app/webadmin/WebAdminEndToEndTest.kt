package dev.valnook.app.webadmin

import android.util.Log
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import dev.valnook.app.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppSessionManager
import dev.valnook.app.di.DataMode
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.data.transaction.TransactionPoint
import dev.valnook.domain.repository.*
import dev.valnook.domain.model.*
import dev.valnook.domain.webadmin.WebAdminPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.inject.Inject

@HiltAndroidTest
class WebAdminEndToEndTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var web: AppWebAdminCoordinator
    @Inject lateinit var database: ValnookDatabase
    @Inject lateinit var sessions: AppSessionManager

    @Before fun inject() = hilt.inject()
    @After fun stop() = runBlocking {
        step("cleanup")
        web.stop()
        if (sessions.session.value.mode == DataMode.DEMO) sessions.exitDemo()
    }

    @Test fun batch_price_transaction_rolls_back_and_retries_without_changing_costs() = runBlocking<Unit> {
        val clock = java.time.Clock.systemUTC()
        val commands = RoomFinancialCommands(database, clock)
        fun id() = UUID.randomUUID().toString()
        val type = commands.execute(SaveAssetType(id(), null, "Stocks")).id
        val a = commands.execute(SaveInstrument(id(), null, null, "Apple", "AAPL", type, "USD", 10_000_000)).id
        val b = commands.execute(SaveInstrument(id(), null, null, "Tencent", "00700", type, "HKD", 40_000_000)).id
        repeat(2) { index ->
            val account = commands.execute(SaveAccount(id(), null, null, "Synthetic $index", "", emptyList())).id
            val position = commands.execute(CreateInvestmentPosition(id(), account, a)).id
            commands.execute(RecordInvestmentTrade(id(), position, Direction.BUY, 200_000_000, 10_000_000_000,
                clock.millis(), false, fee_minor = 150))
        }
        val before = RoomOverview(database).snapshot()
        val generation = database.audit().generation()
        fun historyCount() = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM instrument_price_history").use {
            it.moveToFirst(); it.getLong(0)
        }
        val pricesBefore = historyCount()
        val update = UpdateInstrumentPrices(id(), listOf(InstrumentPriceChange(a, 1, 11_123_456), InstrumentPriceChange(b, 1, 0)))
        val faulty = RoomFinancialCommands(database, clock) { if (it == TransactionPoint.AFTER_BUSINESS) error("Injected failure") }
        assertTrue(runCatching { faulty.execute(update) }.isFailure)
        assertEquals(before.instruments, RoomOverview(database).snapshot().instruments)
        assertEquals(generation, database.audit().generation())
        assertEquals(pricesBefore, historyCount())
        assertEquals(null, commands.operationResult(update.operation_id))
        suspend fun rejected(command: UpdateInstrumentPrices, code: ErrorCode) {
            val error = runCatching { commands.execute(command) }.exceptionOrNull()
            assertTrue(error is DomainException)
            assertEquals(code, (error as DomainException).code)
            assertEquals(generation, database.audit().generation())
            assertEquals(pricesBefore, historyCount())
        }
        rejected(update.copy(operation_id = id(), changes = update.changes.map {
            if (it.instrumentId == b) it.copy(expectedRevision = 99) else it }), ErrorCode.STALE_RECORD)
        rejected(update.copy(operation_id = id(), changes = listOf(update.changes[0], update.changes[0])), ErrorCode.FORMAT)
        rejected(update.copy(operation_id = id(), changes = emptyList()), ErrorCode.FORMAT)
        rejected(update.copy(operation_id = id(), changes = listOf(InstrumentPriceChange(a, 1, -1))), ErrorCode.POSITIVE)
        rejected(update.copy(operation_id = id(), changes = listOf(InstrumentPriceChange(a, 1, Long.MAX_VALUE))), ErrorCode.OVERFLOW)
        rejected(update.copy(operation_id = id(), changes = listOf(InstrumentPriceChange(Long.MAX_VALUE, 1, 0))), ErrorCode.NOT_FOUND)
        val receipt = commands.execute(update, CommandSource.WEB_ADMIN)
        assertEquals(receipt, commands.execute(update.copy(changes = update.changes.reversed()), CommandSource.WEB_ADMIN))
        assertEquals(generation + 1, database.audit().generation())
        assertEquals(pricesBefore + 2, historyCount())
        val after = RoomOverview(database).snapshot()
        assertEquals(11_123_456L, after.instruments.single { it.id == a }.currentPriceE5)
        assertEquals(0L, after.instruments.single { it.id == b }.currentPriceE5)
        assertEquals(before.cash, after.cash)
        after.positions.forEach { position ->
            val old = before.positions.single { it.id == position.id }
            val oldProfit = dev.valnook.domain.calculation.InvestmentProfitCalculator.fromReadModel(old)
            val profit = dev.valnook.domain.calculation.InvestmentProfitCalculator.fromReadModel(position)
            assertEquals(oldProfit.average_cost, profit.average_cost)
            assertEquals(oldProfit.realized, profit.realized)
            assertNotEquals(oldProfit.unrealized, profit.unrealized)
        }
        val audit = requireNotNull(database.audit().eventForOperation(update.operation_id))
        assertEquals("WEB_ADMIN", audit.source)
        assertTrue(audit.before_json!!.contains("10000000"))
        assertTrue(audit.after_json!!.contains("11123456"))
        val conflict = runCatching { commands.execute(update.copy(changes = listOf(InstrumentPriceChange(a, 2, 1)))) }.exceptionOrNull()
        assertEquals(ErrorCode.OPERATION_CONFLICT, (conflict as DomainException).code)
        commands.execute(UpdateInstrumentPrices(id(), listOf(InstrumentPriceChange(a, 2, 11_123_456))))
        assertEquals(pricesBefore + 2, historyCount())
        assertEquals(2L, RoomOverview(database).snapshot().instruments.single { it.id == a }.revision)
    }

    @Test fun batch_price_endpoint_validates_and_exposes_recoverable_receipt() = runBlocking<Unit> {
        val commands = RoomFinancialCommands(database, java.time.Clock.systemUTC())
        val type = commands.execute(SaveAssetType(UUID.randomUUID().toString(), null, "Stocks")).id
        val ids = listOf("Apple", "Tencent").map {
            commands.execute(SaveInstrument(UUID.randomUUID().toString(), null, null, it, it, type, "USD", 1_000_000)).id
        }
        web.start()
        val endpoint = URI(requireNotNull(web.state.value.url))
        val origin = "http://${endpoint.host}:${endpoint.port}"
        val paired = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = JSONObject().put("code", web.state.value.pairingCode).toString())
        assertEquals(200, paired.status)
        val cookie = paired.headers["set-cookie"].orEmpty().substringBefore(';')
        val csrf = JSONObject(paired.body).getString("csrfToken")
        openWebSocket(endpoint, origin, cookie).use { socket ->
            awaitPhase(WebAdminPhase.ACTIVE)
            listOf("decimal", "price-editor", "investment-groups").forEach {
                assertEquals(200, http(endpoint, "GET", "/$it.js").status)
            }
            val generation = database.audit().generation()
            fun payload(price: String, revision: Long = 1) = JSONObject()
                .put("operationId", UUID.randomUUID().toString()).put("dataGeneration", generation)
                .put("changes", org.json.JSONArray(ids.mapIndexed { index, id ->
                    JSONObject().put("instrumentId", id).put("expectedRevision", if (index == 0) 1 else revision)
                        .put("price", if (index == 0) "20.12345" else price)
                }))
            fun send(body: JSONObject) = http(endpoint, "PUT", "/api/v1/instrument-prices",
                origin = origin, cookie = cookie, csrf = csrf, body = body.toString())
            assertEquals(401, http(endpoint, "PUT", "/api/v1/instrument-prices", origin = origin, body = "{}").status)
            val invalid = send(payload("1.123456"))
            assertEquals(400, invalid.status)
            assertTrue(invalid.body.contains("PRECISION"))
            assertEquals(409, send(payload("2", 99)).status)
            assertEquals(generation, database.audit().generation())
            val body = payload("0")
            val saved = send(body)
            assertEquals(saved.body, 200, saved.status)
            val committed = database.audit().generation()
            sendMaskedText(socket, "{\"type\":\"heartbeat\"}")
            // A repeated HTTP write may hit the generation gate: the operation lookup resolves it.
            assertEquals(409, send(body).status)
            val receipt = http(endpoint, "GET", "/api/v1/operations/${body.getString("operationId")}", cookie = cookie)
            assertEquals(receipt.body, 200, receipt.status)
            assertEquals(committed, database.audit().generation())
            assertEquals(listOf(20_12345L, 0L), RoomOverview(database).snapshot().instruments.map { it.currentPriceE5 })
        }
    }

    @Test fun pairing_security_websocket_write_and_revocation_work_over_real_server() = runBlocking<Unit> {
        step("start")
        web.start()
        val waiting = web.state.value
        assertEquals(waiting.error?.name, WebAdminPhase.WAITING, waiting.phase)
        val endpoint = URI(requireNotNull(waiting.url))
        val origin = "http://${endpoint.host}:${endpoint.port}"

        step("static-security")
        val index = http(endpoint, "GET", "/")
        assertEquals(200, index.status)
        assertTrue(index.headers["content-security-policy"].orEmpty().contains("connect-src 'self' ws://${endpoint.host}:${endpoint.port}"))
        assertFalse(index.body.contains("https://"))
        assertEquals("DENY", index.headers["x-frame-options"])
        assertEquals("nosniff", index.headers["x-content-type-options"])
        listOf("core", "session", "pages", "icons", "symbols").forEach {
            val asset = http(endpoint, "GET", "/$it.js")
            assertEquals(200, asset.status)
            assertTrue(asset.headers["content-type"].orEmpty().contains("javascript"))
        }
        assertEquals(401, http(endpoint, "GET", "/api/v1/account-icons/" + "a".repeat(64)).status)

        assertEquals(401, http(endpoint, "GET", "/api/v1/accounts").status)
        assertEquals(401, http(endpoint, "POST", "/api/v1/accounts", origin = origin, body = "{}").status)
        assertEquals(400, http(endpoint, "GET", "/", host = "evil.example").status)
        assertEquals(403, http(endpoint, "POST", "/api/v1/pair/code",
            body = "{\"code\":\"000000\"}").status)
        assertEquals(400, http(endpoint, "POST", "/api/v1/pair/code", origin = origin, body = "{").status)

        step("pairing")
        val firstCode = requireNotNull(web.state.value.pairingCode)
        val wrongCode = if (firstCode == "000000") "000001" else "000000"
        val wrong = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = "{\"code\":\"$wrongCode\"}")
        assertEquals(401, wrong.status)
        assertTrue(wrong.body.contains("PAIR_CODE_ROTATED"))
        val currentCode = requireNotNull(web.state.value.pairingCode)
        assertNotEquals(firstCode, currentCode)

        val paired = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = "{\"code\":\"$currentCode\"}")
        assertEquals(200, paired.status)
        val cookie = paired.headers["set-cookie"].orEmpty().substringBefore(';')
        assertTrue(paired.headers["set-cookie"].orEmpty().contains("HttpOnly"))
        assertTrue(paired.headers["set-cookie"].orEmpty().contains("SameSite=Lax"))
        val csrf = JSONObject(paired.body).getString("csrfToken")

        step("websocket")
        val socket = openWebSocket(endpoint, origin, cookie)
        awaitPhase(WebAdminPhase.ACTIVE)
        sendMaskedText(socket, "{\"type\":\"heartbeat\"}")
        assertEquals(409, http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = "{\"code\":\"123456\"}").status)

        step("write")
        val session = http(endpoint, "GET", "/api/v1/session", cookie = cookie)
        assertEquals(200, session.status)
        assertEquals("REAL", JSONObject(session.body).getString("dataMode"))
        assertEquals(200, http(endpoint, "GET",
            "/api/v1/statistics/history?granularity=DAILY&metric=TOTAL_ASSETS", cookie = cookie).status)
        val generation = JSONObject(session.body).getLong("dataGeneration")
        val operationId = UUID.randomUUID().toString()
        val createBody = JSONObject().put("operationId", operationId).put("dataGeneration", generation)
            .put("expectedRevision", JSONObject.NULL).put("name", "<script>alert(1)</script>")
            .put("note", "<img src=x onerror=alert(1)>").put("cashChanges", org.json.JSONArray())
            .put("iconChange", JSONObject().put("type", "SYMBOL").put("value", "savings")).toString()
        assertEquals(401, http(endpoint, "POST", "/api/v1/accounts", origin = origin,
            cookie = cookie, body = createBody).status)
        val saved = http(endpoint, "POST", "/api/v1/accounts", origin = origin,
            cookie = cookie, csrf = csrf, body = createBody)
        assertEquals(saved.body, 200, saved.status)
        assertEquals("WEB_ADMIN", database.audit().eventForOperation(operationId)?.source)
        val savedJson = JSONObject(saved.body)
        val details = JSONObject(http(endpoint, "GET", "/api/v1/accounts/${savedJson.getLong("id")}", cookie = cookie).body)
        assertEquals("savings", details.getJSONObject("account").getJSONObject("icon").getString("value"))
        assertEquals(200, http(endpoint, "GET", "/api/v1/operations/$operationId", cookie = cookie).status)
        val staleBody = JSONObject(createBody)
            .put("operationId", UUID.randomUUID().toString())
            .put("dataGeneration", savedJson.getLong("dataGeneration"))
            .put("expectedRevision", 0)
            .put("name", "stale update")
            .toString()
        val stale = http(endpoint, "PUT", "/api/v1/accounts/${savedJson.getLong("id")}", origin = origin,
            cookie = cookie, csrf = csrf, body = staleBody)
        assertEquals(409, stale.status)
        assertTrue(stale.body.contains("STALE_RECORD"))
        val accounts = http(endpoint, "GET", "/api/v1/accounts", cookie = cookie)
        assertTrue(accounts.body.contains("<script>alert(1)</script>"))

        step("limits")
        val oversized = http(endpoint, "POST", "/api/v1/accounts", origin = origin,
            cookie = cookie, csrf = csrf, declaredLength = 262_145)
        assertEquals(413, oversized.status)

        step("disconnect")
        socket.close()
        delay(500)
        assertEquals(WebAdminPhase.ACTIVE, web.state.value.phase)
        assertEquals(200, http(endpoint, "GET", "/api/v1/accounts", cookie = cookie).status)
        assertEquals(200, http(endpoint, "POST", "/api/v1/session/end", origin = origin,
            cookie = cookie, csrf = csrf, body = "{}").status)
        awaitPhase(WebAdminPhase.WAITING)
        assertEquals(401, http(endpoint, "GET", "/api/v1/accounts", cookie = cookie).status)
        step("complete")
    }

    @Test fun demo_mode_serves_and_mutates_only_the_disposable_demo_database() = runBlocking<Unit> {
        val realNames = RoomOverview(database).snapshot().accounts.map { it.name }
        sessions.enterDemo()
        web.start()
        val waiting = web.state.value
        assertEquals(waiting.error?.name, WebAdminPhase.WAITING, waiting.phase)
        val endpoint = URI(requireNotNull(waiting.url))
        val origin = "http://${endpoint.host}:${endpoint.port}"

        val paired = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = "{\"code\":\"${requireNotNull(waiting.pairingCode)}\"}")
        assertEquals(200, paired.status)
        val cookie = paired.headers["set-cookie"].orEmpty().substringBefore(';')
        val csrf = JSONObject(paired.body).getString("csrfToken")
        val socket = openWebSocket(endpoint, origin, cookie)
        awaitPhase(WebAdminPhase.ACTIVE)

        val session = JSONObject(http(endpoint, "GET", "/api/v1/session", cookie = cookie).body)
        assertEquals("DEMO", session.getString("dataMode"))
        val operationId = UUID.randomUUID().toString()
        val body = JSONObject().put("operationId", operationId)
            .put("dataGeneration", session.getLong("dataGeneration"))
            .put("expectedRevision", JSONObject.NULL)
            .put("name", "Demo browser account")
            .put("note", "Disposable Web data")
            .put("cashChanges", org.json.JSONArray()).toString()
        val saved = http(endpoint, "POST", "/api/v1/accounts", origin = origin,
            cookie = cookie, csrf = csrf, body = body)
        assertEquals(saved.body, 200, saved.status)
        assertTrue(sessions.session.value.graph.overview.snapshot().accounts.any {
            it.name == "Demo browser account"
        })
        assertEquals(realNames, RoomOverview(database).snapshot().accounts.map { it.name })

        socket.close()
        web.stop()
        sessions.exitDemo()
        assertEquals(realNames, RoomOverview(database).snapshot().accounts.map { it.name })
    }

    @Test fun credit_accounts_round_trip_through_web_commands_and_reject_invalid_relations() = runBlocking<Unit> {
        web.start()
        val waiting = web.state.value
        val endpoint = URI(requireNotNull(waiting.url))
        val origin = "http://${endpoint.host}:${endpoint.port}"
        val paired = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = "{\"code\":\"${requireNotNull(waiting.pairingCode)}\"}")
        assertEquals(200, paired.status)
        val cookie = paired.headers["set-cookie"].orEmpty().substringBefore(';')
        val csrf = JSONObject(paired.body).getString("csrfToken")
        val socket = openWebSocket(endpoint, origin, cookie)
        awaitPhase(WebAdminPhase.ACTIVE)
        var generation = JSONObject(http(endpoint, "GET", "/api/v1/session", cookie = cookie).body)
            .getLong("dataGeneration")

        fun creditChange(name: String, balance: String, sourceId: Long? = null,
            limit: String = "5000.00", cashId: Long? = null, revision: Long? = null) =
            JSONObject().put("cashAccountId", cashId ?: JSONObject.NULL)
                .put("expectedRevision", revision ?: JSONObject.NULL)
                .put("currencyCode", "CNY").put("balance", balance).put("name", name).put("note", "Web credit")
                .put("type", "CREDIT").put("credit", JSONObject()
                    .put("limitSourceAccountId", sourceId ?: JSONObject.NULL)
                    .put("creditLimit", if (sourceId == null) limit else JSONObject.NULL)
                    .put("statementDay", 25)
                    .put("dueRule", JSONObject().put("type", "AFTER_STATEMENT_DAYS").put("value", 20)))

        fun createParent(name: String, change: JSONObject): JSONObject {
            val body = JSONObject().put("operationId", UUID.randomUUID().toString())
                .put("dataGeneration", generation).put("expectedRevision", JSONObject.NULL)
                .put("name", name).put("note", "Credit relation test")
                .put("cashChanges", org.json.JSONArray().put(change))
            val response = http(endpoint, "POST", "/api/v1/accounts", origin = origin,
                cookie = cookie, csrf = csrf, body = body.toString())
            assertEquals(response.body, 200, response.status)
            return JSONObject(response.body).also { generation = it.getLong("dataGeneration") }
        }

        val rootParent = createParent("Web credit root", creditChange("Root card", "-1200.00"))
        var rootDetail = JSONObject(http(endpoint, "GET", "/api/v1/accounts/${rootParent.getLong("id")}",
            cookie = cookie).body)
        val rootCash = rootDetail.getJSONArray("cash").getJSONObject(0)
        assertEquals("CREDIT", rootCash.getString("type"))
        assertEquals("5000.00", rootCash.getJSONObject("credit").getString("creditLimit"))
        assertEquals("1200", rootCash.getJSONObject("credit").getString("used"))

        var rootAccount = rootDetail.getJSONObject("account")
        val addChild = JSONObject().put("operationId", UUID.randomUUID().toString())
            .put("dataGeneration", generation).put("expectedRevision", rootAccount.getLong("revision"))
            .put("name", rootAccount.getString("name")).put("note", rootAccount.getString("note"))
            .put("cashChanges", org.json.JSONArray().put(
                creditChange("Supplementary card", "-300.00", rootCash.getLong("id"))))
        val addedChild = http(endpoint, "PUT", "/api/v1/accounts/${rootParent.getLong("id")}", origin = origin,
            cookie = cookie, csrf = csrf, body = addChild.toString())
        assertEquals(addedChild.body, 200, addedChild.status)
        generation = JSONObject(addedChild.body).getLong("dataGeneration")
        rootDetail = JSONObject(http(endpoint, "GET", "/api/v1/accounts/${rootParent.getLong("id")}",
            cookie = cookie).body)
        val rootCashRows = rootDetail.getJSONArray("cash")
        val childCash = (0 until rootCashRows.length()).map(rootCashRows::getJSONObject)
            .single { it.getString("name") == "Supplementary card" }
        assertEquals(rootCash.getLong("id"), childCash.getJSONObject("credit").getLong("limitSourceAccountId"))

        val crossParent = JSONObject().put("operationId", UUID.randomUUID().toString())
            .put("dataGeneration", generation).put("expectedRevision", JSONObject.NULL)
            .put("name", "Invalid cross-parent credit").put("note", "")
            .put("cashChanges", org.json.JSONArray().put(
                creditChange("Invalid card", "0", rootCash.getLong("id"))))
        val crossParentResponse = http(endpoint, "POST", "/api/v1/accounts", origin = origin,
            cookie = cookie, csrf = csrf, body = crossParent.toString())
        assertEquals(crossParentResponse.body, 409, crossParentResponse.status)
        assertTrue(crossParentResponse.body.contains("CREDIT_SOURCE_PARENT"))

        rootAccount = rootDetail.getJSONObject("account")
        val invalidChain = JSONObject().put("operationId", UUID.randomUUID().toString())
            .put("dataGeneration", generation).put("expectedRevision", rootAccount.getLong("revision"))
            .put("name", rootAccount.getString("name")).put("note", rootAccount.getString("note"))
            .put("cashChanges", org.json.JSONArray().put(
                creditChange("Invalid chained card", "0", childCash.getLong("id"))))
        val invalidResponse = http(endpoint, "PUT", "/api/v1/accounts/${rootParent.getLong("id")}", origin = origin,
            cookie = cookie, csrf = csrf, body = invalidChain.toString())
        assertEquals(invalidResponse.body, 409, invalidResponse.status)
        assertTrue(invalidResponse.body.contains("CREDIT_SOURCE_CHAIN"))

        rootAccount = rootDetail.getJSONObject("account")
        val edit = JSONObject().put("operationId", UUID.randomUUID().toString())
            .put("dataGeneration", generation).put("expectedRevision", rootAccount.getLong("revision"))
            .put("name", rootAccount.getString("name")).put("note", rootAccount.getString("note"))
            .put("cashChanges", org.json.JSONArray().put(creditChange("Root card", "-1200.00",
                limit = "6000.00", cashId = rootCash.getLong("id"), revision = rootCash.getLong("revision"))))
        val edited = http(endpoint, "PUT", "/api/v1/accounts/${rootParent.getLong("id")}", origin = origin,
            cookie = cookie, csrf = csrf, body = edit.toString())
        assertEquals(edited.body, 200, edited.status)
        generation = JSONObject(edited.body).getLong("dataGeneration")
        rootDetail = JSONObject(http(endpoint, "GET", "/api/v1/accounts/${rootParent.getLong("id")}",
            cookie = cookie).body)
        assertEquals("6000.00", rootDetail.getJSONArray("cash").getJSONObject(0)
            .getJSONObject("credit").getString("creditLimit"))

        val stale = JSONObject(edit.toString()).put("operationId", UUID.randomUUID().toString())
            .put("dataGeneration", generation)
        val staleResponse = http(endpoint, "PUT", "/api/v1/accounts/${rootParent.getLong("id")}", origin = origin,
            cookie = cookie, csrf = csrf, body = stale.toString())
        assertEquals(staleResponse.body, 409, staleResponse.status)
        assertTrue(JSONObject(staleResponse.body).getJSONObject("error").getBoolean("refreshRequired"))

        val currentRootCash = rootDetail.getJSONArray("cash").getJSONObject(0)
        val protectedDelete = JSONObject().put("operationId", UUID.randomUUID().toString())
            .put("dataGeneration", generation).put("accountId", rootParent.getLong("id"))
            .put("expectedRevision", currentRootCash.getLong("revision"))
        val deleteResponse = http(endpoint, "DELETE", "/api/v1/balance-accounts/${currentRootCash.getLong("id")}",
            origin = origin, cookie = cookie, csrf = csrf, body = protectedDelete.toString())
        assertEquals(deleteResponse.body, 409, deleteResponse.status)
        assertTrue(deleteResponse.body.contains("CREDIT_LIMIT_IN_USE"))
        socket.close()
    }

    @Test fun protocol_pong_keeps_session_active_without_javascript_timer_heartbeat() = runBlocking<Unit> {
        web.start()
        val waiting = web.state.value
        val endpoint = URI(requireNotNull(waiting.url))
        val origin = "http://${endpoint.host}:${endpoint.port}"
        val paired = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = "{\"code\":\"${requireNotNull(waiting.pairingCode)}\"}")
        assertEquals(200, paired.status)
        val cookie = paired.headers["set-cookie"].orEmpty().substringBefore(';')
        val socket = openWebSocket(endpoint, origin, cookie)
        awaitPhase(WebAdminPhase.ACTIVE)

        val protocolResponder = launch(Dispatchers.IO) { runCatching { respondToProtocolPings(socket) } }
        delay(17_000)
        assertEquals(WebAdminPhase.ACTIVE, web.state.value.phase)
        assertEquals(200, http(endpoint, "GET", "/api/v1/session", cookie = cookie).status)

        socket.close()
        protocolResponder.cancelAndJoin()
    }

    @Test fun mobile_browser_bearer_reconnects_without_cookie_or_repairing() = runBlocking<Unit> {
        web.start()
        val waiting = web.state.value
        val endpoint = URI(requireNotNull(waiting.url))
        val origin = "http://${endpoint.host}:${endpoint.port}"
        val paired = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = "{\"code\":\"${requireNotNull(waiting.pairingCode)}\"}")
        assertEquals(200, paired.status)
        assertTrue(paired.headers["set-cookie"].orEmpty().contains("SameSite=Lax"))
        val pairedBody = JSONObject(paired.body)
        val csrf = pairedBody.getString("csrfToken")
        val sessionToken = pairedBody.getString("sessionToken")

        val first = openWebSocket(endpoint, origin, cookie = "", ticket = sessionToken)
        awaitPhase(WebAdminPhase.ACTIVE)
        val replacement = openWebSocket(endpoint, origin, cookie = "", ticket = sessionToken)
        delay(500)
        assertEquals(WebAdminPhase.ACTIVE, web.state.value.phase)
        assertEquals(200, http(endpoint, "GET", "/api/v1/session", bearer = sessionToken).status)

        first.close()
        replacement.close()
        delay(500)
        assertEquals(WebAdminPhase.ACTIVE, web.state.value.phase)
        assertEquals(200, http(endpoint, "GET", "/api/v1/session", bearer = sessionToken).status)

        val restored = http(endpoint, "POST", "/api/v1/session/resume", origin = origin,
            bearer = sessionToken, body = "{}")
        assertEquals(200, restored.status)
        assertEquals(csrf, JSONObject(restored.body).getString("csrfToken"))
        assertEquals(401, http(endpoint, "POST", "/api/v1/session/resume", origin = origin,
            bearer = "wrong-session-token-that-is-long-enough", body = "{}").status)

        val resumed = openWebSocket(endpoint, origin, cookie = "", ticket = sessionToken)
        delay(500)
        assertEquals(WebAdminPhase.ACTIVE, web.state.value.phase)
        assertEquals(200, http(endpoint, "GET", "/api/v1/session", bearer = sessionToken).status)
        assertEquals(200, http(endpoint, "POST", "/api/v1/session/end", origin = origin,
            bearer = sessionToken, csrf = csrf, body = "{}").status)
        awaitPhase(WebAdminPhase.WAITING)
        resumed.close()
    }

    @Test fun backgrounding_while_waiting_stops_pairing_before_a_browser_can_activate() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            web.start()
            awaitPhase(WebAdminPhase.WAITING)
            activity.moveToState(Lifecycle.State.CREATED)
            awaitPhase(WebAdminPhase.CLOSED)
        }
    }

    @Test fun account_photo_round_trip_preserves_existing_image_and_rejects_invalid_symbols() = runBlocking<Unit> {
        web.start()
        val waiting = web.state.value
        val endpoint = URI(requireNotNull(waiting.url))
        val origin = "http://${endpoint.host}:${endpoint.port}"
        val paired = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = JSONObject().put("code", waiting.pairingCode).toString())
        val cookie = paired.headers["set-cookie"].orEmpty().substringBefore(';')
        val csrf = JSONObject(paired.body).getString("csrfToken")
        val socket = openWebSocket(endpoint, origin, cookie)
        awaitPhase(WebAdminPhase.ACTIVE)
        val bitmap = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GRAY)
        val image = ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val generation = JSONObject(http(endpoint, "GET", "/api/v1/session", cookie = cookie).body).getLong("dataGeneration")
        val create = JSONObject().put("operationId", UUID.randomUUID().toString()).put("dataGeneration", generation)
            .put("name", "Photo account").put("cashChanges", org.json.JSONArray())
            .put("iconChange", JSONObject().put("type", "IMAGE").put("imageBase64", Base64.getEncoder().encodeToString(image)))
        val response = http(endpoint, "POST", "/api/v1/accounts", origin = origin, cookie = cookie, csrf = csrf, body = create.toString())
        assertEquals(response.body, 200, response.status)
        val receipt = JSONObject(response.body)
        val id = receipt.getLong("id")
        val detail = JSONObject(http(endpoint, "GET", "/api/v1/accounts/$id", cookie = cookie).body).getJSONObject("account")
        val key = detail.getJSONObject("icon").getString("value")
        assertEquals("IMAGE", detail.getJSONObject("icon").getString("type"))
        val photo = http(endpoint, "GET", "/api/v1/account-icons/$key", cookie = cookie)
        assertEquals(200, photo.status)
        assertEquals("image/png", photo.headers["content-type"])
        assertEquals(401, http(endpoint, "GET", "/api/v1/account-icons/$key").status)
        val edit = JSONObject().put("operationId", UUID.randomUUID().toString())
            .put("dataGeneration", receipt.getLong("dataGeneration")).put("expectedRevision", detail.getLong("revision"))
            .put("name", "Renamed photo account").put("cashChanges", org.json.JSONArray())
        val edited = http(endpoint, "PUT", "/api/v1/accounts/$id", origin = origin, cookie = cookie, csrf = csrf, body = edit.toString())
        assertEquals(200, edited.status)
        val preserved = JSONObject(http(endpoint, "GET", "/api/v1/accounts/$id", cookie = cookie).body).getJSONObject("account")
        assertEquals(key, preserved.getJSONObject("icon").getString("value"))
        val invalid = JSONObject(edit.toString()).put("operationId", UUID.randomUUID().toString())
            .put("dataGeneration", JSONObject(edited.body).getLong("dataGeneration"))
            .put("expectedRevision", preserved.getLong("revision"))
            .put("iconChange", JSONObject().put("type", "SYMBOL").put("value", "../arbitrary"))
        assertEquals(400, http(endpoint, "PUT", "/api/v1/accounts/$id", origin = origin, cookie = cookie, csrf = csrf, body = invalid.toString()).status)
        socket.close()
    }

    @Test fun phone_stays_awake_until_idle_timeout_and_background_ends_session() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            web.start()
            val waiting = web.state.value
            val endpoint = URI(requireNotNull(waiting.url))
            val origin = "http://${endpoint.host}:${endpoint.port}"
            val paired = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
                body = JSONObject().put("code", waiting.pairingCode).toString())
            val cookie = paired.headers["set-cookie"].orEmpty().substringBefore(';')
            val socket = openWebSocket(endpoint, origin, cookie)
            awaitPhase(WebAdminPhase.ACTIVE)
            delay(1_000)
            activity.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) }
            activity.recreate()
            delay(1_000)
            assertEquals(WebAdminPhase.ACTIVE, web.state.value.phase)
            activity.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) }
            val responder = launch(Dispatchers.IO) { runCatching { respondToProtocolPings(socket) } }
            // Exercise the production five-minute timer, with heartbeats but no user activity.
            repeat(60) {
                if (web.state.value.phase == WebAdminPhase.ACTIVE) runCatching { sendMaskedText(socket, "heartbeat") }
                delay(5_000)
            }
            awaitPhase(WebAdminPhase.CLOSED)
            activity.onActivity { assertEquals(0, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            socket.close(); responder.cancelAndJoin()
            web.start()
            val next = web.state.value
            val nextEndpoint = URI(requireNotNull(next.url))
            val nextOrigin = "http://${nextEndpoint.host}:${nextEndpoint.port}"
            val nextPair = http(nextEndpoint, "POST", "/api/v1/pair/code", origin = nextOrigin,
                body = JSONObject().put("code", next.pairingCode).toString())
            val nextSocket = openWebSocket(nextEndpoint, nextOrigin, nextPair.headers["set-cookie"].orEmpty().substringBefore(';'))
            awaitPhase(WebAdminPhase.ACTIVE)
            activity.moveToState(Lifecycle.State.CREATED)
            awaitPhase(WebAdminPhase.CLOSED)
            nextSocket.close()
        }
    }

    private fun step(name: String) = Log.i("WebAdminE2E", "STEP $name")

    private suspend fun awaitPhase(expected: WebAdminPhase) {
        repeat(100) {
            if (web.state.value.phase == expected) return
            delay(50)
        }
        throw AssertionError("Expected $expected but was ${web.state.value}")
    }

    private fun http(
        endpoint: URI,
        method: String,
        path: String,
        origin: String? = null,
        cookie: String? = null,
        bearer: String? = null,
        csrf: String? = null,
        body: String = "",
        host: String = "${endpoint.host}:${endpoint.port}",
        declaredLength: Int? = null
    ): HttpResult = Socket(endpoint.host, endpoint.port).use { socket ->
        socket.soTimeout = 10_000
        val bytes = body.toByteArray(Charsets.UTF_8)
        val request = buildString {
            append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n")
            origin?.let { append("Origin: $it\r\n") }
            cookie?.let { append("Cookie: $it\r\n") }
            bearer?.let { append("Authorization: Bearer $it\r\n") }
            csrf?.let { append("X-Valnook-CSRF: $it\r\n") }
            if (method != "GET") append("Content-Type: application/json\r\nContent-Length: ${declaredLength ?: bytes.size}\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().write(request)
        if (declaredLength == null && bytes.isNotEmpty()) socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
        readHttp(socket)
    }

    private fun readHttp(socket: Socket): HttpResult {
        val input = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        var tail = ""
        while (!tail.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next < 0) throw AssertionError("Incomplete HTTP response")
            headerBytes.write(next)
            tail = (tail + next.toChar()).takeLast(4)
        }
        val head = headerBytes.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n").split("\r\n")
        val headers = head.drop(1).mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let {
            line.substring(0, it).lowercase() to line.substring(it + 1).trim()
        } }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) throw AssertionError("Incomplete HTTP body")
            offset += count
        }
        return HttpResult(head.first().split(' ')[1].toInt(), headers, body.toString(Charsets.UTF_8))
    }

    private fun openWebSocket(endpoint: URI, origin: String, cookie: String, ticket: String? = null): Socket {
        val socket = Socket(endpoint.host, endpoint.port).apply { soTimeout = 10_000 }
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
        val target = "/ws/v1/session" + ticket?.let { "?ticket=$it" }.orEmpty()
        val request = "GET $target HTTP/1.1\r\nHost: ${endpoint.host}:${endpoint.port}\r\n" +
            "Origin: $origin\r\n" + cookie.takeIf { it.isNotBlank() }?.let { "Cookie: $it\r\n" }.orEmpty() +
            "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII)); socket.getOutputStream().flush()
        val header = ByteArrayOutputStream()
        var tail = ""
        while (!tail.endsWith("\r\n\r\n")) {
            val next = socket.getInputStream().read()
            if (next < 0) break
            header.write(next); tail = (tail + next.toChar()).takeLast(4)
        }
        assertTrue(header.toString(Charsets.ISO_8859_1.name()).startsWith("HTTP/1.1 101"))
        return socket
    }

    private fun sendMaskedText(socket: Socket, value: String) {
        val payload = value.toByteArray(Charsets.UTF_8)
        sendMaskedFrame(socket, 0x1, payload)
    }

    private fun respondToProtocolPings(socket: Socket) {
        val input = socket.getInputStream()
        while (!Thread.currentThread().isInterrupted) {
            val first = input.read()
            if (first < 0) return
            val second = input.read()
            if (second < 0) return
            val masked = second and 0x80 != 0
            val baseLength = second and 0x7f
            val length = when (baseLength) {
                126 -> (input.read() shl 8) or input.read()
                127 -> {
                    var value = 0L
                    repeat(8) { value = (value shl 8) or input.read().toLong() }
                    require(value <= Int.MAX_VALUE)
                    value.toInt()
                }
                else -> baseLength
            }
            val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(length).also { readFully(input, it) }
            mask?.let { key -> payload.indices.forEach { payload[it] =
                (payload[it].toInt() xor key[it % key.size].toInt()).toByte() } }
            when (first and 0x0f) {
                0x9 -> sendMaskedFrame(socket, 0xA, payload)
                0x8 -> return
            }
        }
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw java.io.EOFException()
            offset += count
        }
    }

    private fun sendMaskedFrame(socket: Socket, opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4).also(SecureRandom()::nextBytes)
        require(payload.size < 126)
        val frame = ByteArray(2 + 4 + payload.size)
        frame[0] = (0x80 or opcode).toByte(); frame[1] = (0x80 or payload.size).toByte()
        mask.copyInto(frame, 2)
        payload.indices.forEach { frame[6 + it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        socket.getOutputStream().write(frame); socket.getOutputStream().flush()
    }

    private data class HttpResult(val status: Int, val headers: Map<String, String>, val body: String)
}
