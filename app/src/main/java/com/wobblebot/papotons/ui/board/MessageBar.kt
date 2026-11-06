package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R
import kotlinx.collections.immutable.ImmutableList

/** Hauteur fixe, comme la barre du haut : un message qui s'allonge ne pousse pas la grille. */
private val MESSAGE_BAR_HEIGHT = 72.dp
private val BAR_HORIZONTAL_PADDING = 8.dp
private val SENTENCE_VERTICAL_PADDING = 6.dp
private val SENTENCE_CONTENT_PADDING = 12.dp
private val WORD_SPACING = 10.dp

@Composable
fun MessageBar(
    words: ImmutableList<String>,
    onSpeak: () -> Unit,
    onDeleteLastWord: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasWords = words.isNotEmpty()
    val speakLabel = stringResource(R.string.message_speak)
    val hint = stringResource(R.string.message_hint)
    // LazyRow ne compose que les mots visibles : TalkBack lirait une phrase tronquée.
    val sentence = if (hasWords) words.joinToString(separator = " ") else hint
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MESSAGE_BAR_HEIGHT)
            .padding(horizontal = BAR_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onSpeak,
            enabled = hasWords,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = SENTENCE_VERTICAL_PADDING)
                // TalkBack lit toute la phrase, puis annonce l'action « Dire la phrase ».
                .semantics {
                    contentDescription = sentence
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
        IconButton(onClick = onSpeak, enabled = hasWords) {
            Icon(painterResource(R.drawable.ic_volume_up), speakLabel)
        }
        IconButton(onClick = onDeleteLastWord, enabled = hasWords) {
            Icon(painterResource(R.drawable.ic_backspace), stringResource(R.string.message_delete_last))
        }
        IconButton(onClick = onClear, enabled = hasWords) {
            Icon(painterResource(R.drawable.ic_clear), stringResource(R.string.message_clear))
        }
    }
}

@Composable
private fun SentenceWords(words: ImmutableList<String>) {
    val listState = rememberLazyListState()
    // Le dernier mot ajouté reste toujours visible.
    LaunchedEffect(words.size) {
        listState.animateScrollToItem(words.lastIndex)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = SENTENCE_CONTENT_PADDING),
        horizontalArrangement = Arrangement.spacedBy(WORD_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(words) { word ->
            Text(text = word, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        }
    }
}
