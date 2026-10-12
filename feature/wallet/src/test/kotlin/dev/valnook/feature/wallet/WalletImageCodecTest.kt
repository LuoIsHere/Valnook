package dev.valnook.feature.wallet

import dev.valnook.domain.model.WalletRules
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class WalletImageCodecTest {
    // Float rounding must not make the zero-width pan range empty (v0.0.16 crash on a 1578×997 photo).
    // 浮点舍入不能让零宽平移范围变成空区间（v0.0.16 在 1578×997 图片上的闪退）。
    @Test fun crop_never_throws_and_always_covers_the_canvas() {
        val sources=listOf(1578 to 997, 997 to 1578, 900 to 600, 1000 to 631, 1600 to 1009, 320 to 4000)
        for((width,height) in sources) for(canvas in 300..1600) {
            val targetWidth=canvas.toFloat()
            for(targetHeight in listOf((canvas/WalletRules.ASPECT).roundToInt().toFloat(),canvas/WalletRules.ASPECT))
                for(zoom in listOf(1f,1.37f,5f)) for(pan in listOf(-3f,0f,3f)) {
                    val bounds=WalletImageCodec.cropBounds(width,height,targetWidth,targetHeight,zoom,pan,-pan)
                    val slack=.01f
                    assertTrue("$width×$height on $targetWidth×$targetHeight zoom=$zoom pan=$pan: $bounds",
                        bounds.left<=slack && bounds.top<=slack &&
                            bounds.right>=targetWidth-slack && bounds.bottom>=targetHeight-slack)
                }
        }
    }
}
