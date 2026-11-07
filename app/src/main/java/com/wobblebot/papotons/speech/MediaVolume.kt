package com.wobblebot.papotons.speech

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

private const val TAG = "MediaVolume"

/** Un appui remonte le son au moins au tiers du maximum : un seul cran resterait inaudible. */
private const val AUDIBLE_VOLUME_DIVISOR = 3

/**
 * Le volume du flux qui porte la voix (média). Couture de test : le ViewModel se teste sur la JVM
 * avec un faux. Appels sur le thread principal uniquement.
 */
interface MediaVolume {
    /** Vrai quand la voix ne peut pas s'entendre (volume à zéro ou flux coupé). */
    val isMuted: StateFlow<Boolean>

    /** Relit le volume : certains changements (« Ne pas déranger ») ne sont signalés par rien. */
    fun refresh()

    /** Monte le son à un niveau audible. Faux quand il reste coupé (« Ne pas déranger », constructeur). */
    fun raise(): Boolean

    fun release()
}

/**
 * Relu à chaque changement de sortie (casque, Bluetooth : le volume est rangé par sortie) et à
 * chaque écriture d'un volume dans `Settings.System` (touches de volume), en plus des relectures
 * demandées. Une exception d'`AudioManager` ne coupe jamais l'appli : le son est alors supposé
 * audible, et un appui sans effet le dit.
 */
class AndroidMediaVolume @Inject constructor(@ApplicationContext private val context: Context) : MediaVolume {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Une lecture qui lève à chaque mot dit ne remplit pas le journal : seul le premier échec y va. */
    private var hasReportedReadFailure = false

    private val state = MutableStateFlow(readIsMuted())
    override val isMuted: StateFlow<Boolean> = state.asStateFlow()

    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = refresh()
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    init {
        try {
            context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)
            audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
        } catch (exception: RuntimeException) {
            // L'alerte ne se mettra plus à jour qu'aux relectures demandées (chaque mot dit).
            Log.w(TAG, "Volume changes cannot be followed", exception)
        }
    }

    override fun refresh() {
        state.value = readIsMuted()
    }

    override fun raise(): Boolean {
        val manager = audioManager ?: return false
        try {
            if (manager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            // Un flux rétabli retrouve son volume d'avant : on ne règle que s'il reste trop bas.
            val audible = maxOf(1, manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / AUDIBLE_VOLUME_DIVISOR)
            if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) < audible) {
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, audible, AudioManager.FLAG_SHOW_UI)
            }
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Volume could not be raised", exception)
        }
        refresh()
        if (state.value) Log.w(TAG, "Volume still muted after raising: ${describe(manager)}")
        return !state.value
    }

    override fun release() {
        try {
            context.contentResolver.unregisterContentObserver(settingsObserver)
            audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Volume watch could not be released", exception)
        }
    }

    private fun describe(manager: AudioManager): String = try {
        "volume ${manager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, " +
            "muted ${manager.isStreamMute(AudioManager.STREAM_MUSIC)}, ringer mode ${manager.ringerMode}"
    } catch (exception: RuntimeException) {
        "unreadable (${exception.message})"
    }

    private fun readIsMuted(): Boolean {
        val manager = audioManager ?: return false
        return try {
            // Volume fixe (certains boîtiers, casques) : rien à régler, donc rien à signaler.
            !manager.isVolumeFixed &&
                (manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 || manager.isStreamMute(AudioManager.STREAM_MUSIC))
        } catch (exception: RuntimeException) {
            if (!hasReportedReadFailure) Log.w(TAG, "Media volume could not be read", exception)
            hasReportedReadFailure = true
            false
        }
    }
}
