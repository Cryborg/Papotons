package com.wobblebot.papotons.domain

import androidx.compose.runtime.Immutable

/** Un utilisateur : la personne qui communique, avec ses propres grilles. */
@Immutable
data class Profile(
    val id: Long,
    val name: String,
    /** Quitter vers le bureau passe par le contrôle des adultes, et l'appli demande à s'épingler à l'écran. */
    val isExitLocked: Boolean,
)
