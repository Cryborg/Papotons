package com.wobblebot.papotons.data.board

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wobblebot.papotons.data.profile.NO_PROFILE
import com.wobblebot.papotons.data.profile.ProfileEntity

/**
 * Une grille appartient à un utilisateur ([profileId], RESTRICT : un utilisateur ne se supprime
 * qu'après ses grilles). [coreBoardId] : l'id, dans `CoreVocabulary`, de la grille du vocabulaire de
 * base dont celle-ci est la copie (c'est par lui qu'on reconnaît l'accueil d'une taille) ; `null`
 * pour une grille de l'adulte. [screenPosition] non nul fait de la grille un écran de son utilisateur
 * (un onglet, dans cet ordre) ; `null` pour une grille de dossier. L'index unique interdit deux écrans
 * au même rang chez un utilisateur (SQLite tient les `NULL` pour distincts : les dossiers ne gênent pas).
 */
@Entity(
    tableName = "boards",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["profile_id"]), Index(value = ["profile_id", "screen_position"], unique = true)],
)
data class BoardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "row_count") val rowCount: Int,
    @ColumnInfo(name = "column_count") val columnCount: Int,
    // Valeurs par défaut seulement pour `CoreVocabulary.kt`, généré sans utilisateur : tout chemin
    // d'écriture les pose. Un oubli de [profileId] est refusé par la clé étrangère.
    @ColumnInfo(name = "profile_id") val profileId: Long = NO_PROFILE,
    @ColumnInfo(name = "core_board_id") val coreBoardId: Long? = null,
    @ColumnInfo(name = "screen_position") val screenPosition: Int? = null,
)
