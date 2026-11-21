package dev.valnook.app
import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import dev.valnook.app.di.AppSessionManager
import dev.valnook.app.navigation.ValnookRoot
import dev.valnook.app.webadmin.AppWebAdminCoordinator
import dev.valnook.app.appearance.ThemePreferences
import dev.valnook.app.appearance.configurationOverride
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.valnook.domain.webadmin.WebAdminPhase
import android.view.WindowManager
@AndroidEntryPoint
class MainActivity:ComponentActivity() {
    @Inject lateinit var sessions:AppSessionManager
    @Inject lateinit var webAdmin:AppWebAdminCoordinator
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // Apply before resources or views are created, including native date/time dialogs.
        ThemePreferences(newBase).read().configurationOverride()?.let(::applyOverrideConfiguration)
    }
    /** Bill pages asked for by a fresh start or a shortcut, until the root opens them. */
    private val launchRequests = dev.valnook.app.quick.LaunchRequests()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        dev.valnook.app.quick.QuickEntryPreferences.of(this).launchTarget(intent, savedInstanceState != null)?.let { target ->
            val shortcut = dev.valnook.app.quick.QuickShortcuts.target(intent) != null
            launchRequests.request(target, shortcut)
            if (shortcut) dev.valnook.app.quick.QuickShortcuts.reportUsed(this, target)
        }
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced=false
        val appearance = ThemePreferences(this)
        lifecycleScope.launch {
            // The screen stays on while the web admin serves, and while a OneDrive backup or a picture transfer runs:
            // the app has no background service, so turning the screen off would stop them.
            kotlinx.coroutines.flow.combine(webAdmin.state, sessions.observeCloudState(), sessions.observeCloudImages()) { web, cloud, images ->
                web.phase == WebAdminPhase.ACTIVE || images.uploading != null || images.fetching != null ||
                    cloud.attemptState in setOf(dev.valnook.domain.cloud.BackupAttemptState.PREPARING,
                        dev.valnook.domain.cloud.BackupAttemptState.UPLOADING, dev.valnook.domain.cloud.BackupAttemptState.VERIFYING)
            }.distinctUntilChanged().collectLatest { keepOn ->
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        setContent {
            ValnookRoot(sessions, webAdmin, themeMode = appearance.read(), onThemeChange = { mode ->
                val saved = appearance.save(mode)
                if (saved) withContext(Dispatchers.Main.immediate) { recreate() }
                saved
            }, launchRequests = launchRequests) {
                lifecycleScope.launch {
                    webAdmin.stop()
                    sessions.exitDemo()
                    finish()
                }
            }
        }
    }
    /** A shortcut used while the app runs: its page opens above the current one. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        dev.valnook.app.quick.QuickShortcuts.target(intent)?.let { target ->
            launchRequests.request(target, shortcut = true)
            dev.valnook.app.quick.QuickShortcuts.reportUsed(this, target)
        }
    }
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { sessions.reconcileCloudSchedule() }
        sessions.requestFxSync()
    }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && webAdmin.state.value.phase != WebAdminPhase.CLOSED) {
            webAdmin.onPhoneBackgrounded()
        }
    }
}
