package com.wobblebot.papotons.ui.board

import com.wobblebot.papotons.speech.MediaVolume
import kotlinx.coroutines.flow.MutableStateFlow

class FakeMediaVolume : MediaVolume {
    override val isMuted = MutableStateFlow(false)

    /** Ce que `refresh` lira : le volume réel de l'appareil, changé sans prévenir. */
    var deviceIsMuted = false

    /** Faux : le son ne remonte pas (« Ne pas déranger »). */
    var canRaise = true
    var refreshCount = 0
        private set
    var isReleased = false
        private set

    override fun refresh() {
        refreshCount++
        isMuted.value = deviceIsMuted
    }

    override fun raise(): Boolean {
        if (canRaise) deviceIsMuted = false
        refresh()
        return !isMuted.value
    }

    override fun release() {
        isReleased = true
    }
}
