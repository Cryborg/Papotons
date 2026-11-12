package com.wobblebot.papotons.speech

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceSelectionTest {
    private val french = Locale.FRANCE
    private val canadianFrench = Locale.CANADA_FRENCH

    @Test
    fun `a language available at any level is accepted`() {
        // Une voix fr-FR répond LANG_COUNTRY_AVAILABLE, pas LANG_AVAILABLE.
        assertTrue(isLanguageAvailable(TextToSpeech.LANG_AVAILABLE))
        assertTrue(isLanguageAvailable(TextToSpeech.LANG_COUNTRY_AVAILABLE))
        assertTrue(isLanguageAvailable(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE))
    }

    @Test
    fun `missing data or an unsupported language is refused`() {
        assertFalse(isLanguageAvailable(TextToSpeech.LANG_MISSING_DATA))
        assertFalse(isLanguageAvailable(TextToSpeech.LANG_NOT_SUPPORTED))
    }

    @Test
    fun `only network and missing voice errors count as a missing voice`() {
        assertTrue(isMissingVoiceError(TextToSpeech.ERROR_NETWORK))
        assertTrue(isMissingVoiceError(TextToSpeech.ERROR_NETWORK_TIMEOUT))
        assertTrue(isMissingVoiceError(TextToSpeech.ERROR_NOT_INSTALLED_YET))
        assertFalse(isMissingVoiceError(TextToSpeech.ERROR_SERVICE))
        assertFalse(isMissingVoiceError(TextToSpeech.ERROR_SYNTHESIS))
        assertFalse(isMissingVoiceError(TextToSpeech.ERROR_OUTPUT))
    }

    @Test
    fun `an offline voice of the language is kept`() {
        val current = VoiceOption("fr-local", french, isOffline = true)

        assertEquals(VoiceChoice.KeepCurrent, chooseOfflineVoice(current, listOf(current), french))
    }

    @Test
    fun `a network voice is replaced by an offline voice of the exact locale first`() {
        val current = VoiceOption("fr-network", french, isOffline = false)
        val available = listOf(
            current,
            VoiceOption("ca-local", canadianFrench, isOffline = true),
            VoiceOption("fr-local", french, isOffline = true),
        )

        assertEquals(VoiceChoice.SwitchTo("fr-local"), chooseOfflineVoice(current, available, french))
    }

    @Test
    fun `another country of the language is better than the network`() {
        val current = VoiceOption("fr-network", french, isOffline = false)
        val available = listOf(current, VoiceOption("ca-local", canadianFrench, isOffline = true))

        assertEquals(VoiceChoice.SwitchTo("ca-local"), chooseOfflineVoice(current, available, french))
    }

    @Test
    fun `an offline voice named in ISO 3 code counts as the language`() {
        val current = VoiceOption("fr-network", french, isOffline = false)
        val iso3French = Locale.Builder().setLanguage("fra").build()
        val available = listOf(current, VoiceOption("fra-local", iso3French, isOffline = true))

        assertEquals(VoiceChoice.SwitchTo("fra-local"), chooseOfflineVoice(current, available, french))
    }

    @Test
    fun `an offline voice of another language does not count`() {
        val current = VoiceOption("fr-network", french, isOffline = false)
        val available = listOf(current, VoiceOption("en-local", Locale.US, isOffline = true))

        assertEquals(VoiceChoice.NoOfflineVoice, chooseOfflineVoice(current, available, french))
    }

    @Test
    fun `a current offline voice of another language is not kept`() {
        val current = VoiceOption("en-local", Locale.US, isOffline = true)

        assertEquals(VoiceChoice.NoOfflineVoice, chooseOfflineVoice(current, listOf(current), french))
    }

    @Test
    fun `a network voice without any voice list is reported`() {
        val current = VoiceOption("fr-network", french, isOffline = false)

        assertEquals(VoiceChoice.NoOfflineVoice, chooseOfflineVoice(current, null, french))
    }

    @Test
    fun `an engine listing no voice at all has no offline voice`() {
        assertEquals(VoiceChoice.NoOfflineVoice, chooseOfflineVoice(null, emptyList(), french))
    }

    @Test
    fun `a current offline voice is kept even without a voice list`() {
        val current = VoiceOption("fr-local", french, isOffline = true)

        assertEquals(VoiceChoice.KeepCurrent, chooseOfflineVoice(current, null, french))
    }

    @Test
    fun `an engine that tells nothing about its voices keeps the language verdict`() {
        assertEquals(VoiceChoice.KeepCurrent, chooseOfflineVoice(null, null, french))
    }

    @Test
    fun `without a current voice an offline voice of the language is chosen`() {
        val available = listOf(VoiceOption("fr-local", french, isOffline = true))

        assertEquals(VoiceChoice.SwitchTo("fr-local"), chooseOfflineVoice(null, available, french))
    }
}
