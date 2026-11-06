package com.wobblebot.papotons.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.wobblebot.papotons.domain.WordCategory

// Thème clair et contrasté : les grilles de communication se lisent mieux sur fond clair.
internal val PapotonsColorScheme = lightColorScheme(
    primary = Color(0xFF1F5FAD),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    // Case mot et fond de la barre de message.
    surfaceVariant = Color(0xFFEEF2F7),
    onSurfaceVariant = Color(0xFF4A4A4A),
    // Contour des cases : au moins 3:1 sur la page et sur chacun des fonds de catégorie.
    outline = Color(0xFF5F6875),
    // Case dossier sans catégorie.
    secondaryContainer = Color(0xFFFFE7A8),
    onSecondaryContainer = Color(0xFF3B2C00),
    // Alerte « voix indisponible ».
    error = Color(0xFFB3261E),
)

// Code couleur de Goossens modifié (Fitzgerald) : un fond par catégorie de mot. Pastels clairs,
// pour que le libellé (onSurface) reste au-dessus de 4,5:1 et le contour au-dessus de 3:1.
private val PeopleBackground = Color(0xFFFFF1A8)
private val VerbBackground = Color(0xFFCDEFC2)
private val DescriptorBackground = Color(0xFFCBE2FA)
private val NounBackground = Color(0xFFFFD9B0)
private val SocialBackground = Color(0xFFF8CFE3)
private val QuestionBackground = Color(0xFFE2D3F5)
private val NegationBackground = Color(0xFFF7C4BF)
private val LittleWordBackground = Color(0xFFECEFF2)

/** Fond d'une case selon la catégorie de son mot (lu aussi par tools/arasaac pour sa planche). */
fun wordCategoryBackground(category: WordCategory): Color = when (category) {
    WordCategory.PEOPLE -> PeopleBackground
    WordCategory.VERB -> VerbBackground
    WordCategory.DESCRIPTOR -> DescriptorBackground
    WordCategory.NOUN -> NounBackground
    WordCategory.SOCIAL -> SocialBackground
    WordCategory.QUESTION -> QuestionBackground
    WordCategory.NEGATION -> NegationBackground
    WordCategory.LITTLE_WORD -> LittleWordBackground
}

@Composable
fun PapotonsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PapotonsColorScheme, content = content)
}
