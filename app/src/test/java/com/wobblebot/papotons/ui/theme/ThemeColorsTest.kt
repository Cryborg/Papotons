package com.wobblebot.papotons.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.wobblebot.papotons.domain.WordCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN_TEXT_CONTRAST = 4.5f
private const val MIN_BORDER_CONTRAST = 3f
private const val LUMINANCE_OFFSET = 0.05f

/** Lisibilité des cases en basse vision : libellé à 4,5:1 et contour à 3:1 sur chaque fond. */
class ThemeColorsTest {
    private val scheme = PapotonsColorScheme
    /** Tous les fonds de case : les huit catégories, plus le dossier et le mot sans catégorie. */
    private val backgrounds: Map<Any, Color> = WordCategory.entries.associateWith(::wordCategoryBackground) +
        mapOf("folder without category" to scheme.secondaryContainer, "word without category" to scheme.surfaceVariant)

    private fun contrast(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + LUMINANCE_OFFSET) / (darker + LUMINANCE_OFFSET)
    }

    @Test
    fun `every category has its own background`() {
        val categoryBackgrounds = WordCategory.entries.map(::wordCategoryBackground)
        assertEquals(categoryBackgrounds.size, categoryBackgrounds.toSet().size)
    }

    @Test
    fun `the message bar hint stays readable`() {
        assertTrue(contrast(scheme.onSurfaceVariant, scheme.surfaceVariant) >= MIN_TEXT_CONTRAST)
    }

    @Test
    fun `labels stay readable on every category background`() {
        for ((category, background) in backgrounds) {
            assertTrue("$category: ${contrast(scheme.onSurface, background)}", contrast(scheme.onSurface, background) >= MIN_TEXT_CONTRAST)
        }
    }

    @Test
    fun `cell borders stay visible on every background and on the page`() {
        for ((category, background) in backgrounds + ("page" to scheme.background)) {
            assertTrue("$category: ${contrast(scheme.outline, background)}", contrast(scheme.outline, background) >= MIN_BORDER_CONTRAST)
        }
    }
}
