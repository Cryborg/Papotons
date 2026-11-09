package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.SentenceToken
import kotlinx.collections.immutable.ImmutableList

private val SENTENCE_CONTENT_PADDING = 12.dp
private val WORD_VERTICAL_PADDING = 4.dp
private val WORD_SPACING = 12.dp

/**
 * La phrase en cours et ses boutons d'effacement, à la hauteur de la barre du haut qui la porte.
 * Toucher la phrase la dit : pas de bouton à part, qui prendrait une cible de plus contre ses voisins.
 */
@Composable
fun MessageBar(
    words: ImmutableList<SentenceToken>,
    onSpeak: () -> Unit,
    onDeleteLastWord: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasWords = words.isNotEmpty()
    val speakLabel = stringResource(R.string.message_speak)
    val hint = stringResource(R.string.message_hint)
    // LazyRow ne compose que les mots visibles : TalkBack lirait une phrase tronquée.
    val sentence = if (hasWords) words.joinToString(separator = " ") { it.text } else hint
    Row(
        modifier = modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(TARGET_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onSpeak,
            enabled = hasWords,
            modifier = Modifier
                // Sa largeur d'au moins une cible est tenue par le seuil de l'accueil dans `BoardTopBar`.
                .weight(1f)
                .fillMaxHeight()
                .padding(top = CHILD_BAR_TOP_PADDING, bottom = CHILD_BAR_BOTTOM_PADDING)
                // TalkBack lit toute la phrase, puis annonce l'action « Dire la phrase ».
                .semantics {
                    contentDescription = sentence
                    // `Surface(onClick)` ne donne aucun rôle : sans lui, TalkBack ne dit pas « bouton ».
                    role = Role.Button
                    onClick(label = speakLabel, action = null)
                },
            shape = BoardCardShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(BOARD_CARD_BORDER_WIDTH, MaterialTheme.colorScheme.outline),
        ) {
            if (hasWords) {
                SentenceWords(words)
            } else {
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = SENTENCE_CONTENT_PADDING)) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        ChildIconButton(painterResource(R.drawable.ic_backspace), stringResource(R.string.message_delete_last), onDeleteLastWord, enabled = hasWords)
        ChildIconButton(painterResource(R.drawable.ic_clear), stringResource(R.string.message_clear), onClear, enabled = hasWords)
    }
}

@Composable
private fun SentenceWords(words: ImmutableList<SentenceToken>) {
    val listState = rememberLazyListState()
    var width by remember { mutableIntStateOf(0) }
    // Le dernier mot reste toujours visible : quand il change sur place (« je » → « j'ai »), et quand
    // la phrase rétrécit (boutons d'un dossier, alerte de voix), qui garderait sinon le début.
    LaunchedEffect(words, width) {
        listState.animateScrollToItem(words.lastIndex)
    }
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width },
        contentPadding = PaddingValues(horizontal = SENTENCE_CONTENT_PADDING),
        horizontalArrangement = Arrangement.spacedBy(WORD_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(words) { word -> SentenceWordCard(word) }
    }
}

/** Comme dans une case : picto dessus, mot centré dessous. */
@Composable
private fun SentenceWordCard(word: SentenceToken) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = WORD_VERTICAL_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Le picto prend la hauteur que le mot lui laisse : une police agrandie le rapetisse au lieu
        // de couper le mot. Un mot sans picto garde sa ligne, celle des autres mots.
        Row(modifier = Modifier.weight(1f)) {
            for (picture in word.pictures) {
                CellPicture(picture, Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true))
            }
        }
        Text(text = word.text, style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false)
    }
}
