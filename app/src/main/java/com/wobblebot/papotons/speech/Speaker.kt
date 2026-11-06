package com.wobblebot.papotons.speech

import kotlinx.coroutines.flow.StateFlow

/** La voix de l'appli. Appels sur le thread principal uniquement. */
interface Speaker {
    val status: StateFlow<SpeechStatus>

    /**
     * Prononce [text] après ce qui se dit déjà (des mots touchés vite s'entendent tous), ou en
     * coupant tout si [interrupt] (la phrase entière remplace les mots en attente).
     */
    fun speak(text: String, interrupt: Boolean)

    /** Coupe ce qui se dit et oublie les mots en attente. */
    fun stop()

    /** Relance le moteur s'il n'est pas utilisable (voix installée ou moteur réactivé entre-temps). */
    fun recoverIfNeeded()

    fun shutdown()
}
