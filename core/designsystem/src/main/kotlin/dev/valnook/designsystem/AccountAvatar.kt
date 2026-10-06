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
        val bitmap by produceState<ImageBitmap?>(null) {
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
    "account_balance_wallet" -> R.drawable.account_symbol_account_balance_wallet
    "credit_card" -> R.drawable.account_symbol_credit_card
    "savings" -> R.drawable.account_symbol_savings
    "payments" -> R.drawable.account_symbol_payments
    "wallet" -> R.drawable.account_symbol_wallet
    "trending_up" -> R.drawable.account_symbol_trending_up
    "show_chart" -> R.drawable.account_symbol_show_chart
    "monitoring" -> R.drawable.account_symbol_monitoring
    "business_center" -> R.drawable.account_symbol_business_center
    "home" -> R.drawable.account_symbol_home
    "apartment" -> R.drawable.account_symbol_apartment
    "storefront" -> R.drawable.account_symbol_storefront
    "shopping_bag" -> R.drawable.account_symbol_shopping_bag
    "work" -> R.drawable.account_symbol_work
    "paid" -> R.drawable.account_symbol_paid
    "currency_exchange" -> R.drawable.account_symbol_currency_exchange
    "public" -> R.drawable.account_symbol_public
    "flight" -> R.drawable.account_symbol_flight
    "directions_car" -> R.drawable.account_symbol_directions_car
    "school" -> R.drawable.account_symbol_school
    "family_restroom" -> R.drawable.account_symbol_family_restroom
    "favorite" -> R.drawable.account_symbol_favorite
    "star" -> R.drawable.account_symbol_star
    else -> R.drawable.account_symbol_account_balance
}
