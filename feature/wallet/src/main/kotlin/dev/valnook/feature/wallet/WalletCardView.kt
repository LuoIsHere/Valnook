package dev.valnook.feature.wallet

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.WalletRepository
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WalletDecoded(val bitmap:ImageBitmap,val tint:Long)

class WalletImageCache(private val repository: WalletRepository) {
    private val decoders=Semaphore(2)
    private val generation=java.util.concurrent.atomic.AtomicInteger()
    private val requests=java.util.concurrent.ConcurrentHashMap<String,Mutex>()
    // Composed cards may outlive an LRU entry. Reuse their live bitmap without
    // retaining every decoded card or enlarging the strong cache budget.
    private val liveImages=java.util.concurrent.ConcurrentHashMap<String,java.lang.ref.WeakReference<WalletDecoded>>()
    // A few bytes per face, so a card still waiting for its pixels is drawn in its own colour.
    private val tints=java.util.concurrent.ConcurrentHashMap<String,Long>()
    /** Room for about twenty phone-width faces where the heap allows it, never less than the old fixed 24 MB. */
    val budgetBytes:Int=(Runtime.getRuntime().maxMemory()/8).coerceIn(24L*1024*1024,64L*1024*1024).toInt()
    private val images=object:LruCache<String,WalletDecoded>(budgetBytes){
        override fun sizeOf(key:String,value:WalletDecoded)=value.bitmap.width*value.bitmap.height*4
    }
    // A newly composed shared card must have the same pixels on its very first frame.
    // Widths a few pixels apart (the preload rounds the screen width differently from the laid-out card) share one
    // decode: the face is cropped to the card anyway, so a 1/32 difference cannot be seen.
    private fun cached(key:String,face:String,target:Int)=images.get(key)?:liveImages[key]?.get()?:
        liveImages.entries.asSequence().filter{it.key.startsWith("$face:")}.mapNotNull{it.value.get()}
            .filter{kotlin.math.abs(it.bitmap.width-target)<=target/32}.minByOrNull{kotlin.math.abs(it.bitmap.width-target)}
    fun peek(key:String,edge:Int?=null):WalletDecoded? =
        edge?.let{it.coerceIn(160,1120).let{target->cached("$key:$target",key,target)}}?:liveImages.entries
            .filter{it.key.startsWith("$key:")}.mapNotNull{it.value.get()}.maxByOrNull{it.bitmap.width}
    fun tint(key:String):Long?=tints[key]
    /**
     * Drops only the strong cache. Faces still on screen keep their bitmaps alive anyway, and a card opened from
     * them must find the same pixels on its first frame, so their weak entries and every tint stay.
     */
    fun clear(){generation.incrementAndGet();images.evictAll()}
    suspend fun load(key:String,edge:Int):WalletDecoded?=withContext(Dispatchers.IO) {
        val target=edge.coerceIn(160,1120)
        val cacheKey="$key:$target"
        requests.getOrPut(cacheKey){Mutex()}.withLock {
        cached(cacheKey,key,target)?:decoders.withPermit {
            val ticket=generation.get()
            repository.image(key)?.let { image ->
            tints[key]=image.tint
            decode(image.bytes,target)?.let { WalletDecoded(it.asImageBitmap(),image.tint) }?.also { if(ticket==generation.get()) {
                liveImages.entries.removeAll{entry->entry.value.get()==null}
                liveImages[cacheKey]=java.lang.ref.WeakReference(it)
                images.put(cacheKey,it)
            } }
        }}
        }
    }

    /**
     * Decodes straight to [target] wide: the decoder scales while reading, so no full-size copy is made and then
     * scaled. A software bitmap measured faster here than a GPU one (whose upload moves into the decode); it is
     * handed to the GPU ahead of its first draw instead.
     */
    private fun decode(bytes:ByteArray,target:Int):android.graphics.Bitmap? = runCatching {
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder,info,_ ->
            decoder.allocator=android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val width=info.size.width
            if(width>target)decoder.setTargetSize(target,(info.size.height.toLong()*target/width).toInt().coerceAtLeast(1))
        }.also{it.prepareToDraw()}
    }.getOrNull()
}

