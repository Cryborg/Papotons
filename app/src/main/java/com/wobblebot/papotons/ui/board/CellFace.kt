package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.offset
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Picture

private val CELL_CONTENT_PADDING = 6.dp
private val PHOTO_SHAPE = RoundedCornerShape(6.dp)
// Dossier : icône au-dessus du libellé (texte seul) ou marque dans le coin, au-dessus du picto qui
// descend d'autant (une marque devant le libellé ne laissait rien au mot sur téléphone).
private val FOLDER_ICON_SIZE = 20.dp
private val FOLDER_MARK_SIZE = 16.dp

// Picto et libellé : parts fixes de la case.
private const val PICTOGRAM_WEIGHT = 0.68f
private const val LABEL_WEIGHT = 0.32f

// Libellé
// Bas : un mot rapetissé reste un mot, un mot coupé (« finish… ») n'en est plus un. Le plancher grossit
// avec la police système ; il n'est atteint que dans les cases étroites du téléphone (MVP-8).
private val LABEL_MIN_FONT_SIZE = 8.sp
internal val LABEL_MAX_FONT_SIZE = 40.sp
// Sous un picto, un plafond bas : les mots courts ont tous la même taille (une grille régulière se
// lit mieux), seuls les longs rapetissent.
private val PICTOGRAM_LABEL_MAX_FONT_SIZE = 22.sp
// Pas de 2 sp : deux fois moins de mesures de texte par case qu'au pas de 1 sp, à chaque changement de grille.
private val LABEL_FONT_STEP = 2.sp
private const val LABEL_MIN_LINES = 1
private const val LABEL_MAX_LINES = 2
private val LABEL_LINE_HEIGHT = 1.15.em

/** Repères des tests : l'image d'un picto, la marque d'un dossier. */
const val PICTOGRAM_TEST_TAG = "pictogram"
const val FOLDER_MARK_TEST_TAG = "folder_mark"

/**
 * Le dessin d'une case, dans la grille comme dans la page des formes : picto (ou photo) et libellé à
 * leurs parts fixes, ou libellé seul. Un dossier y ajoute sa marque.
 */
@Composable
internal fun CellFace(label: String, picture: Picture?, isFolder: Boolean = false) {
    // fillMaxSize : sans lui, le contenu d'une case en texte seul se colle en haut à gauche.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(CELL_CONTENT_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (picture != null) {
                // Parts fixes : la taille adaptative du libellé ne peut pas écraser le picto,
                // et la place est réservée avant même que l'image ne soit décodée.
                CellPicture(
                    picture,
                    Modifier
                        .weight(PICTOGRAM_WEIGHT)
                        .fillMaxWidth()
                        .then(if (isFolder) Modifier.clearOfFolderMark() else Modifier),
                )
                CellLabel(label, PICTOGRAM_LABEL_MAX_FONT_SIZE, Modifier.weight(LABEL_WEIGHT).fillMaxWidth())
            } else {
                if (isFolder) FolderIcon(Modifier.size(FOLDER_ICON_SIZE))
                CellLabel(label, LABEL_MAX_FONT_SIZE, Modifier)
            }
        }
        if (isFolder && picture != null) {
            FolderIcon(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(CELL_CONTENT_PADDING)
                    .size(FOLDER_MARK_SIZE)
                    .testTag(FOLDER_MARK_TEST_TAG),
            )
        }
    }
}

/** L'image d'une case ou d'un mot : picto ARASAAC ou photo de l'adulte. */
@Composable
internal fun CellPicture(picture: Picture, modifier: Modifier) {
    when (picture) {
        is Picture.Pictogram -> Pictogram(picture.id, modifier)
        is Picture.Photo -> Photo(picture.fileName, modifier)
    }
}

/** Comme un picto : place tenue avant le décodage, décodée à la demande. */
@Composable
private fun Photo(fileName: String, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val image = LocalPhotoImages.current(fileName)
        val load = LocalPhotoLoader.current
        // Relancé si l'image sort du cache borné pendant qu'elle est à l'écran.
        LaunchedEffect(fileName, image == null) { if (image == null) load(fileName) }
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(PHOTO_SHAPE),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Si [modifier] en fixe la taille, la place du picto est tenue avant le décodage : rien ne bouge à son arrivée. */
@Composable
internal fun Pictogram(pictogramId: Long, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // La table des images est un état Compose : le picto se recompose quand elle se remplit.
        val image = LocalPictogramImages.current(pictogramId)
        val load = LocalPictogramLoader.current
        LaunchedEffect(pictogramId) { load(pictogramId) }
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(PICTOGRAM_TEST_TAG),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * Descend le picto d'un dossier sous la marque du coin, seulement s'il la toucherait : dans une case
 * plate (téléphone couché), le picto carré centré passe à côté, et le descendre l'effaçait presque.
 */
private fun Modifier.clearOfFolderMark() = layout { measurable, constraints ->
    val mark = FOLDER_MARK_SIZE.roundToPx()
    val isClearOfMark = constraints.hasBoundedWidth && constraints.hasBoundedHeight && constraints.maxWidth >= constraints.maxHeight + 2 * mark
    val top = if (isClearOfMark) 0 else mark
    val placeable = measurable.measure(constraints.offset(vertical = -top))
    layout(placeable.width, constraints.constrainHeight(placeable.height + top)) { placeable.place(0, top) }
}

@Composable
private fun FolderIcon(modifier: Modifier) {
    Icon(painter = painterResource(R.drawable.ic_folder), contentDescription = null, modifier = modifier)
}

@Composable
internal fun CellLabel(label: String, maxFontSize: TextUnit, modifier: Modifier) {
    val contentColor = LocalContentColor.current
    val titleStyle = MaterialTheme.typography.titleLarge
    // Interligne proportionnel : celui du style, fixe, ferait chevaucher les lignes agrandies.
    val labelStyle = remember(titleStyle) {
        titleStyle.copy(textAlign = TextAlign.Center, lineHeight = LABEL_LINE_HEIGHT)
    }
    // Autant de lignes que de mots (deux au plus) : un mot seul rapetisse au lieu
    // d'être coupé en deux, ce que la taille adaptative ferait pour remplir la case. Seules les
    // espaces ordinaires comptent : « quoi ? » porte une insécable et reste sur une ligne.
    val maxLines = remember(label) {
        label.split(' ').count { it.isNotBlank() }.coerceIn(LABEL_MIN_LINES, LABEL_MAX_LINES)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        BasicText(
            text = label,
            style = labelStyle,
            color = { contentColor },
            maxLines = maxLines,
            // Clip et pas Ellipsis : avec Ellipsis, la taille adaptative ne voit qu'un mot trop large,
            // jamais un mot trop haut, et le bas des lettres sortait des cases basses de la tablette.
            overflow = TextOverflow.Clip,
            autoSize = TextAutoSize.StepBased(
                minFontSize = LABEL_MIN_FONT_SIZE,
                maxFontSize = maxFontSize,
                stepSize = LABEL_FONT_STEP,
            ),
        )
    }
}
