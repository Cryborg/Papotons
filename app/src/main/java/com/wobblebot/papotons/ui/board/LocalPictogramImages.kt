package com.wobblebot.papotons.ui.board

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/** L'image d'un picto par son identifiant. Sans fournisseur (aperçus, tests), aucune image. */
val LocalPictogramImages = staticCompositionLocalOf<(Long) -> ImageBitmap?> { { null } }

/** Décode à la demande un picto téléchargé que la case affiche. Sans fournisseur, rien. */
val LocalPictogramLoader = staticCompositionLocalOf<suspend (Long) -> Unit> { {} }

/** L'image d'une photo de case par son nom de fichier. Sans fournisseur, aucune image. */
val LocalPhotoImages = staticCompositionLocalOf<(String) -> ImageBitmap?> { { null } }

/** Décode à la demande la photo qu'une case affiche. Sans fournisseur, rien. */
val LocalPhotoLoader = staticCompositionLocalOf<suspend (String) -> Unit> { {} }
