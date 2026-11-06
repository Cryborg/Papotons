package com.wobblebot.papotons.domain

/**
 * Catégorie grammaticale d'une case, colorée selon la clé de Goossens modifiée (Fitzgerald) :
 * les couleurs sont dans `ui/theme/Theme.kt`. Persistée par son nom : un nom ne se renomme ni ne
 * se retire jamais (des bases l'ont écrit).
 */
enum class WordCategory {
    PEOPLE,
    VERB,
    DESCRIPTOR,
    NOUN,
    SOCIAL,
    QUESTION,
    NEGATION,
    LITTLE_WORD;

    companion object {
        /** `null` pour une case sans catégorie ou un nom inconnu (base d'une version future). */
        fun fromStoredName(name: String?): WordCategory? = entries.firstOrNull { it.name == name }
    }
}
