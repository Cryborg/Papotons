package com.wobblebot.papotons.domain

/** Coupe à [maxLength] sans jamais laisser une moitié d'émoji (paire de substitution). */
internal fun String.takeWhole(maxLength: Int): String {
    val cut = take(maxLength)
    return if (cut.length == maxLength && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}
