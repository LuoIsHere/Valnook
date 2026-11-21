package dev.valnook.app.quick

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import dev.valnook.app.MainActivity
import dev.valnook.app.R
import dev.valnook.feature.settings.QuickLaunchTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * The launcher icon's long-press menu: "New bill" always, "AI bookkeeping" while the AI chat can be used. They open
 * the page whether or not the app is running; a running app shows it above the page it was on.
 */
object QuickShortcuts {
    const val NEW_BILL = "new-bill"
    const val AI_CHAT = "ai-chat"
    private const val ACTION_NEW_BILL = "dev.valnook.app.action.NEW_BILL"
    private const val ACTION_AI_CHAT = "dev.valnook.app.action.AI_CHAT"

    /** Sets the menu on real data: [ai] adds the AI chat. Called while the app is in front, so it is not rate limited. */
    fun publish(context: Context, ai: Boolean) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcuts = buildList {
            add(shortcut(context, NEW_BILL, ACTION_NEW_BILL, R.string.shortcut_new_bill, R.string.shortcut_new_bill_long,
                R.drawable.ic_shortcut_new_bill, 0))
            if (ai) add(shortcut(context, AI_CHAT, ACTION_AI_CHAT, R.string.shortcut_ai_chat, R.string.shortcut_ai_chat_long,
                R.drawable.ic_shortcut_ai_chat, 1))
        }
        runCatching { manager.dynamicShortcuts = shortcuts }
    }

    private fun shortcut(context: Context, id: String, action: String, label: Int, longLabel: Int, icon: Int, rank: Int) =
        ShortcutInfo.Builder(context, id)
            .setShortLabel(context.getString(label))
            .setLongLabel(context.getString(longLabel))
            .setIcon(Icon.createWithResource(context, icon))
            .setRank(rank)
            // Into the app's own task: a running app keeps its pages and gets this one on top.
            .setIntent(Intent(action, null, context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            .build()

    /** The page a shortcut asks for; null for any other intent. */
    fun target(intent: Intent?): QuickLaunchTarget? = when (intent?.action) {
        ACTION_NEW_BILL -> QuickLaunchTarget.MANUAL
        ACTION_AI_CHAT -> QuickLaunchTarget.AI
        else -> null
    }

    /** Tells the launcher a shortcut was used, which it may use to rank it. */
    fun reportUsed(context: Context, target: QuickLaunchTarget) {
        val id = if (target == QuickLaunchTarget.AI) AI_CHAT else NEW_BILL
        runCatching { context.getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(id) }
    }
}

/** A bill page asked for: [shortcut] when a launcher shortcut asked, not the "open to a new bill" setting. */
data class LaunchRequest(val target: QuickLaunchTarget, val shortcut: Boolean)

/** A bill page asked for by a fresh start or a shortcut, waiting for the app to open it once. */
class LaunchRequests {
    private val pending = MutableStateFlow<LaunchRequest?>(null)
    val requests: StateFlow<LaunchRequest?> = pending.asStateFlow()

    fun request(target: QuickLaunchTarget, shortcut: Boolean = false) { pending.value = LaunchRequest(target, shortcut) }

    /** The waiting request, now handled; null when there is none. */
    fun take(): LaunchRequest? = pending.getAndUpdate { null }
}
