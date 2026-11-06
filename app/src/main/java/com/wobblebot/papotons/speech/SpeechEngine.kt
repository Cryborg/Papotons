package com.wobblebot.papotons.speech

/**
 * Un moteur de synthèse vocale lié à l'appli. Couture de test : la logique de rétablissement
 * ([RecoveringSpeaker]) se teste sur la JVM avec un moteur factice.
 */
interface SpeechEngine {
    /** Faux quand le moteur refuse l'énoncé (service plus lié). */
    fun speak(text: String): Boolean

    /**
     * Règle sortie audio, langue et voix après un `onInit` réussi. Vrai quand une voix locale
     * de la langue est en place.
     */
    fun configure(): Boolean

    fun shutdown()

    /** Rappels du moteur, toujours livrés sur le thread principal. */
    interface Callbacks {
        fun onInit(isSuccess: Boolean)

        /** Le dernier énoncé accepté n'a pas pu être prononcé (voix absente, réseau exigé, moteur tombé). */
        fun onUtteranceError(errorCode: Int)
    }
}

fun interface SpeechEngineFactory {
    /** Peut appeler `onInit(false)`, jamais `onInit(true)`, avant de rendre la main (aucun moteur installé). */
    fun create(callbacks: SpeechEngine.Callbacks): SpeechEngine
}

/** Planifie une action sur le thread principal ; la fonction rendue l'annule. */
fun interface MainThreadScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
}
