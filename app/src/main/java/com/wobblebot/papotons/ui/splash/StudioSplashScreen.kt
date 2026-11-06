package com.wobblebot.papotons.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R
import kotlinx.coroutines.delay

private const val STUDIO_LOGO_DURATION_MS = 1200L
private const val STUDIO_LOGO_FADEOUT_MS = 600
private val LOGO_HORIZONTAL_PADDING = 48.dp

/**
 * Plafond de largeur du logo. Sous 600 dp (donc sur tout téléphone debout) il n'a
 * rien à retenir et le cadrage ne bouge pas. ⚠️ Sans lui, `fillMaxWidth` nu donnait
 * un logo de 1280 dp de large sur une tablette couchée, où il occupait l'écran entier.
 */
private val LOGO_MAX_WIDTH = 600.dp

/**
 * Fond [Color.Black] et non un token du thème : ce carton ne représente pas l'appli
 * mais l'éditeur, il doit rester identique d'une appli à l'autre quel que soit le thème
 * de celui qui l'héberge. C'est aussi la couleur du splash système qui le précède
 * (`windowSplashScreenBackground`) : aucun saut de couleur entre les deux.
 */
private val STUDIO_BACKGROUND = Color.Black

/**
 * Carton WobbleBot Studio, posé AU-DESSUS du premier écran, qui se compose dessous
 * et apparaît pendant le fondu. [onFinished] est appelé à la fin du fondu : l'hôte
 * retire alors le carton de la composition.
 */
@Composable
fun StudioSplashScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val opacity = remember { Animatable(1f) }
    val currentOnFinished = rememberUpdatedState(onFinished)

    LaunchedEffect(opacity) {
        delay(STUDIO_LOGO_DURATION_MS)
        opacity.animateTo(0f, tween(STUDIO_LOGO_FADEOUT_MS))
        currentOnFinished.value()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // L'opacité change à chaque image du fondu : lue dans `graphicsLayer`,
            // elle ne recompose rien.
            .graphicsLayer { alpha = opacity.value }
            .background(STUDIO_BACKGROUND)
            // Absorbe les taps, sinon ils actionnent le premier écran caché dessous.
            // ⚠️ Pas de `clickable` : il ajouterait un nœud cliquable à l'arbre
            // sémantique et TalkBack annoncerait le carton comme un bouton.
            // `onLongPress` n'est pas du luxe : sans lui, l'appui long traverse.
            .pointerInput(Unit) { detectTapGestures(onTap = {}, onLongPress = {}) },
        contentAlignment = Alignment.Center
    ) {
        StudioLogo()
    }
}

@Composable
private fun StudioLogo() {
    Image(
        painter = painterResource(R.drawable.wobblebot_studio),
        contentDescription = stringResource(R.string.studio_logo_description),
        // ⚠️ L'ordre n'est pas libre : `widthIn` abaisse d'abord la contrainte reçue,
        // puis `fillMaxWidth` remplit ce qui reste. Dans l'autre sens, le plafond
        // n'aurait plus rien à retenir.
        modifier = Modifier
            .widthIn(max = LOGO_MAX_WIDTH)
            .fillMaxWidth()
            .padding(horizontal = LOGO_HORIZONTAL_PADDING),
        contentScale = ContentScale.FillWidth
    )
}

@Preview(showSystemUi = true)
@Composable
private fun StudioSplashScreenPreview() {
    // Une preview statique ne lance pas le `LaunchedEffect` : le carton reste opaque.
    StudioSplashScreen(onFinished = {})
}
