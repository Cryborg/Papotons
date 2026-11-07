package com.wobblebot.papotons.ui.board

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitLockTest {
    @Test
    fun `a locked profile goes through the adult gate`() {
        assertTrue(needsGateToLeave(isExitLocked = true, isEditing = false))
    }

    @Test
    fun `an unlocked profile leaves directly`() {
        assertFalse(needsGateToLeave(isExitLocked = false, isEditing = false))
    }

    /** Prénom pas encore lu : jamais de sortie libre par défaut. */
    @Test
    fun `an unknown profile goes through the adult gate`() {
        assertTrue(needsGateToLeave(isExitLocked = null, isEditing = false))
    }

    /** En édition, l'adulte est déjà passé par le contrôle, comme pour la roue. */
    @Test
    fun `while editing, even a locked profile leaves directly`() {
        assertFalse(needsGateToLeave(isExitLocked = true, isEditing = true))
        assertFalse(needsGateToLeave(isExitLocked = null, isEditing = true))
    }

    @Test
    fun `a locked profile asks for pinning once`() {
        assertTrue(shouldRequestPin(isExitLocked = true, hasRequested = false, isPinned = false))
        // Refusé : pas redemandé avant le prochain retour au premier plan.
        assertFalse(shouldRequestPin(isExitLocked = true, hasRequested = true, isPinned = false))
    }

    /** Déjà épinglée (écran rallumé, épinglage fait à la main) : la confirmation ne revient pas. */
    @Test
    fun `an app already pinned is not asked again`() {
        assertFalse(shouldRequestPin(isExitLocked = true, hasRequested = false, isPinned = true))
    }

    @Test
    fun `an unlocked profile never asks for pinning`() {
        assertFalse(shouldRequestPin(isExitLocked = false, hasRequested = false, isPinned = false))
    }
}
