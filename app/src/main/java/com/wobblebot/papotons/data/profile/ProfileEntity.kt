package com.wobblebot.papotons.data.profile

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** `profile_id` d'une grille construite sans utilisateur : la clé étrangère la refuse à l'écriture. */
const val NO_PROFILE = 0L

/** L'utilisateur que crée la migration 3 → 4 et la graine d'une base vide. */
const val FIRST_PROFILE_ID = 1L

/**
 * Utilisateurs au plus : l'appli n'en crée pas davantage, et la sauvegarde refuse au-delà (ce que
 * l'export écrit, la restauration doit l'accepter).
 */
const val MAX_PROFILES = 100

/** Un nom d'utilisateur, au plus (saisie et restauration). */
const val MAX_PROFILE_NAME_LENGTH = 40

/** Espaces de début et de fin retirés, suites d'espaces réduites à une : la forme gardée d'un nom. */
fun normalizedProfileName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

/** Ce que la sauvegarde accepte : un nom écrit ailleurs ne doit jamais rendre l'export impossible. */
fun isValidProfileName(name: String): Boolean = name.isNotBlank() && name.length <= MAX_PROFILE_NAME_LENGTH

/**
 * Un utilisateur : la personne qui communique, avec ses propres grilles (`boards.profile_id`).
 * [startBoardId] : l'écran de départ choisi avant les écrans ; plus lu ni écrit depuis (le
 * premier écran s'ouvre), seulement lu une fois par la migration 4 → 5 et par une sauvegarde au
 * format 2. La colonne reste : la retirer recréerait la table. [isExitLocked] :
 * le bouton Bureau (quitter vers le bureau de la tablette) demande le contrôle des adultes ; vrai par
 * défaut, un enfant ne quitte pas l'appli par erreur.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "start_board_id") val startBoardId: Long?,
    @ColumnInfo(name = "is_exit_locked", defaultValue = "1") val isExitLocked: Boolean = true,
)
