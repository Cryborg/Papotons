package com.wobblebot.papotons.update

import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/** Le régime d'une mise à jour lancée depuis les Réglages. */
internal enum class UpdateKind {
    /** Téléchargement en fond pendant qu'on s'en sert, installation au feu vert de l'adulte. */
    Flexible,

    /** Plein écran Play jusqu'à l'installation : seulement quand Play refuse le régime FLEXIBLE. */
    Immediate
}

/** Ce que la ligne de mise à jour des Réglages montre. */
enum class UpdateStatus {
    /** Rien à proposer, ou appli installée hors du Play Store : la ligne n'existe pas. */
    None,

    /** Une version plus récente attend que l'adulte lance son téléchargement. */
    Available,

    /** Le téléchargement accepté est en cours. */
    Downloading,

    /** Téléchargée : il ne manque que le feu vert de l'adulte, qui redémarre l'appli. */
    ReadyToInstall
}

private val INSTALL_UNDERWAY = setOf(InstallStatus.PENDING, InstallStatus.DOWNLOADING, InstallStatus.INSTALLING)

/**
 * Une IMMEDIATE lancée puis laissée en plan se dit DEVELOPER_TRIGGERED : elle se relance depuis
 * les Réglages comme une version disponible.
 */
private val UPDATE_OFFERABLE = setOf(UpdateAvailability.UPDATE_AVAILABLE, UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS)

/**
 * Ce que montrer de la mise à jour, et comment la lancer. Pur, pour se tester sans Play.
 *
 * Rien ne s'ouvre jamais tout seul : l'enfant ne lit pas, et une boîte posée sur sa grille lui
 * retire la parole tant qu'un adulte ne l'a pas fermée (décision du 2026-11-08).
 */
internal object UpdateCheckPolicy {

    fun status(
        updateAvailability: Int,
        installStatus: Int,
        isFlexibleAllowed: Boolean,
        isImmediateAllowed: Boolean,
    ): UpdateStatus = when {
        installStatus == InstallStatus.DOWNLOADED -> UpdateStatus.ReadyToInstall
        // Un téléchargement accepté peut encore se dire UPDATE_AVAILABLE.
        installStatus in INSTALL_UNDERWAY -> UpdateStatus.Downloading
        updateAvailability in UPDATE_OFFERABLE && chooseUpdateKind(isFlexibleAllowed, isImmediateAllowed) != null -> UpdateStatus.Available
        else -> UpdateStatus.None
    }

    fun chooseUpdateKind(isFlexibleAllowed: Boolean, isImmediateAllowed: Boolean): UpdateKind? = when {
        isFlexibleAllowed -> UpdateKind.Flexible
        isImmediateAllowed -> UpdateKind.Immediate
        else -> null
    }
}
