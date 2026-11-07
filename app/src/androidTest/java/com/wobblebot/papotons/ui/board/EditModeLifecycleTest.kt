package com.wobblebot.papotons.ui.board

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La tablette rendue à l'enfant n'est jamais en édition : un vrai départ de l'appli la termine,
 * une rotation (recréation de l'activité) la garde. Sur l'émulateur seulement : l'appli réelle se lance.
 */
@RunWith(AndroidJUnit4::class)
class EditModeLifecycleTest {
    private fun ActivityScenario<MainActivity>.boardViewModel(): BoardViewModel {
        lateinit var viewModel: BoardViewModel
        onActivity { viewModel = ViewModelProvider(it)[BoardViewModel::class.java] }
        return viewModel
    }

    /** L'état suivi par l'écran ; lu au premier plan, quand l'écran le collecte. */
    private fun ActivityScenario<MainActivity>.isEditing(): Boolean {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return boardViewModel().uiState.value.isEditing
    }

    @Test
    fun leavingTheAppEndsTheEditButARotationKeepsIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { ViewModelProvider(it)[BoardViewModel::class.java].onStartEditing() }
            assertTrue(scenario.isEditing())

            scenario.recreate()
            assertTrue("a rotation must keep the edit mode", scenario.isEditing())

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertFalse("leaving the app must end the edit mode", scenario.isEditing())
        }
    }
}
