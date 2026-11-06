package com.wobblebot.papotons.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Thème clair et contrasté : les grilles de communication se lisent mieux sur fond clair.
private val PapotonsColorScheme = lightColorScheme(
    primary = Color(0xFF1F5FAD),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    // Case mot et fond de la barre de message.
    surfaceVariant = Color(0xFFEEF2F7),
    onSurfaceVariant = Color(0xFF4A4A4A),
    // Seul séparateur des cases sur le fond (le fond des cases est presque blanc) : au moins 3:1.
    outline = Color(0xFF6F7885),
    // Case dossier : se distingue d'un mot par le fond (et par son icône, la couleur ne suffit pas).
    secondaryContainer = Color(0xFFFFE7A8),
    onSecondaryContainer = Color(0xFF3B2C00),
    // Alerte « voix indisponible ».
    error = Color(0xFFB3261E),
)

@Composable
fun PapotonsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PapotonsColorScheme, content = content)
}
