package com.wobblebot.papotons.speech

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val AUDIBLE_VOLUME_DIVISOR = 3
private const val OBSERVER_TIMEOUT_MS = 5_000L
private const val POLL_INTERVAL_MS = 100L

/** Le vrai volume de l'appareil (émulateur seul : le test change le volume média). */
@RunWith(AndroidJUnit4::class)
class AndroidMediaVolumeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var originalVolume = 0
    private lateinit var mediaVolume: AndroidMediaVolume

    @Before
    fun setUp() {
        assumeFalse("fixed volume: nothing to test", audioManager.isVolumeFixed)
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        instrumentation.runOnMainSync { mediaVolume = AndroidMediaVolume(context) }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { mediaVolume.release() }
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
    }

    @Test
    fun aZeroVolumeIsSeenAndRaisingMakesItAudible() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        instrumentation.runOnMainSync { mediaVolume.refresh() }
        assertTrue(mediaVolume.isMuted.value)

        var isRaised = false
        instrumentation.runOnMainSync { isRaised = mediaVolume.raise() }

        assertTrue(isRaised)
        assertFalse(mediaVolume.isMuted.value)
        // Un seul cran resterait inaudible : au moins le tiers du maximum.
        assertTrue(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) >= audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / AUDIBLE_VOLUME_DIVISOR)
    }

    @Test
    fun theVolumeKeysAreFollowedWithoutAnyRefresh() {
        // Partir d'un son audible, sinon `isMuted` serait vrai d'avance et rien ne serait prouvé.
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 0)
        instrumentation.runOnMainSync { mediaVolume.refresh() }
        assertFalse(mediaVolume.isMuted.value)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        // Aucune relecture demandée : seul l'observateur de `Settings.System` peut le voir. Le
        // système écrit le volume en différé.
        val deadline = System.currentTimeMillis() + OBSERVER_TIMEOUT_MS
        while (!mediaVolume.isMuted.value && System.currentTimeMillis() < deadline) Thread.sleep(POLL_INTERVAL_MS)

        assertTrue(mediaVolume.isMuted.value)
    }
}
