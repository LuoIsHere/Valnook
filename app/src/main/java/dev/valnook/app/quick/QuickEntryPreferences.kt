package dev.valnook.app.quick

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.valnook.feature.settings.QuickEntrySettings
import dev.valnook.feature.settings.QuickLaunchTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Quick bookkeeping on this device, outside backups: where a fresh start opens, and whether "Valnook bill" is in the
 * share sheet. The latter is the share entry's own state in the system, so it is what the share sheet shows.
 */
class QuickEntryPreferences private constructor(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("quick_entry", Context.MODE_PRIVATE)
    private val shareEntry = ComponentName(app, SHARE_ENTRY)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<QuickEntrySettings> = state.asStateFlow()

    private fun read() = QuickEntrySettings(
        launch = preferences.getBoolean(LAUNCH, false),
        target = QuickLaunchTarget.entries.firstOrNull { it.name == preferences.getString(TARGET, null) } ?: QuickLaunchTarget.MANUAL,
        share = app.packageManager.getComponentEnabledSetting(shareEntry) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
    )

    fun setLaunch(on: Boolean) { preferences.edit().putBoolean(LAUNCH, on).apply(); state.value = read() }

    fun setTarget(target: QuickLaunchTarget) { preferences.edit().putString(TARGET, target.name).apply(); state.value = read() }

    fun setShare(on: Boolean) {
        // Kept running: only the share sheet changes.
        app.packageManager.setComponentEnabledSetting(shareEntry,
            if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        state.value = read()
    }

    /**
     * Where to open when the activity starts afresh from the launcher (no saved state: the app was cleared from
     * recents or never ran); null to open as usual.
     */
    fun launchTarget(intent: Intent?, restored: Boolean): QuickLaunchTarget? {
        if (restored || intent == null) return null
        if (intent.action != Intent.ACTION_MAIN || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val current = read()
        return if (current.launch) current.target else null
    }

    companion object {
        /** The share sheet entry in the manifest, an alias of the share activity. */
        const val SHARE_ENTRY = "dev.valnook.app.share.ShareEntry"
        private const val LAUNCH = "launch"
        private const val TARGET = "target"
        @Volatile private var instance: QuickEntryPreferences? = null

        fun of(context: Context): QuickEntryPreferences =
            instance ?: synchronized(this) { instance ?: QuickEntryPreferences(context).also { instance = it } }
    }
}
