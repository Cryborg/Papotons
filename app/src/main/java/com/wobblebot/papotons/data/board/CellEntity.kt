package com.wobblebot.papotons.data.board

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * L'index unique (grille, ligne, colonne) interdit deux cases au même emplacement (invariant 3).
 * Un dossier vise sa grille en RESTRICT : supprimer une grille encore visée doit échouer,
 * plutôt que de changer le dossier en mot qui parle sans que personne ne l'ait voulu.
 */
@Entity(
    tableName = "cells",
    foreignKeys = [
        ForeignKey(
            entity = BoardEntity::class,
            parentColumns = ["id"],
            childColumns = ["board_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BoardEntity::class,
            parentColumns = ["id"],
            childColumns = ["linked_board_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["board_id", "grid_row", "grid_column"], unique = true),
        Index(value = ["linked_board_id"]),
    ],
)
data class CellEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "board_id") val boardId: Long,
    // Colonnes nommées grid_* : `row` et `column` sont des mots-clés SQLite.
    @ColumnInfo(name = "grid_row") val row: Int,
    @ColumnInfo(name = "grid_column") val column: Int,
    val label: String,
    @ColumnInfo(name = "linked_board_id") val linkedBoardId: Long?,
)
