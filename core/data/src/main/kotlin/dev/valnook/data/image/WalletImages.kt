package dev.valnook.data.image

import android.graphics.BitmapFactory
import dev.valnook.domain.model.WalletImage
import dev.valnook.domain.model.WalletImageFormat
import dev.valnook.domain.model.WalletRules
import kotlin.math.abs

/** Storage-side check for card faces before they are saved or restored from a backup. */
object WalletImages {
    fun valid(image: WalletImage): Boolean {
        if(image.bytes.isEmpty() || image.bytes.size>WalletRules.MAX_IMAGE_BYTES || WalletImageFormat.digest(image.bytes)!=image.key ||
            image.width !in 1..1600 || image.height !in 1..1600 || abs(image.width.toFloat()/image.height-WalletRules.ASPECT)>.01f) return false
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.size,options)
        if(options.outWidth!=image.width || options.outHeight!=image.height || options.outMimeType!="image/webp" || !WalletImageFormat.staticPhoto(image.bytes))return false
        val decoded=BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.size)?:return false
        decoded.recycle();return true
    }
}
