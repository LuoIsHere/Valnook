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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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

@Composable internal fun wording(en:String,zh:String):String =
    if(LocalConfiguration.current.locales[0].language=="zh")zh else en

data class WalletDecoded(val bitmap:ImageBitmap,val tint:Long)

class WalletImageCache(private val repository: WalletRepository) {
    private val decoders=Semaphore(2)
    private val generation=java.util.concurrent.atomic.AtomicInteger()
    private val requests=java.util.concurrent.ConcurrentHashMap<String,Mutex>()
    // Composed cards may outlive an LRU entry. Reuse their live bitmap without
    // retaining every decoded card or enlarging the strong cache budget.
    private val liveImages=java.util.concurrent.ConcurrentHashMap<String,java.lang.ref.WeakReference<WalletDecoded>>()
    private val images=object:LruCache<String,WalletDecoded>(24*1024*1024){
        override fun sizeOf(key:String,value:WalletDecoded)=value.bitmap.width*value.bitmap.height*4
    }
    // A newly composed shared card must have the same pixels on its very first frame.
    private fun cached(key:String)=images.get(key)?:liveImages[key]?.get()
    fun peek(key:String,edge:Int?=null):WalletDecoded? =
        edge?.let{cached("$key:${it.coerceIn(160,1120)}")}?:liveImages.entries
            .filter{it.key.startsWith("$key:")}.mapNotNull{it.value.get()}.maxByOrNull{it.bitmap.width}
    fun clear(){generation.incrementAndGet();images.evictAll();liveImages.clear()}
    suspend fun load(key:String,edge:Int):WalletDecoded?=withContext(Dispatchers.IO) {
        val target=edge.coerceIn(160,1120)
        val cacheKey="$key:$target"
        requests.getOrPut(cacheKey){Mutex()}.withLock {
        cached(cacheKey)?:decoders.withPermit {
            val ticket=generation.get()
            repository.image(key)?.let { image ->
            val options=BitmapFactory.Options()
            var sample=1;while(image.width/(sample*2)>=target)sample*=2
            options.inSampleSize=sample
            BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.size,options)?.let { decoded ->
                val sized=if(decoded.width>target)android.graphics.Bitmap.createScaledBitmap(decoded,target,
                    (decoded.height.toFloat()*target/decoded.width).toInt().coerceAtLeast(1),true) else decoded
                if(sized!==decoded)decoded.recycle()
                WalletDecoded(sized.asImageBitmap(),image.tint)
            }?.also { if(ticket==generation.get()) {
                liveImages.entries.removeAll{entry->entry.value.get()==null}
                liveImages[cacheKey]=java.lang.ref.WeakReference(it)
                images.put(cacheKey,it)
            } }
        }}
        }
    }
}

@Composable internal fun WalletCardView(card:WalletCard,cache:WalletImageCache,modifier:Modifier=Modifier,
    preview:WalletImage?=null,raised:Boolean=false) {
    var tint by remember(card.imageKey,preview?.key) { mutableLongStateOf(card.imageKey?.let{cache.peek(it)?.tint}?:0xff555555L) }
    BoxWithConstraints(modifier.widthIn(max=440.dp).fillMaxWidth().aspectRatio(WalletRules.ASPECT)
        .shadow(if(raised)16.dp else 5.dp,RoundedCornerShape(18.dp),clip=false,ambientColor=Color(tint.toInt()),spotColor=Color(tint.toInt()))
        .clip(RoundedCornerShape(18.dp)).testTag("wallet-card-${card.id}")) {
        val density=androidx.compose.ui.platform.LocalDensity.current
        val edge=with(density){maxWidth.roundToPx()}.coerceAtMost(1600)
        val initialImage=remember(card.imageKey,preview?.key,edge){card.imageKey?.let{cache.peek(it,edge)}}
        val image by produceState<WalletDecoded?>(initialImage,card.imageKey,preview?.key,edge) {
            value=if(preview!=null)withContext(Dispatchers.IO){BitmapFactory.decodeByteArray(preview.bytes,0,preview.bytes.size)?.asImageBitmap()?.let { WalletDecoded(it,preview.tint) }}
                else card.imageKey?.let { runCatching { cache.load(it,edge) }.getOrNull() }
            value?.let { tint=it.tint }
        }
        val surface=MaterialTheme.colorScheme.surfaceContainerHigh
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.linearGradient(listOf(surface,lerp(surface,Color.White,.08f),surface),Offset.Zero,Offset(size.width,size.height)))
            if(card.imageKey==null) {
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha=.11f),Color.Transparent)),size.width*.7f,Offset(size.width*.18f,-size.height*.3f))
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha=.045f),Color.Transparent)),size.width*.65f,Offset(size.width,size.height*1.1f))
            }
        }
        image?.let { Image(it.bitmap,contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
    }
}
