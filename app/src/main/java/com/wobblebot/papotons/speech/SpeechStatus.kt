package com.wobblebot.papotons.speech

enum class SpeechStatus {
    Initializing,

    /** Une voix locale de la langue est en place. */
    Ready,

    /** Aucune voix locale de la langue : le moteur parle avec ce qu'il a, il faut en installer une. */
    LanguageMissing,

    /** Le moteur ne répond pas ou n'a pas pu dire le dernier mot : l'appli est muette. */
    Unavailable;

    /** Vrai quand l'utilisateur doit être prévenu. */
    val needsAttention: Boolean get() = this == LanguageMissing || this == Unavailable
}
