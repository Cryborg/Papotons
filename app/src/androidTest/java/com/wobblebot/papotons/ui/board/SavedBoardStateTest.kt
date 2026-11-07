package com.wobblebot.papotons.ui.board

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wobblebot.papotons.domain.SentenceWord
import com.wobblebot.papotons.domain.WordCategory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'état que `BoardViewModel` sauve passe par un vrai `Parcel`, comme à la mort du processus. Les
 * tests JVM gardent les objets tels quels et ne verraient pas un type que le `Bundle` refuse.
 */
@RunWith(AndroidJUnit4::class)
class SavedBoardStateTest {
    @Test
    fun theSavedStackAndSentenceComeBackThroughAParcel() {
        val words = listOf(
            SentenceWord("je", WordCategory.PEOPLE, 6632),
            SentenceWord("ai", WordCategory.VERB, null),
        )
        val handle = SavedStateHandle()
        handle[NAVIGATION_KEY] = longArrayOf(1, 2, 3)
        handle[MESSAGE_KEY] = ArrayList(words.map(SavedWord::from))

        val restored = SavedStateHandle.createHandle(throughParcel(handle.savedStateProvider().saveState()), null)

        assertEquals(listOf(1L, 2L, 3L), restored.get<LongArray>(NAVIGATION_KEY)?.toList())
        assertEquals(words, restored.get<ArrayList<SavedWord>>(MESSAGE_KEY)?.map { it.toSentenceWord() })
    }

    private fun throughParcel(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            return checkNotNull(parcel.readBundle(SavedWord::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
    }
}
