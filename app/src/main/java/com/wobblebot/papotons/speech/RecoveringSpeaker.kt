package com.wobblebot.papotons.speech

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

private const val TAG = "RecoveringSpeaker"

/** Au-delà, un moteur désactivé ou bloqué ne répondra plus : on le dit au lieu d'attendre. */
internal const val INIT_TIMEOUT_MS = 5_000L

/**
 * La voix de l'appli et son rétablissement : tout silence doit se signaler par le statut (donc
 * par l'alerte), jamais passer inaperçu. Thread principal uniquement.
 *
 * - Chaque moteur créé porte un numéro de génération : tout rappel d'un moteur remplacé est
 *   ignoré. Le numéro, et pas l'instance, parce qu'un moteur peut rappeler `onInit` avant que
 *   sa fabrique ne l'ait rendu, et le même moteur peut rappeler `onInit` après une reconnexion.
 * - L'ancien moteur est éteint et son délai annulé avant d'en créer un autre.
 * - Pendant une recréation le statut vaut [SpeechStatus.Initializing], jamais `Unavailable` :
 *   un retour au premier plan ne relance donc pas une init en cours.
 * - Un mot touché pendant l'init attend (seul le dernier compte) ; il n'existe qu'en
 *   `Initializing`, donc au plus un délai d'init par moteur créé (deux si le rejeu est refusé) :
 *   jamais une parole prise longtemps après l'appui.
 * - Un refus de `speak` éteint le moteur, en recrée un seul et rejoue le mot ; un refus au rejeu
 *   ne recrée plus rien (statut `Unavailable`). Le budget se réarme au premier énoncé accepté et
 *   à chaque relance demandée (retour au premier plan, « Réessayer », mot touché en `Unavailable`).
 * - En `LanguageMissing` on parle quand même, avec la voix que le moteur a : mieux qu'un silence.
 * - Après [shutdown], plus rien ne crée de moteur.
 */
class RecoveringSpeaker @Inject constructor(
    private val engineFactory: SpeechEngineFactory,
    private val scheduler: MainThreadScheduler,
) : Speaker {
    private val mutableStatus = MutableStateFlow(SpeechStatus.Initializing)
    override val status: StateFlow<SpeechStatus> = mutableStatus.asStateFlow()

    private var engine: SpeechEngine? = null
    private var generation = 0
    private var cancelInitTimeout: () -> Unit = {}
    private var pendingText: String? = null
    private var hasRecreatedAfterError = false
    private var isShutDown = false

    init {
        // La création ne fait que lier le service : la lancer tout de suite chauffe le moteur
        // avant le premier mot.
        createEngine()
    }

    override fun speak(text: String) {
        if (isShutDown) return
        when (mutableStatus.value) {
            SpeechStatus.Initializing -> pendingText = text
            SpeechStatus.Ready, SpeechStatus.LanguageMissing -> utter(text)
            // Un appui est aussi une demande de voix : on relance le moteur, et ce mot-ci,
            // tout juste touché, part dès qu'il répond.
            SpeechStatus.Unavailable -> restart(pending = text)
        }
    }

    override fun recoverIfNeeded() {
        if (isShutDown || !mutableStatus.value.needsAttention) return
        restart(pending = null)
    }

    override fun shutdown() {
        isShutDown = true
        generation++
        cancelInitTimeout()
        engine?.shutdown()
        engine = null
        pendingText = null
    }

    private fun restart(pending: String?) {
        hasRecreatedAfterError = false
        // Avant la création : un échec synchrone de l'init doit pouvoir l'effacer.
        pendingText = pending
        createEngine()
    }

    private fun createEngine() {
        cancelInitTimeout()
        engine?.shutdown()
        engine = null
        val engineGeneration = ++generation
        mutableStatus.value = SpeechStatus.Initializing
        // Posé avant la création : un onInit en échec synchrone l'annule aussitôt.
        cancelInitTimeout = scheduler.schedule(INIT_TIMEOUT_MS) { onInitTimeout(engineGeneration) }
        engine = engineFactory.create(object : SpeechEngine.Callbacks {
            override fun onInit(isSuccess: Boolean) = onEngineInit(engineGeneration, isSuccess)
            override fun onUtteranceError(errorCode: Int) = onEngineUtteranceError(engineGeneration, errorCode)
        })
    }

    private fun onInitTimeout(engineGeneration: Int) {
        if (engineGeneration != generation || mutableStatus.value != SpeechStatus.Initializing) return
        Log.e(TAG, "Text-to-speech engine did not answer within $INIT_TIMEOUT_MS ms")
        becomeUnavailable()
    }

    private fun onEngineInit(engineGeneration: Int, isSuccess: Boolean) {
        if (engineGeneration != generation) return
        cancelInitTimeout()
        val currentEngine = engine
        if (!isSuccess || currentEngine == null) {
            Log.e(TAG, "Text-to-speech initialization failed")
            becomeUnavailable()
            return
        }
        val hasLocalVoice = try {
            currentEngine.configure()
        } catch (exception: RuntimeException) {
            // Un moteur tiers peut lever ici : sans ce garde, l'appli planterait à chaque lancement.
            Log.e(TAG, "Text-to-speech configuration failed", exception)
            becomeUnavailable()
            return
        }
        // Honoré même après le délai : un moteur lent qui finit par répondre rend la voix.
        mutableStatus.value = if (hasLocalVoice) SpeechStatus.Ready else SpeechStatus.LanguageMissing
        pendingText?.let { text ->
            pendingText = null
            utter(text)
        }
    }

    private fun onEngineUtteranceError(engineGeneration: Int, errorCode: Int) {
        if (engineGeneration != generation) return
        // Pas de rejeu : le mot est passé, le dire plus tard serait hors de propos.
        // Le prochain mot touché relance le moteur.
        Log.e(TAG, "Utterance failed with error $errorCode")
        // Sans voix locale, un échec réseau est attendu : le diagnostic utile reste « voix à
        // installer ». Toute autre erreur est une panne du moteur, que le prochain mot relancera.
        val isExpectedWithoutLocalVoice = mutableStatus.value == SpeechStatus.LanguageMissing && isMissingVoiceError(errorCode)
        if (!isExpectedWithoutLocalVoice) mutableStatus.value = SpeechStatus.Unavailable
    }

    private fun becomeUnavailable() {
        pendingText = null
        mutableStatus.value = SpeechStatus.Unavailable
    }

    private fun utter(text: String) {
        if (engine?.speak(text) == true) {
            hasRecreatedAfterError = false
            return
        }
        Log.e(TAG, "Text-to-speech refused the utterance")
        if (hasRecreatedAfterError) {
            mutableStatus.value = SpeechStatus.Unavailable
            return
        }
        // Moteur mis à jour ou tué pendant que l'appli vivait : l'instance n'est plus liée.
        // Le mot repart avec un nouveau délai d'init : au pire deux délais après l'appui.
        hasRecreatedAfterError = true
        pendingText = text
        createEngine()
    }
}
