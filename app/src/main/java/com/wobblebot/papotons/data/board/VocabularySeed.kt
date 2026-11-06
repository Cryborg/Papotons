package com.wobblebot.papotons.data.board

/** Les grilles et les cases à insérer ensemble, en une transaction, au premier lancement. */
class VocabularySeed(val boards: List<BoardEntity>, val cells: List<CellEntity>)
