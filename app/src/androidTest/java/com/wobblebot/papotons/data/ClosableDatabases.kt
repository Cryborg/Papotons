package com.wobblebot.papotons.data

import android.content.Context

/** [PapotonsDatabases] de l'appli, chaque instance retenue pour être fermée à la fin du test : l'appli ne ferme jamais la sienne. */
class ClosableDatabases(context: Context, name: String) {
    private val opened = mutableListOf<PapotonsDatabase>()

    val databases = PapotonsDatabases(context, name) { database -> synchronized(opened) { opened += database } }

    /** Les instances ouvertes, dans l'ordre. */
    val instances: List<PapotonsDatabase> get() = synchronized(opened) { opened.toList() }

    fun close() = synchronized(opened) { opened.forEach { it.close() } }
}
