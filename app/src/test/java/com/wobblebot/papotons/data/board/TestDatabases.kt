package com.wobblebot.papotons.data.board

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.wobblebot.papotons.data.OpenedDatabase
import com.wobblebot.papotons.data.PapotonsDatabases

/**
 * Des instances simulées, une par DAO de [daos] (la dernière resservie) : [lose] perd la courante,
 * [replace] la remplace sur le disque (la suivante s'ouvre au prochain accès), comme une corruption
 * vue en cours d'usage. Une instance perdue le reste.
 */
class TestDatabases(vararg daos: BoardDao) {
    private class Instance {
        @Volatile
        var isLost = false

        @Volatile
        var isReplaced = false
    }

    private val pending = ArrayDeque(daos.toList())
    private var current = Instance()
    private var onReplaced: () -> Unit = {}

    val databases = PapotonsDatabases { onReplaced, _ ->
        this.onReplaced = onReplaced
        val instance = Instance().also { current = it }
        OpenedDatabase(if (pending.size > 1) pending.removeFirst() else pending.first(), { instance.isLost }, { instance.isReplaced })
    }

    fun lose() {
        current.isLost = true
    }

    /** Le drapeau avant le compteur, comme la fabrique. */
    fun replace() {
        current.isLost = true
        current.isReplaced = true
        onReplaced()
    }
}

/** Le dépôt des tests JVM, sur une seule instance qui ne se perd jamais. */
fun BoardRepository(boardDao: BoardDao, preferences: DataStore<Preferences>, context: Context): BoardRepository =
    BoardRepository(TestDatabases(boardDao).databases, preferences, context)
