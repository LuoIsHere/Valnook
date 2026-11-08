package dev.valnook.designsystem

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** A loader belongs to one ledger session. Cache immutable content IDs, never account IDs. */
class AccountImageLoader(private val read: suspend (String) -> ByteArray?) {
    private val cache = object : LruCache<String, ImageBitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    /** A photo already decoded, without waiting: shown on the first frame instead of the symbol. */
    fun peek(id: String): ImageBitmap? = cache.get(id)
    suspend fun load(id: String): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            cache.get(id) ?: read(id)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { cache.put(id, it) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

val LocalAccountImageLoader = staticCompositionLocalOf { AccountImageLoader { null } }

@Composable
fun AccountAvatar(symbol: String = "account_balance", imageKey: String? = null,
    modifier: Modifier = Modifier, size: Dp = 36.dp, preview: ByteArray? = null) {
    val loader = LocalAccountImageLoader.current
    // Key the producer's identity too, so a reused row never shows the previous account's bitmap.
    key(loader, imageKey, preview) {
        // Start from the cache, so a row shown again never flashes its symbol first.
        val bitmap by produceState(if (preview == null) imageKey?.let(loader::peek) else null) {
            if (value != null) return@produceState
            value = withContext(Dispatchers.IO) {
                if (preview != null) BitmapFactory.decodeByteArray(preview, 0, preview.size)?.asImageBitmap()
                else imageKey?.let { loader.load(it) }
            }
        }
        Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(painterResource(accountSymbolResource(symbol)), contentDescription = null,
                modifier = Modifier.size(size * .58f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun accountSymbolResource(key: String): Int = when (key) {
    "account_balance_wallet" -> R.drawable.fa_wallet
    "credit_card" -> R.drawable.fa_credit_card
    "savings" -> R.drawable.fa_piggy_bank
    "payments" -> R.drawable.fa_money_bills
    "wallet" -> R.drawable.fa_money_check
    "trending_up" -> R.drawable.fa_arrow_trend_up
    "show_chart" -> R.drawable.fa_chart_line
    "monitoring" -> R.drawable.fa_chart_column
    "business_center" -> R.drawable.fa_briefcase
    "home" -> R.drawable.fa_house
    "apartment" -> R.drawable.fa_building
    "storefront" -> R.drawable.fa_store
    "shopping_bag" -> R.drawable.fa_bag_shopping
    "work" -> R.drawable.fa_suitcase
    "paid" -> R.drawable.fa_circle_dollar_to_slot
    "currency_exchange" -> R.drawable.fa_money_bill_transfer
    "public" -> R.drawable.fa_earth_asia
    "flight" -> R.drawable.fa_plane
    "directions_car" -> R.drawable.fa_car
    "school" -> R.drawable.fa_graduation_cap
    "family_restroom" -> R.drawable.fa_people_roof
    "favorite" -> R.drawable.fa_heart
    "star" -> R.drawable.fa_star
    else -> R.drawable.fa_building_columns
}
