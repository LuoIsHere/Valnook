package dev.valnook.app.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

/** App-owned timings replace Navigation3's 700ms defaults. */
internal object NavigationMotion {
    fun forward(offset_px:Int):ContentTransform =
        (fadeIn(tween(160,easing=FastOutSlowInEasing))+
            slideInHorizontally(tween(160,easing=FastOutSlowInEasing)){offset_px}) togetherWith
            fadeOut(tween(100))

    fun back(offset_px:Int):ContentTransform =
        (fadeIn(tween(140,easing=FastOutSlowInEasing))+
            slideInHorizontally(tween(140,easing=FastOutSlowInEasing)){-offset_px}) togetherWith
            fadeOut(tween(90))

    fun no_preview():ContentTransform=EnterTransition.None togetherWith ExitTransition.None
}
