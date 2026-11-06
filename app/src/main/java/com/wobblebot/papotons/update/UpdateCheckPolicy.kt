package com.wobblebot.papotons.update

/** Le régime d'une mise à jour proposée depuis l'appli. */
internal enum class UpdateKind {
    /** Téléchargement en fond pendant qu'on s'en sert, installation au feu vert de l'utilisateur. */
    Flexible,

    /** Bloquant : plein écran Play jusqu'à l'installation. */
    Immediate
}

/**
 * Quand redemander à Play s'il existe une version plus récente, et quoi proposer.
 * Pur, pour se tester sans Play.
 */
internal object UpdateCheckPolicy {

    /**
     * Écart minimal entre deux demandes. Assez court pour qu'une appli laissée en fond des
     * jours entiers voie passer une version au retour, assez long pour qu'une recréation
     * d'activité ou le retour du dialogue Play lui-même ne redemandent rien.
     */
    const val RECHECK_INTERVAL_MS = 30L * 60L * 1_000L

    /**
     * Priorité Play (0 à 5) à partir de laquelle la mise à jour devient bloquante. Elle
     * se pose À LA PUBLICATION, par l'API Play Developer (`inAppUpdatePriority`), et c'est
     * l'ancienne version installée qui la lit : seul moyen de forcer un utilisateur déjà en
     * retard. Par défaut une release a la priorité 0, donc personne n'est forcé.
     */
    const val IMMEDIATE_MIN_PRIORITY = 4

    /** `lastCheckAtMs` et `nowMs` sur la même horloge MONOTONE (`elapsedRealtime`). */
    fun isCheckDue(lastCheckAtMs: Long?, nowMs: Long): Boolean =
        lastCheckAtMs == null || nowMs - lastCheckAtMs >= RECHECK_INTERVAL_MS

    /**
     * Une version refusée ne se repropose pas : seule une PLUS récente rouvre la question.
     * Sinon chaque retour dans l'appli rejouerait la proposition écartée.
     */
    fun mayPropose(availableVersionCode: Int, declinedVersionCode: Int?): Boolean =
        declinedVersionCode == null || availableVersionCode > declinedVersionCode

    fun isImmediate(updatePriority: Int): Boolean = updatePriority >= IMMEDIATE_MIN_PRIORITY

    /**
     * Le régime à proposer, ou `null` pour ne rien proposer. Une mise à jour bloquante
     * passe outre un refus : on ne la force que quand on ne peut pas laisser tourner
     * l'ancienne version.
     */
    fun chooseUpdateKind(
        updatePriority: Int,
        isImmediateAllowed: Boolean,
        isFlexibleAllowed: Boolean,
        availableVersionCode: Int,
        declinedVersionCode: Int?
    ): UpdateKind? = when {
        isImmediate(updatePriority) && isImmediateAllowed -> UpdateKind.Immediate
        !mayPropose(availableVersionCode, declinedVersionCode) -> null
        isFlexibleAllowed -> UpdateKind.Flexible
        else -> null
    }
}
