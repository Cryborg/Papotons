package com.wobblebot.papotons.ui.board

import com.wobblebot.papotons.data.conjugation.Conjugations
import com.wobblebot.papotons.domain.ConjugationTable
import java.io.File

/** La vraie table embarquée, lue sur la JVM (les tests tournent depuis le dossier du module). */
class FakeConjugations(
    private val table: ConjugationTable = ConjugationTable.parse(File("src/main/assets/conjugations.tsv").readText()),
) : Conjugations {
    override suspend fun table(): ConjugationTable = table
}
