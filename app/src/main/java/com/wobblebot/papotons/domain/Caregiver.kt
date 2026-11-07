package com.wobblebot.papotons.domain

import androidx.compose.runtime.Immutable

/** Ce que l'écran voit d'un accompagnant : jamais son code, même haché. */
@Immutable
data class Caregiver(val id: Long, val name: String, val codeResetAtMs: Long?)
