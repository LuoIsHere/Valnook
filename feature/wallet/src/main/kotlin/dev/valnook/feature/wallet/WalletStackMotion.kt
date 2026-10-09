package dev.valnook.feature.wallet

import kotlin.math.roundToInt

/** Fixed slots keep animated neighbours out of the drop target calculation. */
internal fun walletDropIndex(position:Float,current:Int,count:Int,step:Float,hysteresis:Float):Int {
    if(count==0||current !in 0 until count||step<=0f)return current
    val distance=position-current*step
    if(kotlin.math.abs(distance)<=step/2f+hysteresis)return current
    return (position/step).roundToInt().coerceIn(0,count-1)
}
