package com.wobblebot.papotons.speech

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MAX_ACCEPTABLE_INIT_WAIT_MS = 10_000L

class RecoveringSpeakerTest {
    private class FakeEngine(val callbacks: SpeechEngine.Callbacks) : SpeechEngine {
        var acceptsSpeech = true
        var hasLocalVoice = true
        var configurationFailure: RuntimeException? = null
        var refusedText: String? = null
        var stopCount = 0
        val spoken = mutableListOf<String>()
        val interruptions = mutableListOf<Boolean>()
        var isShutDown = false

        override fun speak(text: String, interrupt: Boolean): Boolean {
            val accepts = acceptsSpeech && text != refusedText
            if (accepts) {
                spoken += text
                interruptions += interrupt
            }
            return accepts
        }

        override fun stop() {
            stopCount++
        }

        override fun configure(): Boolean {
            configurationFailure?.let { throw it }
            return hasLocalVoice
        }

        override fun shutdown() {
            isShutDown = true
        }
    }

    private class FakeFactory : SpeechEngineFactory {
        val engines = mutableListOf<FakeEngine>()
        var failsSynchronously = false
        var acceptsSpeech = true

        override fun create(callbacks: SpeechEngine.Callbacks): SpeechEngine {
            val engine = FakeEngine(callbacks).also { it.acceptsSpeech = acceptsSpeech }
            engines += engine
            if (failsSynchronously) callbacks.onInit(false)
            return engine
        }
    }

    private class FakeScheduler : MainThreadScheduler {
        val pending = mutableListOf<() -> Unit>()
        val delays = mutableListOf<Long>()

        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            delays += delayMs
            pending += action
            return { pending.remove(action) }
        }

