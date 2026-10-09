package dev.valnook.data.image

import android.content.Context
import android.graphics.*
import android.net.Uri
import dev.valnook.domain.model.WalletImage
import dev.valnook.domain.model.WalletRules
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.*

object WalletImages {
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun staticPhoto(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) return true
        if (bytes.copyOfRange(0,8).contentEquals(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10))) {
            var offset = 8
            while (offset + 12 <= bytes.size) {
                val length = ByteBuffer.wrap(bytes,offset,4).int
                if (length < 0 || length.toLong() + offset + 12 > bytes.size) return false
                val type = String(bytes,offset+4,4,Charsets.US_ASCII)
                if (type == "acTL") return false
                if (type == "IEND") return true
                offset += length + 12
            }
            return false
        }
        if (String(bytes,0,4,Charsets.US_ASCII) != "RIFF" || String(bytes,8,4,Charsets.US_ASCII) != "WEBP") return false
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val type = String(bytes,offset,4,Charsets.US_ASCII)
            val length = ByteBuffer.wrap(bytes,offset+4,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (length < 0 || offset.toLong() + 8 + length > bytes.size) return false
            if (type == "ANIM" || type == "ANMF" || (type == "VP8X" && length > 0 && bytes[offset+8].toInt() and 2 != 0)) return false
            offset += 8 + length + (length and 1)
        }
        return offset == bytes.size
    }

    fun read(context: Context, uri: Uri): Bitmap {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break
                require(out.size() + n <= WalletRules.MAX_INPUT_BYTES); out.write(buffer,0,n) }
            out.toByteArray()
        }
        require(staticPhoto(bytes))
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            require(info.size.width.toLong()*info.size.height <= WalletRules.MAX_PIXELS)
            val factor = min(1f, 2400f / max(info.size.width,info.size.height))
            decoder.setTargetSize(max(1,(info.size.width*factor).toInt()),max(1,(info.size.height*factor).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    fun cropRect(width: Int,height: Int,targetWidth: Float,targetHeight: Float,zoom: Float,x: Float,y: Float): RectF {
        val scale = max(targetWidth/width,targetHeight/height)*zoom.coerceIn(1f,5f)
        val w=width*scale; val h=height*scale
        val dx=(x*targetWidth).coerceIn(-(w-targetWidth)/2,(w-targetWidth)/2)
        val dy=(y*targetHeight).coerceIn(-(h-targetHeight)/2,(h-targetHeight)/2)
        return RectF((targetWidth-w)/2+dx,(targetHeight-h)/2+dy,(targetWidth+w)/2+dx,(targetHeight+h)/2+dy)
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
                    if (bytes.size <= WalletRules.MAX_IMAGE_BYTES) return WalletImage(digest(bytes),bytes,width,height,tint(bitmap))
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

    fun valid(image: WalletImage): Boolean {
        if(image.bytes.isEmpty() || image.bytes.size>WalletRules.MAX_IMAGE_BYTES || digest(image.bytes)!=image.key ||
            image.width !in 1..1600 || image.height !in 1..1600 || abs(image.width.toFloat()/image.height-WalletRules.ASPECT)>.01f) return false
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.size,options)
        if(options.outWidth!=image.width || options.outHeight!=image.height || options.outMimeType!="image/webp" || !staticPhoto(image.bytes))return false
        val decoded=BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.size)?:return false
        decoded.recycle();return true
    }
}
