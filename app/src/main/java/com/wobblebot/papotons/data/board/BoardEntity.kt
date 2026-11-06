package com.wobblebot.papotons.data.board

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "boards")
data class BoardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "row_count") val rowCount: Int,
    @ColumnInfo(name = "column_count") val columnCount: Int,
)
