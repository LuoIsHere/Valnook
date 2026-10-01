package dev.valnook.app
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import dev.valnook.app.di.AppGraph
import dev.valnook.app.navigation.ValnookRoot
import dev.valnook.designsystem.ValnookTheme
@AndroidEntryPoint
class MainActivity:ComponentActivity() {
    @Inject lateinit var graph:AppGraph
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced=false
        setContent { ValnookTheme {ValnookRoot(graph)} }
    }
}
