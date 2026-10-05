package dev.valnook.app.appearance

import android.content.Context
import android.annotation.SuppressLint
import android.content.res.Configuration
import dev.valnook.feature.settings.AppThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Separate from financial backups and database replacement. */
internal class ThemePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun read(): AppThemeMode = AppThemeMode.entries.firstOrNull {
        it.name == preferences.getString("theme", null)
    } ?: AppThemeMode.SYSTEM

    @SuppressLint("UseKtx") // The Boolean commit result controls whether the Activity may apply the choice.
    suspend fun save(mode: AppThemeMode): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putString("theme", mode.name).commit()
    }
}

internal fun AppThemeMode.configurationOverride(): Configuration? = when (this) {
    AppThemeMode.SYSTEM -> null
    AppThemeMode.DARK -> Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_YES }
    AppThemeMode.LIGHT -> Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_NO }
}
