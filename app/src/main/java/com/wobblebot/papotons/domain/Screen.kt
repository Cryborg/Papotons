package com.wobblebot.papotons.domain

import androidx.compose.runtime.Immutable

/** Un écran d'un utilisateur : un onglet de l'enfant. [size] `null` : grille d'une sauvegarde d'avant les tailles. */
@Immutable
data class Screen(val id: Long, val name: String, val size: BoardSize?)
