package dev.valnook.feature.ai

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Replies are plain text. The rules ask for no Markdown; should some come anyway, only bold (`**…**`) and list
 * markers (`- `, `* ` at a line start) are turned into what they mean, everything else stays as written.
 */
object AiText {
    private val bold = Regex("""\*\*(.+?)\*\*""")
    private val bullet = Regex("""^(\s*)[-*]\s+""", RegexOption.MULTILINE)

    /** [text] as lines with list markers made bullets, and the stretches to show bold. */
    internal fun parse(text: String): Pair<String, List<IntRange>> {
        val listed = text.replace(bullet) { it.groupValues[1] + "• " }
        val ranges = mutableListOf<IntRange>()
        val out = StringBuilder()
        var last = 0
        bold.findAll(listed).forEach { match ->
            out.append(listed, last, match.range.first)
            val start = out.length
            out.append(match.groupValues[1])
            ranges += start until out.length
            last = match.range.last + 1
        }
        out.append(listed, last, listed.length)
        return out.toString() to ranges
    }

    fun plain(text: String): AnnotatedString {
        val (shown, ranges) = parse(text)
        return buildAnnotatedString {
            var last = 0
            ranges.forEach { range ->
                append(shown.substring(last, range.first))
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(shown.substring(range.first, range.last + 1)) }
                last = range.last + 1
            }
            append(shown.substring(last))
        }
    }
}

/** One-time reminders, remembered on this device: the first picture in a chat, and turning on shared pictures. */
object AiNotices {
    private const val FILE = "valnook_ai_notices"
    const val CHAT_PICTURES = "chat_pictures"
    const val SHARE_PICTURES = "share_pictures"
    const val ENABLE = "enable"

    fun seen(context: Context, key: String): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(key, false)

    fun mark(context: Context, key: String) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key, true).apply()

    fun picturesSeen(context: Context) = seen(context, CHAT_PICTURES)
    fun markPicturesSeen(context: Context) = mark(context, CHAT_PICTURES)

    /** Forgets every reminder, for clearing all data. */
    fun clear(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
}
