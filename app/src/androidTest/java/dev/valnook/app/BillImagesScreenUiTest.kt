package dev.valnook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.cloud.CloudBackupRuntimeState
import dev.valnook.domain.cloud.CloudBackupService
import dev.valnook.domain.cloud.CloudImageStatus
import dev.valnook.domain.cloud.UnavailableCloudBackupService
import dev.valnook.domain.repository.BillImageCleanup
import dev.valnook.domain.repository.BillImageStorage
import dev.valnook.domain.repository.BillImageUsage
import dev.valnook.feature.backup.BillImagesScreen
import dev.valnook.feature.backup.BillImagesViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Settings → Bill pictures: room used, the automatic clean-up, clearing by hand and fetching back. */
class BillImagesScreenUiTest {
    @get:Rule val rule = createComposeRule()
    private val now = Instant.parse("2026-11-14T00:00:00Z")
    private val usage = MutableStateFlow(BillImageUsage(onDevice = 12, onDeviceBytes = 3 * 1024 * 1024 + 300_000, onlyInCloud = 30, notUploaded = 2))
    private val months = MutableStateFlow<Int?>(null)
    private val connected = MutableStateFlow(true)
    private val cleared = mutableListOf<Long?>()
    private val deleted = mutableListOf<Long?>()
    private var fetches = 0
    private val storage = object : BillImageStorage {
        override fun observeUsage() = usage
        override fun observeAutoCleanMonths() = months
        override suspend fun setAutoCleanMonths(months: Int?) { this@BillImagesScreenUiTest.months.value = months }
        override suspend fun clearDevice(savedBeforeMs: Long?): BillImageCleanup { cleared += savedBeforeMs; return BillImageCleanup(9, 2) }
        override suspend fun deleteEverywhere(savedBeforeMs: Long?): Int { deleted += savedBeforeMs; return 4 }
    }
    private val cloud = object : CloudBackupService by UnavailableCloudBackupService {
        override val cloudAllowed = true
        override fun observeState() = connected.map { CloudBackupRuntimeState(connected = it) }
        override fun observeImages() = MutableStateFlow(CloudImageStatus())
        override suspend fun fetchMissingImages(attachedSinceMs: Long?): Int { fetches++; return 0 }
    }

    @Before fun prompts() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("valnook_backup_prompts", 0).edit().clear().commit()
    }

    private fun show() {
        val vm = BillImagesViewModel(storage, cloud, Clock.fixed(now, ZoneOffset.UTC))
        rule.setContent { ValnookTheme(dark_theme = false) { BillImagesScreen(vm) } }
    }

    @Test fun usage_and_the_automatic_clean_up_need_onedrive() {
        show()
        rule.onNodeWithTag("bill-images-on-device").assertTextContains("12", substring = true).assertTextContains("3.3", substring = true)
        rule.onNodeWithTag("bill-images-auto-clean").performClick()
        rule.waitUntil(5_000) { months.value == 3 }
        rule.onNodeWithTag("bill-images-auto-6").performClick()
        rule.waitUntil(5_000) { months.value == 6 }
        rule.onNodeWithTag("bill-images-auto-clean").performClick()
        rule.waitUntil(5_000) { months.value == null }
        // Without OneDrive there is nowhere to fetch from, so it cannot be switched on.
        connected.value = false
        rule.onNodeWithTag("bill-images-auto-clean").assertIsNotEnabled()
    }

    @Test fun clearing_keeps_onedrive_unless_asked_and_without_onedrive_deletes_for_good() {
        show()
        rule.onNodeWithTag("bill-images-clear").performClick()
        rule.onNodeWithTag("bill-images-clear-6").performClick()
        rule.onNodeWithTag("bill-images-clear-confirm").assertTextEquals("清理").performClick()
        rule.waitUntil(5_000) { cleared.size == 1 }
        assertEquals(now.toEpochMilli() - 6 * 30L * 24 * 3600 * 1000, cleared.single())
        rule.onNodeWithTag("bill-images-result").assertTextContains("2 张还没备份到 OneDrive", substring = true)
        // Also from OneDrive: the button says delete.
        rule.onNodeWithTag("bill-images-clear").performClick()
        rule.onNodeWithTag("bill-images-clear-all").performClick()
        rule.onNodeWithTag("bill-images-clear-everywhere").performClick()
        rule.onNodeWithTag("bill-images-clear-confirm").assertTextEquals("删除").performClick()
        rule.waitUntil(5_000) { deleted.size == 1 }
        assertEquals(null, deleted.single())
        // Not connected: only deleting for good is offered.
        connected.value = false
        rule.onNodeWithTag("bill-images-clear").performClick()
        rule.onNodeWithTag("bill-images-clear-everywhere").assertDoesNotExist()
        rule.onNodeWithTag("bill-images-clear-confirm").assertTextEquals("删除").performClick()
        rule.waitUntil(5_000) { deleted.size == 2 }
    }

    @Test fun fetching_many_back_asks_to_keep_the_screen_on_first() {
        show()
        rule.onNodeWithTag("bill-images-fetch-all").performClick()
        rule.onNodeWithTag("cloud-foreground-reminder").assertExists()
        rule.onNodeWithTag("cloud-foreground-continue").performClick()
        rule.waitUntil(5_000) { fetches == 1 }
    }
}
