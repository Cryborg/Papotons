package com.wobblebot.papotons.ui.board

import com.wobblebot.papotons.speech.SpeechStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** Quelle alerte la barre du haut montre, et dans quel ordre. */
class VisibleAlertsTest {
    @Test
    fun `a narrow bar shows only the most important alert`() {
        assertEquals(listOf(TopBarAlert.Speech), visibleAlerts(false, SpeechStatus.Unavailable, isMuted = true, hasLostBoards = true))
        // Voix française manquante : l'appli parle encore, le son coupé passe devant.
        assertEquals(listOf(TopBarAlert.Muted), visibleAlerts(false, SpeechStatus.LanguageMissing, isMuted = true, hasLostBoards = true))
        assertEquals(listOf(TopBarAlert.Speech), visibleAlerts(false, SpeechStatus.LanguageMissing, isMuted = false, hasLostBoards = true))
        assertEquals(listOf(TopBarAlert.Muted), visibleAlerts(false, SpeechStatus.Ready, isMuted = true, hasLostBoards = true))
        assertEquals(listOf(TopBarAlert.LostBoards), visibleAlerts(false, SpeechStatus.Ready, isMuted = false, hasLostBoards = true))
    }

    @Test
    fun `a wide bar shows every alert in a fixed order`() {
        // Ordre fixe quel que soit l'état de la voix : « Son coupé » ne saute pas sous le doigt.
        assertEquals(
            listOf(TopBarAlert.Speech, TopBarAlert.Muted, TopBarAlert.LostBoards),
            visibleAlerts(true, SpeechStatus.LanguageMissing, isMuted = true, hasLostBoards = true),
        )
        assertEquals(
            listOf(TopBarAlert.Speech, TopBarAlert.Muted, TopBarAlert.LostBoards),
            visibleAlerts(true, SpeechStatus.Unavailable, isMuted = true, hasLostBoards = true),
        )
        assertEquals(listOf(TopBarAlert.Muted, TopBarAlert.LostBoards), visibleAlerts(true, SpeechStatus.Ready, isMuted = true, hasLostBoards = true))
    }

    @Test
    fun `nothing to report shows nothing`() {
        assertEquals(emptyList<TopBarAlert>(), visibleAlerts(false, SpeechStatus.Ready, isMuted = false, hasLostBoards = false))
        assertEquals(emptyList<TopBarAlert>(), visibleAlerts(true, SpeechStatus.Initializing, isMuted = false, hasLostBoards = false))
    }
}
