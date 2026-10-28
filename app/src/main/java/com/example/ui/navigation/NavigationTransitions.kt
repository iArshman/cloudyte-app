package com.example.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

object NavigationTransitions {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val ENTER_MS = 400
    const val EXIT_MS = 200

    fun sharedAxisEnter(forward: Boolean): EnterTransition {
        return slideInHorizontally(tween(ENTER_MS, easing = EmphasizedDecelerate)) { w ->
            if (forward) w / 4 else -w / 4
        } + fadeIn(tween(ENTER_MS / 2, delayMillis = ENTER_MS / 8))
    }

    fun sharedAxisExit(forward: Boolean): ExitTransition {
        return slideOutHorizontally(tween(EXIT_MS, easing = EmphasizedAccelerate)) { w ->
            if (forward) -w / 4 else w / 4
        } + fadeOut(tween(EXIT_MS / 2))
    }

    fun fadeThroughEnter(): EnterTransition {
        return fadeIn(tween(ENTER_MS, delayMillis = ENTER_MS / 4)) + scaleIn(tween(ENTER_MS, easing = EmphasizedDecelerate), initialScale = 0.92f)
    }

    fun fadeThroughExit(): ExitTransition {
        return fadeOut(tween(EXIT_MS)) + scaleOut(tween(EXIT_MS, easing = EmphasizedAccelerate), targetScale = 0.92f)
    }

    fun tabEnter(forward: Boolean): EnterTransition {
        return slideInHorizontally(tween(ENTER_MS, easing = EmphasizedDecelerate)) { w ->
            if (forward) w / 3 else -w / 3
        } + fadeIn(tween(ENTER_MS / 2, delayMillis = ENTER_MS / 8))
    }

    fun tabExit(forward: Boolean): ExitTransition {
        return slideOutHorizontally(tween(EXIT_MS, easing = EmphasizedAccelerate)) { w ->
            if (forward) -w / 3 else w / 3
        } + fadeOut(tween(EXIT_MS / 2))
    }

    fun previewEnter(): EnterTransition {
        return fadeIn(tween(ENTER_MS)) + scaleIn(tween(ENTER_MS, easing = EmphasizedDecelerate), initialScale = 0.85f)
    }

    fun previewExit(): ExitTransition {
        return fadeOut(tween(EXIT_MS)) + scaleOut(tween(EXIT_MS, easing = EmphasizedAccelerate), targetScale = 0.85f)
    }
}

private var lastNavTime = 0L

fun NavController.navigateOnce(route: String, builder: (NavOptionsBuilder.() -> Unit)? = null) {
    val now = System.currentTimeMillis()
    if (now - lastNavTime > 400) {
        lastNavTime = now
        if (builder != null) {
            navigate(route, builder)
        } else {
            navigate(route)
        }
    }
}

fun NavController.popBackStackOnce(): Boolean {
    val now = System.currentTimeMillis()
    if (now - lastNavTime > 400) {
        lastNavTime = now
        return popBackStack()
    }
    return false
}
