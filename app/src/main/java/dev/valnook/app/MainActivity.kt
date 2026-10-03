package dev.valnook.app
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import dev.valnook.app.di.AppSessionManager
import dev.valnook.app.navigation.ValnookRoot
import kotlinx.coroutines.launch
@AndroidEntryPoint
class MainActivity:ComponentActivity() {
    @Inject lateinit var sessions:AppSessionManager
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced=false
        setContent {
            ValnookRoot(sessions) {
                lifecycleScope.launch {
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
