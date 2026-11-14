package dev.valnook.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppSessionManager
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.BillDraft
import dev.valnook.domain.model.BillImage
import dev.valnook.domain.model.BillType
import dev.valnook.domain.repository.BillImageLibrary
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveBill
import dev.valnook.feature.bills.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** Pictures are picked or photographed in the bill form, shown on the bill, and counted in the list; synthetic only. */
@HiltAndroidTest
class BillImagesUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var sessions: AppSessionManager
    @Inject lateinit var db: ValnookDatabase
    private val graph get() = sessions.session.value.graph
    private val library get() = requireNotNull(graph.billImages)
    private var cashId = 0L

    @Before fun seed() {
        hilt.inject()
        rule.waitForIdle()
        runBlocking {
            val account = graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "合成银行", "",
                listOf(CashBalanceChange("CNY", 100_000, null, name = "合成储蓄卡")))).id
            cashId = db.cash().accountIds(account).single()
        }
    }

    private fun waitTag(tag: String, timeout: Long = 10_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /** A synthetic receipt-like picture in [format]. */
    private fun picture(shade: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(250, 250, 245))
            drawRect(40f, 40f, 560f, 120f + shade, Paint().apply { color = Color.rgb(shade, 90, 150) })
        }
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it); bitmap.recycle() }.toByteArray()
    }

    private fun bill(images: List<BillImage>? = null): Long = runBlocking {
        val categories = graph.bills!!.observeCategories().first()
        fun key(value: String) = categories.single { it.builtinKey == value }.id
        graph.commands.execute(SaveBill(UUID.randomUUID().toString(), null, null, BillDraft(BillType.EXPENSE,
            graph.clock.millis() - 60_000, 2_500, "CNY", 0, cashId, 2_500, categoryId = key("food"),
            subCategoryId = key("food.lunch"), note = "合成午饭", images = images))).id
    }

    /** Answers the photo picker with [picked] and the camera by writing [photo] where it asks. */
    private fun registry(picked: List<File>, photo: ByteArray) = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: androidx.core.app.ActivityOptionsCompat?) {
            val result = when (contract) {
                is ActivityResultContracts.TakePicture -> {
                    rule.activity.contentResolver.openOutputStream(input as Uri)!!.use { it.write(photo) }
                    android.content.Intent()
                }
                else -> android.content.Intent().apply {
                    data = Uri.fromFile(picked.first())
                    clipData = android.content.ClipData.newRawUri(null, Uri.fromFile(picked.first())).apply {
                        picked.drop(1).forEach { addItem(android.content.ClipData.Item(Uri.fromFile(it))) }
                    }
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                dispatchResult(requestCode, android.app.Activity.RESULT_OK, result)
            }
        }
    }

    private fun show(registry: ActivityResultRegistry? = null, images: BillImageLibrary? = library, content: @androidx.compose.runtime.Composable () -> Unit) {
        val owner = registry?.let { object : ActivityResultRegistryOwner { override val activityResultRegistry = it } }
        rule.runOnUiThread {
            rule.activity.setContent {
                val inner = @androidx.compose.runtime.Composable {
                    CompositionLocalProvider(LocalBillImages provides images) {
                        ValnookTheme(dark_theme = false) { Box(Modifier.fillMaxSize().systemBarsPadding()) { content() } }
                    }
                }
                if (owner == null) inner() else CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) { inner() }
            }
        }
    }

    private fun editor(billId: Long, saved: SavedStateHandle) = rule.runOnUiThread {
        BillEditorViewModel(billId, null, BillTab.EXPENSE, graph.bills!!, graph.commands, graph.settings.observeSettings(),
            graph.overview.observeFxContext(), graph.clock, saved, library)
    }

    @Test fun pictures_from_photos_and_the_camera_are_added_kept_across_a_restart_and_saved() {
        val id = bill()
        val picked = listOf(picture(10), picture(60)).map { bytes ->
            File(rule.activity.cacheDir, "picked-${UUID.randomUUID()}.png").apply { writeBytes(bytes) }
        }
        val saved = SavedStateHandle()
        val vm = editor(id, saved)
        var done = false
        show(registry(picked, picture(120, Bitmap.CompressFormat.JPEG))) { BillEditorScreen(vm, { done = true }, {}) }
        waitTag("bill-images-pill")
        rule.onNodeWithTag("bill-images-pill").performScrollTo().assertTextEquals("图片").performClick()
        try { waitTag("bill-image-from-photos", 3_000) } catch (e: Throwable) {
            throw AssertionError(rule.onRoot(useUnmergedTree = true).printToString(), e)
        }
        rule.onNodeWithTag("bill-image-from-photos").performClick()
        waitTag("bill-editor-image-1")
        rule.onNodeWithTag("bill-images-pill").performScrollTo().performClick()
        rule.onNodeWithTag("bill-image-take-photo").performClick()
        waitTag("bill-editor-image-2")
        rule.onNodeWithTag("bill-images-pill").assertTextEquals("图片 3/4")
        // The camera's file is read and gone; only the stored copy is left.
        assertTrue(File(rule.activity.cacheDir, "bill_camera").listFiles().orEmpty().isEmpty())

        // A picture added here and taken off again is deleted at once.
        val removed = vm.state.value.form.images.first().key
        rule.onNodeWithTag("bill-editor-image-remove-0").performScrollTo().performClick()
        rule.waitUntil(5_000) { runBlocking { library.read(removed) } == null }
        rule.onNodeWithTag("bill-images-pill").assertTextEquals("图片 2/4")
        // A form put back after the app was ended still has its pictures.
        val restored = editor(id, saved)
        assertEquals(vm.state.value.form.images, restored.state.value.form.images)
        // Viewing a picture from the form opens it full screen.
        rule.onNodeWithTag("bill-editor-image-0").performScrollTo().performClick()
        waitTag("bill-image-viewer")
        rule.onNodeWithTag("bill-image-close").performClick()
        rule.onNodeWithTag("bill-confirm").performClick()
        rule.waitUntil(8_000) { done }
        val stored = runBlocking { graph.bills!!.observeImages(id).first() }
        assertEquals(vm.state.value.form.images.map { it.key }, stored.map { it.key })
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.byteSize > 0 && it.width * it.height <= 2_000_000 })
        picked.forEach { it.delete() }
    }

    @Test fun the_bill_shows_its_pictures_full_screen_and_one_not_on_this_device_as_missing() {
        val images = listOf(picture(20), picture(80)).map { runBlocking { library.store(BillImageCodecSample.webp(it)) } }
        val id = bill(images)
        library.saved(images.map { it.key })
        // The second picture is not on this device.
        val partial = object : BillImageLibrary by library {
            override suspend fun read(key: String) = if (key == images[1].key) null else library.read(key)
        }
        val vm = rule.runOnUiThread { BillDetailViewModel(id, graph.bills!!, graph.commands) }
        show(images = partial) { BillDetailScreen(vm, {}, {}, {}) }
        waitTag("bill-image-1")
        waitTag("bill-image-missing")
        rule.onNodeWithTag("bill-image-0").performClick()
        waitTag("bill-image-viewer")
        rule.onNodeWithText("1 / 2").assertExists()
        rule.onNodeWithTag("bill-image-close").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("bill-image-viewer").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun the_list_counts_a_bill_s_pictures() {
        val images = listOf(picture(30), picture(90)).map { runBlocking { library.store(BillImageCodecSample.webp(it)) } }
        val withPictures = bill(images)
        library.saved(images.map { it.key })
        val plain = bill()
        val vm = rule.runOnUiThread { BillsViewModel(graph.bills!!, graph.settings.observeSettings(),
            graph.overview.observeFxContext(), graph.clock, SavedStateHandle()) }
        show { BillsScreen(vm, {}, {}) }
        waitTag("bill-row-images-$withPictures")
        rule.onNode(hasText("2") and hasAnyAncestor(hasTestTag("bill-row-images-$withPictures")), useUnmergedTree = true).assertExists()
        assertTrue(rule.onAllNodesWithTag("bill-row-images-$plain", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}

/** Synthetic pictures already in the stored form, for tests that do not go through the picker. */
internal object BillImageCodecSample {
    fun webp(png: ByteArray): ByteArray {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it); bitmap.recycle() }.toByteArray()
    }
}
