package com.wobblebot.papotons.ui.board

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay

private const val TAG = "ExitLock"

/**
 * Le bouton Bureau passe par le contrôle des adultes : option cochée, ou pas encore lue (jamais de
 * sortie libre par défaut). En édition, l'adulte est déjà passé par le contrôle, comme pour la roue.
 */
fun needsGateToLeave(isExitLocked: Boolean?, isEditing: Boolean): Boolean = !isEditing && isExitLocked != false

/**
 * Demande l'épinglage tant que l'utilisateur ouvert est protégé, s'il n'est pas déjà épinglé et pas encore
 * demandé depuis le dernier vrai départ : un refus n'est redemandé qu'au retour au premier plan.
 */
fun shouldRequestPin(isExitLocked: Boolean, hasRequested: Boolean, isPinned: Boolean): Boolean = isExitLocked && !hasRequested && !isPinned

/**
 * Épingle l'appli (épinglage d'Android, avec sa confirmation) tant que l'utilisateur ouvert est protégé,
 * édition comprise. Seul le bouton Bureau désépingle : un désépinglage verrouille l'appareil s'il a un
 * code, et ce départ terminerait l'édition et fermerait les Réglages.
 */
@Composable
fun ExitLockEffect(isExitLocked: Boolean) {
    val activity = LocalActivity.current ?: return
    // Sauvé : une rotation (qui passe par ON_STOP) ne redemande pas après un refus.
    var hasRequested by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!activity.isChangingConfigurations) hasRequested = false
    }
    // À la reprise, pas au démarrage : Android refuse d'épingler une activité qui n'est pas au premier plan.
    LifecycleResumeEffect(isExitLocked) {
        if (shouldRequestPin(isExitLocked, hasRequested, isPinned(activity))) pin(activity)
        // Option décochée puis recochée : redemandé.
        hasRequested = isExitLocked
        onPauseOrDispose {}
    }
}

/** Android ne signale pas la fin de sa confirmation d'épinglage : l'état se relit à cet intervalle. */
private const val PIN_CHECK_INTERVAL_MS = 1_000L

/**
 * L'appli est-elle épinglée, relu tant que l'appelant l'affiche : un appui long (Retour et Aperçu)
 * désépingle sans quitter l'appli. Toujours faux sans activité.
 */
@Composable
fun rememberIsPinned(): Boolean {
    val activity = LocalActivity.current ?: return false
    val isPinned by produceState(initialValue = remember(activity) { isPinned(activity) }, activity) {
        while (true) {
            delay(PIN_CHECK_INTERVAL_MS)
            value = isPinned(activity)
        }
    }
    return isPinned
}

/** Retourne au bureau de l'appareil, en désépinglant d'abord (épinglée, l'appli ne peut ouvrir le lanceur). */
fun leaveToHomeScreen(activity: Activity) {
    unpin(activity)
    try {
        activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (exception: ActivityNotFoundException) {
        Log.w(TAG, "No home screen activity", exception)
    }
}

private fun isPinned(activity: Activity): Boolean =
    activity.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

private fun pin(activity: Activity) {
    try {
        activity.startLockTask()
    } catch (exception: IllegalArgumentException) {
        Log.w(TAG, "Pinning refused: activity not in the foreground", exception)
    } catch (exception: SecurityException) {
        Log.w(TAG, "Pinning refused", exception)
    } catch (exception: IllegalStateException) {
        Log.w(TAG, "Pinning refused", exception)
    }
}

private fun unpin(activity: Activity) {
    // L'épinglage seulement : un mode kiosque posé par un gestionnaire d'appareils n'est pas le nôtre.
    val state = activity.getSystemService(ActivityManager::class.java).lockTaskModeState
    if (state == ActivityManager.LOCK_TASK_MODE_LOCKED) Log.w(TAG, "Kiosk mode set by a device manager: the home screen stays out of reach")
    if (state != ActivityManager.LOCK_TASK_MODE_PINNED) return
    try {
        activity.stopLockTask()
    } catch (exception: SecurityException) {
        Log.w(TAG, "Unpinning refused: the app was pinned by someone else", exception)
    } catch (exception: IllegalStateException) {
        Log.w(TAG, "Unpinning refused", exception)
    }
}
