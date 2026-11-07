package com.wobblebot.papotons.ui.edit

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.CellContent
import com.wobblebot.papotons.domain.WordCategory
import com.wobblebot.papotons.domain.Picture
import com.wobblebot.papotons.ui.board.CellEditorState
import com.wobblebot.papotons.ui.board.CellPicture
import com.wobblebot.papotons.ui.board.EditFailure
import com.wobblebot.papotons.ui.board.PhotoFailure
import com.wobblebot.papotons.ui.board.EditTarget
import com.wobblebot.papotons.ui.board.Pictogram
import com.wobblebot.papotons.ui.theme.wordCategoryBackground

private val EDITOR_PADDING = 16.dp
private val SECTION_SPACING = 16.dp
private val ITEM_SPACING = 8.dp
private val PICTOGRAM_PREVIEW_SIZE = 96.dp
private val CATEGORY_SWATCH_SIZE = 16.dp
private const val MAX_SPOKEN_TEXT_LENGTH = 200

/** Bien en dessous de ce qu'une sauvegarde accepte : un texte collé ne doit jamais rendre l'export impossible. */
private const val MAX_LABEL_LENGTH = 200

/**
 * L'éditeur d'une case, en plein écran. Le brouillon survit à la rotation (`rememberSaveable`), rangé
 * sous l'ouverture (`state.openingId`) : après la mort du processus, il n'est jamais repris par
 * l'éditeur d'une autre case. Son ouverture, elle, vient du ViewModel (jamais restaurée sans
 * contrôle parental).
 */
