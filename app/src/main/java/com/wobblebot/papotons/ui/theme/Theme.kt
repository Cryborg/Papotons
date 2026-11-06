package com.wobblebot.papotons.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Thème clair et contrasté : les grilles de communication se lisent mieux sur fond clair.
// Le code couleur des catégories de mots (Fitzgerald) viendra ici, en tokens nommés par rôle.
private val PapotonsColorScheme = lightColorScheme(
    primary = Color(0xFF1F5FAD),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF4A4A4A),
)

@Composable
fun PapotonsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PapotonsColorScheme, content = content)
}
