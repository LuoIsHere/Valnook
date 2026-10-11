package dev.valnook.domain.model

enum class WalletFaceBackground { SOLID, LINEAR, RADIAL }
enum class WalletFaceGeometry { NONE, RINGS, ARC, DIAGONAL, CIRCLES, BLOCKS }

/** Public FRONT artwork only. Never store private back/edge colors here.
 *  仅公开正面样式；严禁把私密卡背或侧面配色写入此可备份模型。 */
data class WalletFaceStyle(
    val background: WalletFaceBackground = WalletFaceBackground.LINEAR,
    val geometry: WalletFaceGeometry = WalletFaceGeometry.RINGS,
    val base: Long = 0xff687983L,
    val secondary: Long = 0xff303740L,
    val accent: Long = 0xffe4e8edL
) {
    init { require(listOf(base, secondary, accent).all { it in 0xff000000L..0xffffffffL }) }
    fun encode(): String = listOf("1", background.name, geometry.name,
        base.toString(16), secondary.toString(16), accent.toString(16)).joinToString("|")
    companion object {
        fun decode(value: String): WalletFaceStyle {
            require(value.length <= 100)
            val fields = value.split('|')
            require(fields.size == 6 && fields[0] == "1")
            return WalletFaceStyle(WalletFaceBackground.valueOf(fields[1]), WalletFaceGeometry.valueOf(fields[2]),
                fields[3].toLong(16), fields[4].toLong(16), fields[5].toLong(16))
        }
    }
}
