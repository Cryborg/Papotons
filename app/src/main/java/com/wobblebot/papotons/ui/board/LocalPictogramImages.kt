package com.wobblebot.papotons.ui.board

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/** L'image d'un picto par son identifiant. Sans fournisseur (aperçus, tests), aucune image. */
val LocalPictogramImages = staticCompositionLocalOf<(Long) -> ImageBitmap?> { { null } }
