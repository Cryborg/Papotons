package com.wobblebot.papotons.speech

import kotlinx.coroutines.flow.StateFlow

/** La voix de l'appli. Appels sur le thread principal uniquement. */
interface Speaker {
    val status: StateFlow<SpeechStatus>

    /** Prononce [text] en coupant ce qui se disait. */
    fun speak(text: String)

    /** Relance le moteur s'il n'est pas utilisable (voix installée ou moteur réactivé entre-temps). */
    fun recoverIfNeeded()

    fun shutdown()
}
