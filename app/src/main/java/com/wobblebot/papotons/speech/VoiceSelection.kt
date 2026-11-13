package com.wobblebot.papotons.speech

import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.MissingResourceException

/**
 * Vrai quand `TextToSpeech.setLanguage` a trouvé la langue. Une voix fr-FR répond
 * `LANG_COUNTRY_AVAILABLE` (1), pas `LANG_AVAILABLE` (0) : tout code positif ou nul convient.
 */
fun isLanguageAvailable(languageResult: Int): Boolean = languageResult >= TextToSpeech.LANG_AVAILABLE

/**
 * Erreurs d'énoncé dues à une voix absente ou réseau (attendues sans voix locale), par opposition
 * à une panne du moteur (service tombé, synthèse ou sortie audio en échec).
 */
fun isMissingVoiceError(errorCode: Int): Boolean = errorCode in MISSING_VOICE_ERRORS

private val MISSING_VOICE_ERRORS = setOf(
    TextToSpeech.ERROR_NETWORK,
    TextToSpeech.ERROR_NETWORK_TIMEOUT,
    TextToSpeech.ERROR_NOT_INSTALLED_YET,
)

/** Ce qu'on sait d'une voix du moteur, sans dépendre de `android.speech.tts.Voice`. */
data class VoiceOption(val name: String, val locale: Locale, val isOffline: Boolean)

sealed interface VoiceChoice {
    data object KeepCurrent : VoiceChoice

    data class SwitchTo(val name: String) : VoiceChoice

    data object NoOfflineVoice : VoiceChoice
}

/**
 * Parler ne doit jamais dépendre du réseau (invariant 2) : la voix courante est gardée si elle
 * est locale et de la langue voulue, sinon on prend une voix locale de cette langue (le pays
 * exact d'abord). Sans aucune information sur les voix (moteur ancien), on garde le verdict
 * de `setLanguage`.
 */
fun chooseOfflineVoice(current: VoiceOption?, available: List<VoiceOption>?, wanted: Locale): VoiceChoice {
    // Comparé en code ISO 3 : certains moteurs rendent leurs voix en « fra » et non en « fr ».
    fun suits(voice: VoiceOption) = voice.isOffline && voice.locale.iso3Language() == wanted.iso3Language()

    if (current != null && suits(current)) return VoiceChoice.KeepCurrent
    if (current == null && available == null) return VoiceChoice.KeepCurrent
    val best = available.orEmpty()
        .filter(::suits)
        .minByOrNull { if (it.locale == wanted) 0 else 1 }
    return if (best == null) VoiceChoice.NoOfflineVoice else VoiceChoice.SwitchTo(best.name)
}

/** Une langue sans code ISO 3 connu se compare telle quelle. */
private fun Locale.iso3Language(): String = try {
    isO3Language
} catch (exception: MissingResourceException) {
    language
}
