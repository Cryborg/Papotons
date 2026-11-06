package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.ui.theme.wordCategoryBackground
import kotlinx.collections.immutable.ImmutableList

// Grille
private val GRID_PADDING = 8.dp
// 8 dp au moins entre deux cibles : un appui qui tremble ne doit pas toucher la voisine.
private val CELL_SPACING = 8.dp
private val CELL_CONTENT_PADDING = 6.dp

// Dossier : contour épais, et icône au-dessus du libellé (texte seul) ou marque dans le coin, au-dessus
// du picto qui descend d'autant (une marque devant le libellé ne laissait rien au mot sur téléphone).
private val FOLDER_BORDER_WIDTH = 3.dp
private val FOLDER_ICON_SIZE = 20.dp
private val FOLDER_MARK_SIZE = 16.dp

// Picto et libellé : parts fixes de la case.
private const val PICTOGRAM_WEIGHT = 0.68f
private const val LABEL_WEIGHT = 0.32f

// Libellé
// Bas : un mot rapetissé reste un mot, un mot coupé (« finish… ») n'en est plus un. Le plancher grossit
// avec la police système ; il n'est atteint que dans les cases étroites du téléphone (MVP-8).
private val LABEL_MIN_FONT_SIZE = 8.sp
private val LABEL_MAX_FONT_SIZE = 40.sp
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
 * Toutes les cases, à leur position, sans défilement. Forcée de gauche à droite : une langue
 * écrite de droite à gauche ne doit pas renverser les colonnes (invariant 3).
 */
@Composable
fun CommunicationGrid(
    grid: ImmutableList<ImmutableList<Cell?>>,
    onCellClick: (Cell) -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = modifier.padding(GRID_PADDING),
            verticalArrangement = Arrangement.spacedBy(CELL_SPACING),
        ) {
            for (row in grid) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CELL_SPACING),
                ) {
                    for (cell in row) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            if (cell != null) CellButton(cell = cell, onClick = { onCellClick(cell) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellButton(cell: Cell, onClick: () -> Unit) {
    val isFolder = cell.linkedBoardId != null
    val folderDescription = if (isFolder) stringResource(R.string.board_folder_description, cell.label) else null
    val category = cell.category
    val background = when {
        category != null -> wordCategoryBackground(category)
        isFolder -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                role = Role.Button
                if (folderDescription != null) contentDescription = folderDescription
            },
        shape = BoardCardShape,
        color = background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Un dossier a la couleur de sa catégorie, comme un mot : son contour épais et sa marque
        // disent d'un coup d'œil qu'il ouvre une page au lieu de parler.
        border = BorderStroke(
            if (isFolder) FOLDER_BORDER_WIDTH else BOARD_CARD_BORDER_WIDTH,
            MaterialTheme.colorScheme.outline,
        ),
    ) {
        // fillMaxSize : sans lui, le contenu d'une case en texte seul se colle en haut à gauche.
        Box(modifier = Modifier.fillMaxSize()) {
            val pictogramId = cell.pictogramId
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(CELL_CONTENT_PADDING),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (pictogramId != null) {
                    // Parts fixes : la taille adaptative du libellé ne peut pas écraser le picto,
                    // et la place est réservée avant même que l'image ne soit décodée.
                    Pictogram(
                        pictogramId,
                        Modifier
                            .weight(PICTOGRAM_WEIGHT)
                            .fillMaxWidth()
                            .padding(top = if (isFolder) FOLDER_MARK_SIZE else 0.dp),
                    )
                    CellLabel(cell.label, PICTOGRAM_LABEL_MAX_FONT_SIZE, Modifier.weight(LABEL_WEIGHT).fillMaxWidth())
                } else {
                    if (isFolder) FolderIcon(Modifier.size(FOLDER_ICON_SIZE))
                    CellLabel(cell.label, LABEL_MAX_FONT_SIZE, Modifier)
                }
            }
            if (isFolder && pictogramId != null) {
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
}

/** Si [modifier] en fixe la taille, la place du picto est tenue avant le décodage : rien ne bouge à son arrivée. */
@Composable
internal fun Pictogram(pictogramId: Long, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // La table des images est un état Compose : le picto se recompose quand elle se remplit.
        val image = LocalPictogramImages.current(pictogramId)
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

@Composable
private fun FolderIcon(modifier: Modifier) {
    Icon(painter = painterResource(R.drawable.ic_folder), contentDescription = null, modifier = modifier)
}

@Composable
private fun CellLabel(label: String, maxFontSize: TextUnit, modifier: Modifier) {
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
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(
                minFontSize = LABEL_MIN_FONT_SIZE,
                maxFontSize = maxFontSize,
                stepSize = LABEL_FONT_STEP,
            ),
        )
    }
}
