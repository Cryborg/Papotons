package com.wobblebot.papotons.speech

import android.content.ContentResolver
import android.content.Context
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MAXIMUM_VOLUME = 15

/** Ce que `AndroidMediaVolume` lit d'`AudioManager`, sur la JVM (le vrai volume : test instrumenté). */
class AndroidMediaVolumeTest {
    private val audioManager = mockk<AudioManager>(relaxed = true) {
        every { isVolumeFixed } returns false
        every { getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns MAXIMUM_VOLUME
    }
    private val context = mockk<Context> {
        every { getSystemService(AudioManager::class.java) } returns audioManager
        every { contentResolver } returns mockk<ContentResolver>(relaxed = true)
    }

    private fun volumeAt(level: Int, isStreamMute: Boolean = false) {
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns level
        every { audioManager.isStreamMute(AudioManager.STREAM_MUSIC) } returns isStreamMute
    }

    @Test
    fun `a zero volume is muted, a fixed volume never is`() {
        volumeAt(0)
        assertTrue(AndroidMediaVolume(context).isMuted.value)

        every { audioManager.isVolumeFixed } returns true
        assertFalse(AndroidMediaVolume(context).isMuted.value)
    }

    @Test
    fun `a muted stream is muted even with a volume left`() {
        // « Ne pas déranger » en silence total : le volume rangé ne change pas.
        volumeAt(5, isStreamMute = true)

        assertTrue(AndroidMediaVolume(context).isMuted.value)
    }

    @Test
    fun `an audio manager that throws is taken as audible, never as a crash`() {
        every { audioManager.getStreamVolume(any()) } throws IllegalStateException("vendor")
        every { audioManager.registerAudioDeviceCallback(any(), any()) } throws IllegalStateException("vendor")

        val mediaVolume = AndroidMediaVolume(context)

        assertFalse(mediaVolume.isMuted.value)
        mediaVolume.release()
    }

    @Test
    fun `raising a zero volume sets a third of the maximum, raising a muted stream only unmutes it`() {
        volumeAt(0)
        AndroidMediaVolume(context).raise()
        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, MAXIMUM_VOLUME / 3, AudioManager.FLAG_SHOW_UI) }

        volumeAt(12, isStreamMute = true)
        AndroidMediaVolume(context).raise()
        verify { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
        // Rétabli à 12 : on n'ajoute pas un cran que personne n'a demandé.
        verify(exactly = 1) { audioManager.setStreamVolume(any(), any(), any()) }

        // Rétabli à un seul cran : encore inaudible, il remonte au tiers.
        volumeAt(1, isStreamMute = true)
        AndroidMediaVolume(context).raise()
        verify(exactly = 2) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, MAXIMUM_VOLUME / 3, AudioManager.FLAG_SHOW_UI) }
    }

    @Test
    fun `a raise that cannot change the volume says so`() {
        volumeAt(0)
        every { audioManager.setStreamVolume(any(), any(), any()) } throws SecurityException("do not disturb")

        assertFalse(AndroidMediaVolume(context).raise())
    }
}
