package com.wobblebot.papotons.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R
import com.wobblebot.papotons.data.backup.BackupContent
import com.wobblebot.papotons.data.backup.PendingRestore
import com.wobblebot.papotons.data.backup.RecoveryReport
import com.wobblebot.papotons.data.board.BoardEntity
import com.wobblebot.papotons.data.profile.ProfileEntity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val CHOICE_SPACING = 12.dp

/** Plusieurs copies abîmées se relisent : l'adulte choisit laquelle récupérer, d'après sa date et ce qu'elle contient. */
@Composable
fun RecoveryChoiceDialog(choices: ImmutableList<PendingRestore>, onChoose: (PendingRestore) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        // Un tap à côté ne ferme jamais une modale : seuls ses boutons et le retour arrière.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.recovery_choice_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CHOICE_SPACING),
            ) {
                Text(stringResource(R.string.recovery_choice_explanation), style = MaterialTheme.typography.bodyMedium)
                for (choice in choices) {
                    // Une carte, pas une pilule : à police agrandie, le texte sur plusieurs lignes serait rogné aux coins.
                    OutlinedButton(onClick = { onChoose(choice) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(copyDateText(choice.recovery?.copiedAtMs ?: 0L), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(
                                    R.string.recovery_copy_content,
                                    choice.profileNames.joinToString(separator = stringResource(R.string.names_separator)),
                                    pluralStringResource(R.plurals.restore_confirm_boards, choice.boardCount, choice.boardCount),
                                    recoveredCellsText(choice.cellCount, choice.recovery?.announcedCellCount),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onCancel) {
                Text(stringResource(R.string.restore_cancel))
            }
        },
    )
}

/** « Copie du 8 novembre 2026 à 14:02 », en français comme le reste de l'appli. */
@Composable
internal fun copyDateText(copiedAtMs: Long): String {
    if (copiedAtMs <= 0L) return stringResource(R.string.recovery_copy_undated)
    val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT).withLocale(Locale.FRENCH)
        .format(Instant.ofEpochMilli(copiedAtMs).atZone(ZoneId.systemDefault()))
    return stringResource(R.string.recovery_copy_date, date)
}

/** « 180 cases sur 224 » quand la copie annonçait son nombre de cases, « 180 cases » sinon. */
@Composable
internal fun recoveredCellsText(cellCount: Int, announcedCellCount: Int?): String =
    if (announcedCellCount == null) {
        pluralStringResource(R.plurals.restore_confirm_cells, cellCount, cellCount)
    } else {
        pluralStringResource(R.plurals.recovery_cells_of_announced, cellCount, cellCount, announcedCellCount)
    }

@Preview
@Composable
private fun RecoveryChoiceDialogPreview() {
    fun choice(copiedAtMs: Long, cells: Int) = PendingRestore(
        fileName = null,
        content = BackupContent(
            listOf(ProfileEntity(1, "Léo", startBoardId = null)),
            listOf(BoardEntity(1, "Accueil", 6, 8, profileId = 1, screenPosition = 0)),
            emptyList(),
        ),
        recovery = RecoveryReport(copyName = "000001-preview", copiedAtMs = copiedAtMs, announcedCellCount = cells, leftOutRowCount = 0),
    )
    RecoveryChoiceDialog(choices = persistentListOf(choice(1_794_000_000_000L, 224), choice(0L, 12)), onChoose = {}, onCancel = {})
}
