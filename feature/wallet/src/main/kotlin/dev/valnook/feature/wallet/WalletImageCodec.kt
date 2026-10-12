package dev.valnook.feature.wallet

import android.content.Context
import android.graphics.*
import android.net.Uri
import dev.valnook.domain.model.WalletImage
import dev.valnook.domain.model.WalletImageFormat
import dev.valnook.domain.model.WalletRules
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.*

internal data class CropBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Decodes, crops and encodes card faces; storage re-validates the result independently. */
object WalletImageCodec {
    fun read(context: Context, uri: Uri): Bitmap {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break
                require(out.size() + n <= WalletRules.MAX_INPUT_BYTES); out.write(buffer,0,n) }
            out.toByteArray()
        }
        require(WalletImageFormat.staticPhoto(bytes))
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            require(info.size.width.toLong()*info.size.height <= WalletRules.MAX_PIXELS)
            val factor = min(1f, 2400f / max(info.size.width,info.size.height))
            decoder.setTargetSize(max(1,(info.size.width*factor).toInt()),max(1,(info.size.height*factor).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    fun cropRect(width: Int,height: Int,targetWidth: Float,targetHeight: Float,zoom: Float,x: Float,y: Float): RectF =
        cropBounds(width,height,targetWidth,targetHeight,zoom,x,y).let { RectF(it.left,it.top,it.right,it.bottom) }

    /** Pure crop geometry shared by the live preview and the encoder. */
    internal fun cropBounds(width: Int,height: Int,targetWidth: Float,targetHeight: Float,zoom: Float,x: Float,y: Float): CropBounds {
        val scale = max(targetWidth/width,targetHeight/height)*zoom.coerceIn(1f,5f)
        val w=width*scale; val h=height*scale
        // The covering edge can land one ulp short of the canvas; clamp so the pan range is never empty.
        // 铺满的那条边可能因浮点舍入比画布短一个最小精度单位，钳到 0 以免平移范围变成空区间而闪退。
        val rangeX=max(0f,(w-targetWidth)/2); val rangeY=max(0f,(h-targetHeight)/2)
        val dx=(x*targetWidth).coerceIn(-rangeX,rangeX)
        val dy=(y*targetHeight).coerceIn(-rangeY,rangeY)
        return CropBounds((targetWidth-w)/2+dx,(targetHeight-h)/2+dy,(targetWidth+w)/2+dx,(targetHeight+h)/2+dy)
    }

    fun encode(source: Bitmap,zoom: Float,x: Float,y: Float): WalletImage {
        var width = min(WalletRules.MAX_EDGE, max(320,source.width))
        while (width >= 640 || width == min(WalletRules.MAX_EDGE,max(320,source.width))) {
            val height=(width/WalletRules.ASPECT).roundToInt()
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).drawBitmap(source,null,cropRect(source.width,source.height,width.toFloat(),height.toFloat(),zoom,x,y),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                for (quality in listOf(92,85,78)) {
                    val out=ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY,quality,out))
                    val bytes=out.toByteArray()
                    if (bytes.size <= WalletRules.MAX_IMAGE_BYTES) return WalletImage(WalletImageFormat.digest(bytes),bytes,width,height,tint(bitmap))
                }
            } finally { bitmap.recycle() }
            width=(width*.8f).toInt()
        }
        error("Image cannot meet card quality limits")
    }

    private fun tint(bitmap: Bitmap): Long {
        var r=0L;var g=0L;var b=0L;var count=0
        for(y in 0 until bitmap.height step max(1,bitmap.height/24)) for(x in 0 until bitmap.width step max(1,bitmap.width/24)) {
            val c=bitmap.getPixel(x,y);if(Color.alpha(c)<128)continue
            r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c);count++
        }
        if(count==0)return 0xff777777L
        val hsv=FloatArray(3);Color.RGBToHSV((r/count).toInt(),(g/count).toInt(),(b/count).toInt(),hsv)
        hsv[1]=min(hsv[1],.22f);hsv[2]=hsv[2].coerceIn(.3f,.75f)
        return Color.HSVToColor(hsv).toLong() and 0xffffffffL
    }
}