@Composable internal fun WalletCardView(card:WalletCard,cache:WalletImageCache,modifier:Modifier=Modifier,
    preview:WalletImage?=null,raised:Boolean=false,glow:(()->Float)?=null,shadow:()->Float={1f}) {
    var tint by remember(card.imageKey,preview?.key) { mutableLongStateOf(card.imageKey?.let{cache.tint(it)?:cache.peek(it)?.tint}?:0xff555555L) }
    val shape=RoundedCornerShape(18.dp)
    val elevation=if(raised)16.dp else 5.dp
    BoxWithConstraints(modifier.widthIn(max=440.dp).fillMaxWidth().aspectRatio(WalletRules.ASPECT)) {
        val density=androidx.compose.ui.platform.LocalDensity.current
        val edge=with(density){maxWidth.roundToPx()}.coerceAtMost(1600)
        val initialImage=remember(card.imageKey,preview?.key,edge){card.imageKey?.let{cache.peek(it,edge)}}
        val image by produceState<WalletDecoded?>(initialImage,card.imageKey,preview?.key,edge) {
            value=if(preview!=null)withContext(Dispatchers.IO){BitmapFactory.decodeByteArray(preview.bytes,0,preview.bytes.size)?.asImageBitmap()?.let { WalletDecoded(it,preview.tint) }}
                else card.imageKey?.let { runCatching { cache.load(it,edge) }.getOrNull() }
            value?.let { tint=it.tint }
        }
        // A face still loading has no glow: a blurred copy of its stand-in reads as a dark ghost card.
        val ready=card.imageKey==null||image!=null
        glow?.takeIf{ready}?.let { WalletGlow(it,shape,Modifier.matchParentSize()) { WalletCardFace(card,image,tint) } }
        // [shadow] is read while drawing, so fading it never recomposes the card.
        Box(Modifier.matchParentSize()
            .graphicsLayer{
                this.shape=RoundedCornerShape(18.dp);clip=false;shadowElevation=elevation.toPx()*shadow()
                ambientShadowColor=Color(tint.toInt());spotShadowColor=Color(tint.toInt())
            }
            .clip(shape).testTag("wallet-card-${card.id}")) {
            WalletCardFace(card,image,tint)
        }
    }
}

/** A card with a picture shows its own colour [tint] until the pixels arrive, never the theme's dark surface. */
@Composable private fun BoxScope.WalletCardFace(card:WalletCard,image:WalletDecoded?,tint:Long) {
    val surface=if(card.imageKey!=null)Color(tint.toInt()) else MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(Modifier.matchParentSize()) {
        drawRect(Brush.linearGradient(listOf(surface,lerp(surface,Color.White,.08f),surface),Offset.Zero,Offset(size.width,size.height)))
        if(card.imageKey==null) {
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha=.11f),Color.Transparent)),size.width*.7f,Offset(size.width*.18f,-size.height*.3f))
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha=.045f),Color.Transparent)),size.width*.65f,Offset(size.width,size.height*1.1f))
        }
    }
    if(card.imageKey==null)card.faceStyle?.let { WalletFaceCanvas(it,Modifier.matchParentSize()) }
    image?.let { Image(it.bitmap,contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
}

/**
 * A blurred, slightly larger copy of a card face behind it, so each part of the card spreads its own edge colour.
 * [strength] is read while drawing: 0 hides the glow, 1 shows it fully.
 */
@Composable internal fun WalletGlow(strength:()->Float,shape:Shape,modifier:Modifier,face:@Composable BoxScope.()->Unit) {
    val peak=if(MaterialTheme.colorScheme.background.luminance()<.5f).225f else .175f
    // One layer: an alpha on a separate parent layer would composite offscreen at the layer size and cut the blur off.
    Box(modifier.graphicsLayer{
        scaleX=1.06f;scaleY=1.06f;alpha=strength().coerceIn(0f,1f)*peak
        val radius=28.dp.toPx();renderEffect=BlurEffect(radius,radius,TileMode.Decal);clip=false
    }.clip(shape),content=face)
}
