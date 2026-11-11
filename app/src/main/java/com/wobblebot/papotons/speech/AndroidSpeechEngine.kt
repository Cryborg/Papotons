package com.wobblebot.papotons.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.wobblebot.papotons.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

private const val TAG = "AndroidSpeechEngine"
private const val UTTERANCE_ID_PREFIX = "papotons-"

/** [text] ramené à [maxLength] caractères au plus, sans couper un emoji (paire de substitution) en deux. */
internal fun cutForSpeech(text: String, maxLength: Int): String {
    if (text.length <= maxLength) return text
    val cut = text.take(maxLength)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/**
 * `TextToSpeech` sur le contexte d'application : le service du moteur reste lié tant que
 * l'instance vit, il ne doit jamais retenir une activité. Tous les rappels sont ramenés sur le
 * thread principal (le moteur appelle `onInit` et les rappels d'énoncé depuis ses propres threads).
 */
class AndroidSpeechEngine(
    private val context: Context,
    private val callbacks: SpeechEngine.Callbacks,
) : SpeechEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textToSpeech: TextToSpeech = TextToSpeech(context) { status ->
        if (status != TextToSpeech.SUCCESS) Log.e(TAG, "Text-to-speech initialization returned $status")
        mainHandler.post { callbacks.onInit(status == TextToSpeech.SUCCESS) }
    }
    private var utteranceCount = 0L

    /** Une erreur sur un énoncé coupé par une interruption est ignorée : seuls comptent les suivants. */
    private var firstLiveUtterance = 0L

    override fun speak(text: String, interrupt: Boolean): Boolean {
        val utteranceNumber = ++utteranceCount
        if (interrupt) firstLiveUtterance = utteranceNumber
        // À partir de la longueur maximale (incluse) le moteur refuse tout, ce qui passerait pour
        // une panne. La coupe ne tombe jamais au milieu d'un emoji (paire de substitution).
        val maxLength = TextToSpeech.getMaxSpeechInputLength() - 1
        if (text.length > maxLength) Log.w(TAG, "Utterance of ${text.length} characters cut to $maxLength")
        val utterance = cutForSpeech(text, maxLength)
        val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        return textToSpeech.speak(utterance, queueMode, null, UTTERANCE_ID_PREFIX + utteranceNumber) != TextToSpeech.ERROR
    }

    override fun stop() {
        // Les erreurs des énoncés coupés ici ne comptent plus, comme après une interruption.
        firstLiveUtterance = utteranceCount + 1
        textToSpeech.stop()
    }

    override fun configure(): Boolean {
        textToSpeech.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit

            @Deprecated("Remplacée par onError(String, Int)")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                // Certains moteurs ne donnent pas l'id : l'erreur compte alors.
                val utteranceNumber = utteranceId?.removePrefix(UTTERANCE_ID_PREFIX)?.toLongOrNull()
                mainHandler.post {
                    if (utteranceNumber == null || utteranceNumber >= firstLiveUtterance) callbacks.onUtteranceError(errorCode)
                }
            }
        })
        val languageTag = context.getString(R.string.speech_language_tag)
        val locale = Locale.forLanguageTag(languageTag)
        val languageResult = textToSpeech.setLanguage(locale)
        if (!isLanguageAvailable(languageResult)) {
            Log.w(TAG, "No $languageTag voice installed (setLanguage returned $languageResult)")
            return false
        }
        // La langue est là : une erreur pendant le choix de la voix laisse le moteur parler
        // (`LanguageMissing`) au lieu de le déclarer muet.
        return try {
            ensureOfflineVoice(locale, languageTag)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not inspect the engine voices", exception)
            false
        }
    }

    private fun ensureOfflineVoice(locale: Locale, languageTag: String): Boolean {
        // Un ensemble vide vaut « aucune information » (certains moteurs le rendent en cas d'erreur).
        val voices = textToSpeech.voices?.takeIf { it.isNotEmpty() }
        val choice = chooseOfflineVoice(textToSpeech.voice?.toOption(), voices?.map { it.toOption() }, locale)
        return when (choice) {
            VoiceChoice.KeepCurrent -> true
            VoiceChoice.NoOfflineVoice -> {
                if (voices == null) {
                    Log.w(TAG, "The engine did not list its voices; current voice needs the network")
                } else {
                    Log.w(TAG, "No offline $languageTag voice among ${voices.size} voices")
                }
                false
            }
            is VoiceChoice.SwitchTo -> switchVoice(voices, choice.name)
        }
    }

    private fun switchVoice(voices: Set<Voice>?, name: String): Boolean {
        val voice = voices?.firstOrNull { it.name == name }
        val result = voice?.let(textToSpeech::setVoice) ?: TextToSpeech.ERROR
        if (result != TextToSpeech.SUCCESS) Log.w(TAG, "Could not switch to offline voice $name (result $result)")
        return result == TextToSpeech.SUCCESS
    }

    override fun shutdown() {
        textToSpeech.shutdown()
    }

    private fun Voice.toOption() = VoiceOption(
        name = name,
        locale = locale,
        isOffline = !isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty(),
    )

    class Factory @Inject constructor(@ApplicationContext private val context: Context) : SpeechEngineFactory {
        override fun create(callbacks: SpeechEngine.Callbacks): SpeechEngine = AndroidSpeechEngine(context, callbacks)
    }
}
