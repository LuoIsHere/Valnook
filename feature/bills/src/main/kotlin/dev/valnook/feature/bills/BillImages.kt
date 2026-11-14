package dev.valnook.feature.bills

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.valnook.designsystem.BillIcons
import dev.valnook.designsystem.FaIcon
import dev.valnook.designsystem.FaIcons
import dev.valnook.domain.repository.BillImageLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** The device's bill pictures, provided by the app; null where pictures are not available (previews, tests). */
val LocalBillImages = staticCompositionLocalOf<BillImageLibrary?> { null }

/** A picture as a page shows it: still loading, there, only in OneDrive, or nowhere known. */
internal sealed interface LoadedImage {
    data object Loading : LoadedImage
    data class Shown(val bitmap: ImageBitmap) : LoadedImage
    data object InCloud : LoadedImage
    data object Missing : LoadedImage
}

/** Bumped when pictures come back from OneDrive, so every page showing them looks again. */
internal object BillImageArrivals { val version = kotlinx.coroutines.flow.MutableStateFlow(0) }

/** Fetches [keys] from OneDrive for a page; true when any arrived. */
internal suspend fun BillImageLibrary.fetchAndShow(keys: Collection<String>): Boolean =
    (runCatching { fetch(keys) }.getOrDefault(0) > 0).also { if (it) BillImageArrivals.version.value++ }

/** Decoded pictures, shared by every page; sized by what they hold, about 16 MB. */
private object BillImageCache {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, value: Bitmap) { cache.put(key, value) }
}

/** Picture [key] decoded to about [longSidePx] on its long side: thumbnails stay small, the viewer gets it whole. */
@Composable internal fun rememberBillImage(key: String, longSidePx: Int): LoadedImage {
    val library = LocalBillImages.current
    val arrivals by BillImageArrivals.version.collectAsState()
    return produceState<LoadedImage>(LoadedImage.Loading, key, longSidePx, library, arrivals) {
        value = withContext(Dispatchers.Default) {
            val bytes = library?.read(key) ?: return@withContext when (library?.place(key)) {
                dev.valnook.domain.repository.BillImagePlace.CLOUD -> LoadedImage.InCloud
                else -> LoadedImage.Missing
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext LoadedImage.Missing
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longSidePx) sample *= 2
            val cacheKey = "$key/$sample"
            val bitmap = BillImageCache.get(cacheKey)
                ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.also { BillImageCache.put(cacheKey, it) }
                ?: return@withContext LoadedImage.Missing
            LoadedImage.Shown(bitmap.asImageBitmap())
        }
    }.value
}

/**
 * A square thumbnail of picture [key]. One only in OneDrive is fetched when tapped; one nowhere known shows as missing.
 * Tapping a picture that is here calls [onClick].
 */
@Composable internal fun BillImageThumb(key: String, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val px = with(LocalDensity.current) { size.roundToPx() } * 2
    val image = rememberBillImage(key, px)
    val library = LocalBillImages.current
    val scope = rememberCoroutineScope()
    var fetching by remember(key) { mutableStateOf(false) }
    Box(modifier.size(size).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerHigh).clickable {
        if (image == LoadedImage.InCloud && library != null && !fetching) scope.launch {
            fetching = true
            try { library.fetchAndShow(listOf(key)) } finally { fetching = false }
        } else if (image is LoadedImage.Shown) onClick()
    }, contentAlignment = Alignment.Center) {
        when {
            fetching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            image is LoadedImage.Shown -> Image(image.bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            image == LoadedImage.InCloud -> CloudImage(compact = true)
            image == LoadedImage.Missing -> MissingImage(compact = true)
        }
    }
}

/** What stands in for a picture that is only in OneDrive: tapping it fetches it. */
@Composable internal fun CloudImage(compact: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(4.dp).testTag("bill-image-in-cloud"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FaIcon(FaIcons.cloud, null, Modifier.size(if (compact) 14.dp else 28.dp), tint = colors.onSurfaceVariant)
        Text(stringResource(R.string.bill_image_in_cloud), style = if (compact) MaterialTheme.typography.labelSmall
            else MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

/** What stands in for a picture that is not on this device. */
@Composable internal fun MissingImage(compact: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(4.dp).testTag("bill-image-missing"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FaIcon(BillIcons.resource("paperclip"), null, Modifier.size(if (compact) 14.dp else 28.dp), tint = colors.onSurfaceVariant)
        Text(stringResource(R.string.bill_image_missing), style = if (compact) MaterialTheme.typography.labelSmall
            else MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

/** A bill's pictures in a row of thumbnails; tapping one opens them full screen from there. */
@Composable internal fun BillImageStrip(keys: List<String>, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    Row(modifier.testTag("bill-images"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEachIndexed { index, key ->
            BillImageThumb(key, 72.dp, { open = index }, Modifier.testTag("bill-image-$index"))
        }
    }
    open?.let { start -> BillImageViewer(keys, start) { open = null } }
}

/** Pictures full screen: swipe between them, pinch or double-tap to zoom. */
@Composable internal fun BillImageViewer(keys: List<String>, start: Int, onDismiss: () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pager = rememberPagerState(start.coerceIn(0, (keys.size - 1).coerceAtLeast(0))) { keys.size }
        var zoomed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("bill-image-viewer")) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { keys[it] }) { page ->
                ZoomableImage(keys[page], onZoom = { if (page == pager.currentPage) zoomed = it })
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss, Modifier.testTag("bill-image-close")) {
                    FaIcon(FaIcons.xmark, stringResource(R.string.bill_image_close), Modifier.size(20.dp), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                if (keys.size > 1) Text("${pager.currentPage + 1} / ${keys.size}", Modifier.padding(end = 12.dp),
                    color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable private fun ZoomableImage(key: String, onZoom: (Boolean) -> Unit) {
    val image = rememberBillImage(key, BILL_IMAGE_FULL_PX)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale > 1f) { onZoom(scale > 1f) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize()
        .pointerInput(key) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) }
        .transformable(transform, canPan = { scale > 1f }), contentAlignment = Alignment.Center) {
        when (image) {
            is LoadedImage.Shown -> Image(image.bitmap, null, Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }.semantics { contentDescription = key }, contentScale = ContentScale.Fit)
            LoadedImage.Missing -> Box(Modifier.clip(CircleShape)) { MissingImage(compact = false) }
            LoadedImage.InCloud -> {
                val library = LocalBillImages.current
                val scope = rememberCoroutineScope()
                var fetching by remember { mutableStateOf(false) }
                var failed by remember { mutableStateOf(false) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        CloudImage(compact = false)
                    }
                    if (fetching) CircularProgressIndicator(color = Color.White)
                    else Button({
                        if (library != null) scope.launch {
                            fetching = true; failed = false
                            try { failed = !library.fetchAndShow(listOf(key)) } finally { fetching = false }
                        }
                    }, Modifier.testTag("bill-image-fetch")) { Text(stringResource(R.string.bill_image_fetch)) }
                    if (failed) Text(stringResource(R.string.bill_image_fetch_failed), color = Color.White,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            LoadedImage.Loading -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/** The viewer decodes pictures whole: they are stored at most 3000 on the long side. */
private const val BILL_IMAGE_FULL_PX = 3000
