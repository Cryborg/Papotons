package com.wobblebot.papotons.data

/**
 * Plus que le plus grand écran (accueil 7 × 8 et la phrase) : les images d'un même écran ne se
 * chassent pas entre elles. Vaut pour les photos des cases et pour les pictos téléchargés.
 */
internal const val MAX_DECODED_IMAGES = 96

/**
 * Ajoute [key] en ordre d'arrivée ; au-delà de [maxSize], les plus anciennement ajoutées sortent
 * (une case qui les affiche encore les redécode).
 */
internal fun <K, V> Map<K, V>.plusEvictingOldest(key: K, value: V, maxSize: Int = MAX_DECODED_IMAGES): Map<K, V> {
    val updated = LinkedHashMap(this)
    updated[key] = value
    while (updated.size > maxSize) updated.remove(updated.keys.first())
    return updated
}