        fun runAll() {
            val actions = pending.toList()
            pending.clear()
            actions.forEach { it() }
        }
    }

    private val factory = FakeFactory()
    private val scheduler = FakeScheduler()

    private fun engine(index: Int) = factory.engines[index]

    @Test
    fun `words touched during init are all spoken in order once the engine is ready`() {
        val speaker = RecoveringSpeaker(factory, scheduler)

        speaker.speak("je", interrupt = false)
        speaker.speak("veux", interrupt = false)
        engine(0).callbacks.onInit(true)

        assertEquals(listOf("je", "veux"), engine(0).spoken)
        // Les mots en attente se suivent : aucun ne coupe celui d'avant.
        assertEquals(listOf(false, false), engine(0).interruptions)
        assertEquals(SpeechStatus.Ready, speaker.status.value)
    }

    @Test
    fun `an interruption during init drops only the words waiting before it`() {
        val speaker = RecoveringSpeaker(factory, scheduler)

        speaker.speak("je", interrupt = false)
        speaker.speak("je veux", interrupt = true)
        speaker.speak("pomme", interrupt = false)
        engine(0).callbacks.onInit(true)

        assertEquals(listOf("je veux", "pomme"), engine(0).spoken)
    }

    @Test
    fun `words follow each other while an interruption cuts what is being said`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).callbacks.onInit(true)

        speaker.speak("je", interrupt = false)
        speaker.speak("veux", interrupt = false)
        speaker.speak("je veux", interrupt = true)

        assertEquals(listOf(false, false, true), engine(0).interruptions)
    }

    @Test
    fun `a refusal while speaking the waiting words replays them all on the new engine`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).acceptsSpeech = false
        speaker.speak("je", interrupt = false)
        speaker.speak("veux", interrupt = false)
        speaker.speak("pomme", interrupt = false)

        engine(0).callbacks.onInit(true)
        engine(1).callbacks.onInit(true)

        assertEquals(listOf("je", "veux", "pomme"), engine(1).spoken)
        assertEquals(listOf(false, false, false), engine(1).interruptions)
    }

    @Test
    fun `a refusal in the middle of the waiting words replays the refused word and the ones after it`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).refusedText = "veux"
        speaker.speak("je", interrupt = false)
        speaker.speak("veux", interrupt = false)
        speaker.speak("pomme", interrupt = false)

        engine(0).callbacks.onInit(true)
        engine(1).callbacks.onInit(true)

        assertEquals(listOf("je"), engine(0).spoken)
        assertEquals(listOf("veux", "pomme"), engine(1).spoken)
    }

    @Test
    fun `a second refusal in the middle of the waiting words gives up and forgets them`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        factory.acceptsSpeech = false
        engine(0).acceptsSpeech = false
        speaker.speak("je", interrupt = false)
        speaker.speak("veux", interrupt = false)
        engine(0).callbacks.onInit(true)

        engine(1).callbacks.onInit(true)

        assertEquals(2, factory.engines.size)
        assertEquals(SpeechStatus.Unavailable, speaker.status.value)
        // Une relance demandée part de zéro : les mots abandonnés ne ressortent pas plus tard.
        factory.acceptsSpeech = true
        speaker.recoverIfNeeded()
        engine(2).callbacks.onInit(true)
        assertTrue(engine(2).spoken.isEmpty())
    }

    @Test
    fun `stop cuts the engine and forgets the words waiting for the init`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        speaker.speak("biscuit", interrupt = false)

        speaker.stop()
        engine(0).callbacks.onInit(true)

        assertEquals(1, engine(0).stopCount)
        assertTrue(engine(0).spoken.isEmpty())
    }

    @Test
    fun `an engine without a local voice of the language reports the missing voice`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).hasLocalVoice = false

        engine(0).callbacks.onInit(true)

        assertEquals(SpeechStatus.LanguageMissing, speaker.status.value)
    }

    @Test
    fun `a missing voice still speaks with the voice the engine has`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).hasLocalVoice = false
        engine(0).callbacks.onInit(true)

        speaker.speak("oui", interrupt = false)

        assertEquals(listOf("oui"), engine(0).spoken)
    }

    @Test
    fun `an engine failing before its creation returns is unavailable and leaves no timeout behind`() {
        factory.failsSynchronously = true

        val speaker = RecoveringSpeaker(factory, scheduler)

        assertEquals(SpeechStatus.Unavailable, speaker.status.value)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `an engine that never answers becomes unavailable and forgets the waiting word`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        speaker.speak("aide", interrupt = false)

        scheduler.runAll()

        assertEquals(SpeechStatus.Unavailable, speaker.status.value)
        engine(0).callbacks.onInit(true)
        // La réponse tardive rend la voix, mais le mot attendu avant le délai n'est pas dit.
        assertEquals(SpeechStatus.Ready, speaker.status.value)
        assertTrue(engine(0).spoken.isEmpty())
    }

    @Test
    fun `callbacks from a replaced engine are ignored`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        scheduler.runAll()
        speaker.recoverIfNeeded()

        engine(0).callbacks.onInit(true)
        engine(0).callbacks.onUtteranceError(-1)

        assertEquals(SpeechStatus.Initializing, speaker.status.value)
    }

    @Test
    fun `recovering replaces the old engine and cancels its timeout`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).hasLocalVoice = false
        engine(0).callbacks.onInit(true)

        speaker.recoverIfNeeded()

        assertEquals(2, factory.engines.size)
        assertTrue(engine(0).isShutDown)
        assertEquals(1, scheduler.pending.size)
        assertEquals(SpeechStatus.Initializing, speaker.status.value)
    }

    @Test
    fun `recovering does nothing while the voice works or is starting`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        speaker.recoverIfNeeded()
        engine(0).callbacks.onInit(true)
        speaker.recoverIfNeeded()

        assertEquals(1, factory.engines.size)
    }

    @Test
    fun `a refused utterance recreates the engine once and replays the word`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).callbacks.onInit(true)
        engine(0).acceptsSpeech = false

        speaker.speak("encore", interrupt = false)

        assertTrue(engine(0).isShutDown)
        assertEquals(SpeechStatus.Initializing, speaker.status.value)
        engine(1).callbacks.onInit(true)
        assertEquals(listOf("encore"), engine(1).spoken)
    }

    @Test
    fun `a refusal during the replay does not recreate again`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).callbacks.onInit(true)
        engine(0).acceptsSpeech = false
        factory.acceptsSpeech = false

        speaker.speak("encore", interrupt = false)
        engine(1).callbacks.onInit(true)

        assertEquals(2, factory.engines.size)
        assertEquals(SpeechStatus.Unavailable, speaker.status.value)
    }

    @Test
    fun `the recreation budget comes back after an accepted utterance`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).callbacks.onInit(true)
        engine(0).acceptsSpeech = false
        speaker.speak("un", interrupt = false)
        engine(1).callbacks.onInit(true)

        engine(1).acceptsSpeech = false
        speaker.speak("deux", interrupt = false)
        engine(2).callbacks.onInit(true)

        assertEquals(listOf("deux"), engine(2).spoken)
    }

    @Test
    fun `the recreation budget comes back when the replay was cleared during the init`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).callbacks.onInit(true)
        engine(0).acceptsSpeech = false
        speaker.speak("un", interrupt = false)
        speaker.stop()
        engine(1).callbacks.onInit(true)

        engine(1).acceptsSpeech = false
        speaker.speak("deux", interrupt = false)
        engine(2).callbacks.onInit(true)

        assertEquals(listOf("deux"), engine(2).spoken)
    }

    @Test
    fun `an utterance error raises the alert without speaking the word later`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).callbacks.onInit(true)
        speaker.speak("pomme", interrupt = false)

        engine(0).callbacks.onUtteranceError(-4)

        assertEquals(SpeechStatus.Unavailable, speaker.status.value)
        speaker.recoverIfNeeded()
        engine(1).callbacks.onInit(true)
        assertTrue(engine(1).spoken.isEmpty())
    }

    @Test
    fun `a word touched while unavailable restarts the voice and is spoken once it answers`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        scheduler.runAll()

        speaker.speak("eau", interrupt = false)

        assertEquals(SpeechStatus.Initializing, speaker.status.value)
        engine(1).callbacks.onInit(true)
        assertEquals(listOf("eau"), engine(1).spoken)
    }

    @Test
    fun `an engine is given a few seconds to answer, never longer`() {
        RecoveringSpeaker(factory, scheduler)

        // Au-delà, un mot touché pendant l'init partirait bien trop tard pour avoir encore un sens.
        assertTrue(scheduler.delays.single() in 1..MAX_ACCEPTABLE_INIT_WAIT_MS)
    }

    @Test
    fun `a network error without a local voice keeps the missing voice diagnosis`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).hasLocalVoice = false
        engine(0).callbacks.onInit(true)
        speaker.speak("pomme", interrupt = false)

        engine(0).callbacks.onUtteranceError(TextToSpeech.ERROR_NETWORK)

        assertEquals(SpeechStatus.LanguageMissing, speaker.status.value)
    }

    @Test
    fun `an engine failure without a local voice is still reported and restarted by the next word`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).hasLocalVoice = false
        engine(0).callbacks.onInit(true)
        speaker.speak("pomme", interrupt = false)

        engine(0).callbacks.onUtteranceError(TextToSpeech.ERROR_SERVICE)
        speaker.speak("pain", interrupt = false)

        assertEquals(2, factory.engines.size)
        engine(1).callbacks.onInit(true)
        assertEquals(listOf("pain"), engine(1).spoken)
    }

    @Test
    fun `a word waiting during a failed init is never spoken later`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        speaker.speak("aide", interrupt = false)

        engine(0).callbacks.onInit(false)
        speaker.recoverIfNeeded()
        engine(1).callbacks.onInit(true)

        assertTrue(engine(1).spoken.isEmpty())
    }

    @Test
    fun `a refused word whose recreation fails at once is never spoken later`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).callbacks.onInit(true)
        engine(0).acceptsSpeech = false
        factory.failsSynchronously = true

        speaker.speak("encore", interrupt = false)
        assertEquals(SpeechStatus.Unavailable, speaker.status.value)
        factory.failsSynchronously = false
        speaker.recoverIfNeeded()
        factory.engines.last().callbacks.onInit(true)

        assertTrue(factory.engines.last().spoken.isEmpty())
    }

    @Test
    fun `try again restores the recreation budget`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).callbacks.onInit(true)
        engine(0).acceptsSpeech = false
        factory.acceptsSpeech = false
        speaker.speak("un", interrupt = false)
        engine(1).callbacks.onInit(true)
        assertEquals(SpeechStatus.Unavailable, speaker.status.value)

        speaker.recoverIfNeeded()
        engine(2).callbacks.onInit(true)
        factory.acceptsSpeech = true
        speaker.speak("deux", interrupt = false)

        assertEquals(4, factory.engines.size)
        engine(3).callbacks.onInit(true)
        assertEquals(listOf("deux"), engine(3).spoken)
    }

    @Test
    fun `an engine failing during configuration is unavailable instead of crashing`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).configurationFailure = IllegalStateException("third-party engine")

        engine(0).callbacks.onInit(true)

        assertEquals(SpeechStatus.Unavailable, speaker.status.value)
    }

    @Test
    fun `after shutdown the engine is released and late callbacks change nothing`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        speaker.speak("pain", interrupt = false)

        speaker.shutdown()
        engine(0).callbacks.onInit(true)

        assertTrue(engine(0).isShutDown)
        assertTrue(scheduler.pending.isEmpty())
        assertEquals(SpeechStatus.Initializing, speaker.status.value)
        assertTrue(engine(0).spoken.isEmpty())
    }

    @Test
    fun `after shutdown nothing creates an engine any more`() {
        val speaker = RecoveringSpeaker(factory, scheduler)
        engine(0).callbacks.onInit(true)
        engine(0).acceptsSpeech = false

        speaker.shutdown()
        speaker.speak("x", interrupt = false)
        speaker.recoverIfNeeded()

        assertEquals(1, factory.engines.size)
    }
}
