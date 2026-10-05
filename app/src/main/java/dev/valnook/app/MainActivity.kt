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
@AndroidEntryPoint
class MainActivity:ComponentActivity() {
    @Inject lateinit var sessions:AppSessionManager
    @Inject lateinit var webAdmin:AppWebAdminCoordinator
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // Apply before resources or views are created, including native date/time dialogs.
        ThemePreferences(newBase).read().configurationOverride()?.let(::applyOverrideConfiguration)
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced=false
        val appearance = ThemePreferences(this)
        setContent {
            ValnookRoot(sessions, webAdmin, themeMode = appearance.read(), onThemeChange = { mode ->
                val saved = appearance.save(mode)
                if (saved) withContext(Dispatchers.Main.immediate) { recreate() }
                saved
            }) {
                lifecycleScope.launch {
                    webAdmin.stop()
                    sessions.exitDemo()
                    finish()
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { sessions.reconcileCloudSchedule() }
    }
}
