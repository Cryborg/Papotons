package com.wobblebot.papotons.ui

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Masque la barre de navigation d'une fenêtre : elle revient le temps d'un glissement depuis le bord,
 * par-dessus l'appli, puis repart. La barre d'état reste (heure, batterie).
 */
fun hideNavigationBar(controller: WindowInsetsControllerCompat) {
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.navigationBars())
}
