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

    // The navigation bar leaves and returns with the wallet card detail instead of popping.
    fun bar_enter():EnterTransition =
        fadeIn(tween(320,easing=FastOutSlowInEasing))+slideInVertically(tween(320,easing=FastOutSlowInEasing)){it}

    fun bar_exit():ExitTransition =
        fadeOut(tween(180))+slideOutVertically(tween(220,easing=FastOutSlowInEasing)){it}
}
