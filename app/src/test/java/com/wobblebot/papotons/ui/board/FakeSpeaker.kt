package com.wobblebot.papotons.ui.board

import com.wobblebot.papotons.speech.Speaker
import com.wobblebot.papotons.speech.SpeechStatus
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSpeaker : Speaker {
    override val status = MutableStateFlow(SpeechStatus.Ready)
    val spoken = mutableListOf<String>()
    var recoverCount = 0
        private set
    var isShutDown = false
        private set

    override fun speak(text: String) {
        spoken += text
    }

    override fun recoverIfNeeded() {
        recoverCount++
    }

    override fun shutdown() {
        isShutDown = true
    }
}
