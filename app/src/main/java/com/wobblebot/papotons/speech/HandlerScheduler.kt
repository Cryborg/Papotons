package com.wobblebot.papotons.speech

import android.os.Handler
import android.os.Looper
import javax.inject.Inject

class HandlerScheduler @Inject constructor() : MainThreadScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMs)
        return { handler.removeCallbacks(runnable) }
    }
}
