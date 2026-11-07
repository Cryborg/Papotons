package com.wobblebot.papotons.ui.edit

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R
import com.wobblebot.papotons.ui.board.FoundPictogram
import com.wobblebot.papotons.ui.board.PictogramSearchState
import com.wobblebot.papotons.ui.board.SearchStatus

private val SEARCH_PADDING = 16.dp
private val ITEM_SPACING = 8.dp
private val RESULT_MIN_SIZE = 96.dp
private val RESULT_PADDING = 6.dp

/** La recherche de pictos ARASAAC, en plein écran, ouverte depuis l'éditeur d'une case. */
@Composable
fun PictogramSearchDialog(
    state: PictogramSearchState,
    onSearch: (String) -> Unit,
    onPick: (Long) -> Unit,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    Dialog(
        onDismissRequest = onClose,
        // Bord à bord : safeDrawingPadding tient lui-même compte des barres et du clavier.
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .safeDrawingPadding()
                    .padding(SEARCH_PADDING),
                verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.search_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                    )
                    TextButton(onClick = onClose) { Text(stringResource(R.string.search_close)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { typed -> query = typed.replace('\n', ' ') },
                        label = { Text(stringResource(R.string.search_field)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onSearch(query) }) { Text(stringResource(R.string.search_button)) }
                }
                // Seul envoi de l'appli vers l'extérieur (invariant 1) : l'adulte sait ce qui part.
                Text(stringResource(R.string.search_privacy), style = MaterialTheme.typography.bodySmall)
                when (state.status) {
                    SearchStatus.Idle -> Unit
                    SearchStatus.Loading -> {
                        val loading = stringResource(R.string.search_loading)
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            // Annoncée : un adulte qui n'y voit pas sait que la recherche est partie.
                            CircularProgressIndicator(
                                modifier = Modifier.semantics {
                                    contentDescription = loading
                                    liveRegion = LiveRegionMode.Polite
                                },
                            )
                        }
                    }
                    SearchStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
                        Notice(R.string.search_failed)
                        Button(onClick = { onSearch(query) }) { Text(stringResource(R.string.search_retry)) }
                    }
                    SearchStatus.TooManyResults -> Notice(R.string.search_too_many)
                    SearchStatus.Done ->
                        if (state.results.isEmpty()) {
                            Notice(R.string.search_none)
                        } else {
                            // Le nombre annoncé : sans lui, une recherche réussie reste muette pour TalkBack.
                            Text(
                                pluralStringResource(R.plurals.search_found, state.results.size, state.results.size),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(RESULT_MIN_SIZE),
                                horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING),
                                verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
                            ) {
                                items(state.results, key = { it.id }) { result -> Result(result, onPick) }
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun Notice(text: Int) {
    Text(stringResource(text), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun Result(result: FoundPictogram, onPick: (Long) -> Unit) {
    val description = if (result.keyword.isBlank()) {
        stringResource(R.string.search_result_unnamed)
    } else {
        stringResource(R.string.search_result_description, result.keyword)
    }
    val image = result.image
    Surface(
        // Une vignette pas encore arrivée ne se choisit pas : il n'y aurait rien à garder.
        onClick = { onPick(result.id) },
        enabled = image != null,
        modifier = Modifier
            .aspectRatio(1f)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (image != null) {
            Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.padding(RESULT_PADDING))
        }
    }
}