@Composable
fun CellEditorDialog(
    state: CellEditorState,
    onSave: (CellContent, asFolder: Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpenFolder: () -> Unit,
    onSearchPictogram: (query: String) -> Unit,
    onRemovePictogram: () -> Unit,
    /** `null` : l'appareil n'a pas d'appareil photo, le bouton n'est pas proposé. */
    onTakePhoto: (() -> Unit)?,
    onChoosePhoto: () -> Unit,
    onClose: () -> Unit,
) {
    key(state.openingId) {
        CellEditor(state, onSave, onDelete, onOpenFolder, onSearchPictogram, onRemovePictogram, onTakePhoto, onChoosePhoto, onClose)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CellEditor(
    state: CellEditorState,
    onSave: (CellContent, asFolder: Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpenFolder: () -> Unit,
    onSearchPictogram: (query: String) -> Unit,
    onRemovePictogram: () -> Unit,
    onTakePhoto: (() -> Unit)?,
    onChoosePhoto: () -> Unit,
    onClose: () -> Unit,
) {
    val existing = (state.target as? EditTarget.ExistingCell)?.cell
    var label by rememberSaveable { mutableStateOf(existing?.label.orEmpty()) }
    var pictogramId by rememberSaveable { mutableStateOf(existing?.pictogramId) }
    var categoryName by rememberSaveable { mutableStateOf(existing?.category?.name) }
    var photoFile by rememberSaveable { mutableStateOf(existing?.photoFile) }
    var spokenText by rememberSaveable { mutableStateOf(existing?.spokenText.orEmpty()) }
    var asFolder by rememberSaveable { mutableStateOf(false) }
    var isLabelMissing by rememberSaveable { mutableStateOf(false) }
    var isDeleteConfirmOpen by rememberSaveable { mutableStateOf(false) }
    val isFolder = existing?.linkedBoardId != null || (existing == null && asFolder)
    val labelFocus = remember { FocusRequester() }
    // Un picto choisi dans la recherche remplace celui du brouillon.
    val chosen = state.chosenPictogram
    LaunchedEffect(chosen) {
        if (chosen != null) {
            pictogramId = chosen.id
            photoFile = null
        }
    }
    // Une photo prise ou choisie remplace le picto.
    val chosenPhoto = state.chosenPhoto
    LaunchedEffect(chosenPhoto) {
        if (chosenPhoto != null) {
            photoFile = chosenPhoto
            pictogramId = null
        }
    }

    Dialog(
        onDismissRequest = onClose,
        // Bord à bord : safeDrawingPadding tient lui-même compte des barres et du clavier.
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(EDITOR_PADDING),
                verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
            ) {
                Text(
                    text = stringResource(if (existing == null) R.string.editor_new_title else R.string.editor_edit_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedTextField(
                    value = label,
                    // Une ligne : la case ne coupe ses mots qu'aux espaces.
                    onValueChange = { typed ->
                        label = typed.replace('\n', ' ').takeWhole(MAX_LABEL_LENGTH)
                        isLabelMissing = false
                    },
                    label = { Text(stringResource(R.string.editor_label)) },
                    singleLine = true,
                    isError = isLabelMissing,
                    supportingText = if (isLabelMissing) {
                        { Text(stringResource(R.string.editor_label_required), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(labelFocus),
                )
                if (existing == null) {
                    Section(R.string.editor_kind) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING),
                            verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
                        ) {
                            FilterChip(selected = !asFolder, onClick = { asFolder = false }, label = { Text(stringResource(R.string.editor_kind_word)) })
                            FilterChip(selected = asFolder, onClick = { asFolder = true }, label = { Text(stringResource(R.string.editor_kind_folder)) })
                        }
                    }
                }
                if (!isFolder) {
                    OutlinedTextField(
                        value = spokenText,
                        onValueChange = { typed -> spokenText = typed.replace('\n', ' ').takeWhole(MAX_SPOKEN_TEXT_LENGTH) },
                        label = { Text(stringResource(R.string.editor_spoken_text)) },
                        supportingText = { Text(stringResource(R.string.editor_spoken_text_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Section(R.string.editor_pictogram) {
                    val currentPictogram = pictogramId
                    val currentPhoto = photoFile
                    if (currentPhoto != null) {
                        CellPicture(Picture.Photo(currentPhoto), Modifier.size(PICTOGRAM_PREVIEW_SIZE))
                    } else if (currentPictogram == null) {
                        Text(stringResource(R.string.editor_no_pictogram))
                    } else if (chosen != null && chosen.id == currentPictogram) {
                        // Pas encore gardé sur l'appareil : l'image de la recherche.
                        Image(bitmap = chosen.image, contentDescription = null, modifier = Modifier.size(PICTOGRAM_PREVIEW_SIZE))
                    } else {
                        Pictogram(currentPictogram, Modifier.size(PICTOGRAM_PREVIEW_SIZE))
                    }
                    if (state.isImportingPhoto) {
                        // Annoncée : la préparation d'une photo peut durer, TalkBack ne doit pas rester muet.
                        Text(stringResource(R.string.editor_photo_importing), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    state.photoFailure?.let { failure ->
                        Text(
                            stringResource(if (failure == PhotoFailure.NoPhotoApp) R.string.editor_photo_no_app else R.string.editor_photo_failed),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING),
                        verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
                    ) {
                        val canPick = !state.isWriting && !state.isImportingPhoto
                        if (onTakePhoto != null) {
                            OutlinedButton(onClick = onTakePhoto, enabled = canPick) { Text(stringResource(R.string.editor_take_photo)) }
                        }
                        OutlinedButton(onClick = onChoosePhoto, enabled = canPick) { Text(stringResource(R.string.editor_choose_photo)) }
                        OutlinedButton(onClick = { onSearchPictogram(label.trim()) }, enabled = canPick) {
                            Text(stringResource(R.string.editor_search_pictogram))
                        }
                        // Visible aussi pendant un import : c'est la sortie d'une image qui ne se charge pas.
                        if (currentPictogram != null || currentPhoto != null || state.isImportingPhoto) {
                            TextButton(
                                onClick = {
                                    pictogramId = null
                                    photoFile = null
                                    onRemovePictogram()
                                },
                                enabled = !state.isWriting,
                            ) { Text(stringResource(R.string.editor_remove_pictogram)) }
                        }
                    }
                }
                Section(R.string.editor_category) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING),
                        verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
                    ) {
                        FilterChip(
                            selected = categoryName == null,
                            onClick = { categoryName = null },
                            label = { Text(stringResource(R.string.editor_category_none)) },
                        )
                        for (category in WordCategory.entries) {
                            FilterChip(
                                selected = categoryName == category.name,
                                onClick = { categoryName = category.name },
                                label = { Text(stringResource(category.displayName())) },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(CATEGORY_SWATCH_SIZE)
                                            .background(wordCategoryBackground(category), CircleShape),
                                    )
                                },
                            )
                        }
                    }
                }
                state.failure?.let { failure ->
                    Text(
                        stringResource(if (failure == EditFailure.Storage) R.string.editor_failed_storage else R.string.editor_failed_conflict),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING),
                    verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
                ) {
                    if (existing != null) {
                        TextButton(onClick = { isDeleteConfirmOpen = true }, enabled = !state.isWriting && !state.isImportingPhoto) {
                            Text(stringResource(R.string.editor_delete), color = MaterialTheme.colorScheme.error)
                        }
                        if (existing.linkedBoardId != null) {
                            // Seulement sans modification en cours : ouvrir le dossier ferme l'éditeur et
                            // jetterait le brouillon sans le dire.
                            val isUnchanged = label == existing.label && pictogramId == existing.pictogramId &&
                                categoryName == existing.category?.name && photoFile == existing.photoFile &&
                                spokenText == existing.spokenText.orEmpty()
                            OutlinedButton(onClick = onOpenFolder, enabled = !state.isWriting && isUnchanged) {
                                Text(stringResource(R.string.editor_open_folder))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose, enabled = !state.isWriting) { Text(stringResource(R.string.editor_cancel)) }
                    Button(
                        onClick = {
                            val trimmed = label.trim().replace(Regex("\\s+"), " ")
                            isLabelMissing = trimmed.isEmpty()
                            if (isLabelMissing) {
                                labelFocus.requestFocus()
                            } else {
                                // Vide, ou égal au libellé : la case dit son libellé. Un dossier ne parle pas.
                                val spoken = spokenText.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() && it != trimmed && !isFolder }
                                onSave(CellContent(trimmed, pictogramId, WordCategory.fromStoredName(categoryName), photoFile, spoken), isFolder)
                            }
                        },
                        enabled = !state.isWriting && !state.isImportingPhoto,
                    ) {
                        Text(stringResource(R.string.editor_save))
                    }
                }
            }
        }
    }
    if (isDeleteConfirmOpen && existing != null) {
        val confirmText = if (existing.linkedBoardId != null) R.string.editor_delete_folder_confirm else R.string.editor_delete_word_confirm
        AlertDialog(
            onDismissRequest = { isDeleteConfirmOpen = false },
            properties = DialogProperties(dismissOnClickOutside = false),
            text = { Text(stringResource(confirmText, existing.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        isDeleteConfirmOpen = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.editor_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { isDeleteConfirmOpen = false }) { Text(stringResource(R.string.editor_cancel)) }
            },
        )
    }
}

@Composable
private fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@StringRes
private fun WordCategory.displayName(): Int = when (this) {
    WordCategory.PEOPLE -> R.string.category_people
    WordCategory.VERB -> R.string.category_verb
    WordCategory.DESCRIPTOR -> R.string.category_descriptor
    WordCategory.NOUN -> R.string.category_noun
    WordCategory.SOCIAL -> R.string.category_social
    WordCategory.QUESTION -> R.string.category_question
    WordCategory.NEGATION -> R.string.category_negation
    WordCategory.LITTLE_WORD -> R.string.category_little_word
}

/** Coupe à [maxLength] sans jamais laisser une moitié d'émoji (paire de substitution). */
internal fun String.takeWhole(maxLength: Int): String {
    val cut = take(maxLength)
    return if (cut.length == maxLength && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}
