package dev.valnook.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.appearance.*
import dev.valnook.app.navigation.ValnookRoot
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.feature.accounts.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

@HiltAndroidTest
class AccountIconUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Before fun prepare() { hilt.inject() }
    private fun scene(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent(content = content) }
        rule.waitForIdle()
    }
    private fun save(name: String, tag: String? = null) {
        val node = if (tag == null) rule.onRoot() else rule.onNodeWithTag(tag)
        val bitmap = node.captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun draft(saved: SavedStateHandle = SavedStateHandle()): AccountEditViewModel {
        val graph = rule.activity.sessions.session.value.graph
        val vm = rule.runOnUiThread { AccountEditViewModel(null, graph.overview, graph.commands, saved) }
        rule.waitUntil(10_000) { vm.state.value.loaded }
        rule.runOnUiThread { vm.changeName("Icon test account") }
        return vm
    }
    private fun edit(vm: AccountEditViewModel, dark: Boolean = true, large: Boolean = false) {
        val graph = rule.activity.sessions.session.value.graph
        scene {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAccountImageLoader provides remember { AccountImageLoader(graph.overview::accountIconImage) },
                LocalDensity provides Density(density.density, if (large) 2f else density.fontScale)) {
                ValnookTheme(dark_theme = dark) { AccountEditScreen(vm, {}) }
            }
        }
    }

    @Test fun symbol_selection_survives_draft_recreation_and_saves_to_account_list() {
        val handle = SavedStateHandle()
        val vm = draft(handle)
        edit(vm)
        rule.onNodeWithTag("account-icon-edit").performClick()
        rule.onNodeWithTag("account-symbol-credit_card").performClick()
        assertEquals("credit_card", vm.state.value.icon.symbol)
        val graph = rule.activity.sessions.session.value.graph
        assertTrue(runBlocking { graph.overview.snapshot().accounts.isEmpty() })
        val restored = rule.runOnUiThread { AccountEditViewModel(null, graph.overview, graph.commands,
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })) }
        assertEquals(vm.state.value.icon, restored.state.value.icon)
        edit(restored, dark = false)
        save("account-icon-editor-light")
        rule.onNodeWithText(rule.activity.getString(dev.valnook.feature.accounts.R.string.account_save)).performScrollTo().performClick()
        rule.waitUntil(10_000) { restored.submission.value.phase == dev.valnook.domain.command.SubmissionPhase.SUCCEEDED }
        assertEquals("credit_card", runBlocking { graph.overview.snapshot() }.accounts.single().icon.symbol)
        scene { ValnookRoot(rule.activity.sessions, rule.activity.webAdmin) }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("accounts-list").fetchSemanticsNodes().isNotEmpty() }
        save("account-icon-list")
    }

    @Test fun symbol_dialog_supports_large_text_and_cancel_does_not_change_draft() {
        val vm = draft()
        edit(vm, large = true)
        rule.onNodeWithTag("account-icon-edit").performClick()
        rule.onNodeWithTag("account-symbol-account_balance").assertIsSelected()
        save("account-icon-grid-large-dark", "account-icon-dialog")
        rule.onNodeWithText(rule.activity.getString(dev.valnook.feature.accounts.R.string.account_order_cancel)).performClick()
        assertFalse(vm.state.value.iconChanged)
        rule.onNodeWithTag("account-icon-edit").performClick()
        rule.onNodeWithTag("account-symbol-savings").performClick()
        rule.onNodeWithTag("account-icon-edit").performClick()
        rule.onNodeWithText(rule.activity.getString(dev.valnook.feature.accounts.R.string.account_icon_reset)).performClick()
        assertEquals(AccountIcon(), vm.state.value.icon)
        assertTrue(runBlocking { rule.activity.sessions.session.value.graph.overview.snapshot().accounts.isEmpty() })
    }

    @Test fun selected_photo_can_be_adjusted_cancelled_and_saved_without_source_uri() {
        val context = rule.activity
        val file = File(context.cacheDir, "account-icon-test-${UUID.randomUUID()}.png")
        val source = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(40, 90, 160))
        android.graphics.Canvas(source).drawRect(320f, 80f, 480f, 320f, android.graphics.Paint().apply { color = Color.WHITE })
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }; source.recycle()
        val vm = draft()
        var launches = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertEquals("android.provider.action.PICK_IMAGES", contract.createIntent(context, input).action)
                launches++
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        try {
            scene { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ValnookTheme(dark_theme = true) { AccountPhotoPicker(vm::changeImage) { pick ->
                    AccountEditScreen(vm, {}, onPickImage = pick)
                } }
            } }
            fun select() {
                rule.onNodeWithTag("account-icon-edit").performClick()
                rule.onNodeWithTag("account-icon-photo").performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithTag("account-photo-crop").fetchSemanticsNodes().isNotEmpty() }
            }
            select()
            rule.onNodeWithTag("account-photo-zoom").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(2f) }
            rule.onNodeWithText(context.getString(dev.valnook.feature.accounts.R.string.account_order_cancel)).performClick()
            assertFalse(vm.state.value.iconChanged)
            select()
            rule.onNode(isToggleable()).performClick()
            save("account-photo-fit", "account-photo-crop")
            rule.onNodeWithTag("account-photo-confirm").performClick()
            rule.waitUntil(10_000) { vm.state.value.icon.type == AccountIconType.IMAGE }
            assertEquals(2, launches)
            assertTrue(file.delete())
            val bytes = requireNotNull(vm.state.value.iconImage)
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(256, image.width); assertEquals(256, image.height)
            assertEquals(0, Color.alpha(image.getPixel(0, 0))); image.recycle()
            val graph = context.sessions.session.value.graph
            assertTrue(runBlocking { graph.overview.snapshot().accounts.isEmpty() })
            rule.runOnUiThread { vm.submit() }
            rule.waitUntil(10_000) { vm.submission.value.phase == dev.valnook.domain.command.SubmissionPhase.SUCCEEDED }
            val icon = runBlocking { graph.overview.snapshot() }.accounts.single().icon
            assertArrayEquals(bytes, runBlocking { graph.overview.accountIconImage(requireNotNull(icon.imageKey)) })
            scene { ValnookRoot(context.sessions, context.webAdmin) }
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("accounts-list").fetchSemanticsNodes().isNotEmpty() }
            save("account-photo-saved-list")
        } finally { file.delete() }
    }

    @Test fun crop_fit_and_session_cache_are_bounded_and_isolated() = runBlocking {
        val source = Bitmap.createBitmap(900, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val cropped = encodeAccountPhoto(source, false, 1f, 50f, -50f)
        val fitted = encodeAccountPhoto(source, true, 1f, 0f, 0f)
        source.recycle()
        assertTrue(cropped.size <= AccountSymbols.MAX_IMAGE_BYTES)
        val bitmap = BitmapFactory.decodeByteArray(cropped, 0, cropped.size)
        assertEquals(255, Color.alpha(bitmap.getPixel(0, 0))); bitmap.recycle()
        val fit = BitmapFactory.decodeByteArray(fitted, 0, fitted.size)
        assertEquals(0, Color.alpha(fit.getPixel(0, 0))); fit.recycle()
        val first = AccountImageLoader { cropped }
        val second = AccountImageLoader { null }
        assertNotNull(first.load("same-id")); assertNull(second.load("same-id"))
        assertNull(AccountImageLoader { error("Closed session") }.load("same-id"))
    }
}
