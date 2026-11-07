package com.wobblebot.papotons.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Caregiver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Les actions sur les accompagnants, regroupées pour la signature des Réglages. */
@Immutable
data class CaregiverActions(
    /** Id `null` : un nouvel accompagnant ; code `null` : inchangé. */
    val onSave: (Long?, String, String?) -> Unit,
    val onDelete: (Long) -> Unit,
)

private const val NO_CAREGIVER_EDITED = -1L
private const val NEW_CAREGIVER = 0L

/** Les accompagnants : tous ont les mêmes droits, chacun peut modifier ou supprimer n'importe lequel. */
@Composable
fun CaregiversSection(state: CaregiversUiState, actions: CaregiverActions, horizontalPadding: Dp) {
    val caregivers = state.caregivers ?: return
    var editedId by rememberSaveable { mutableStateOf(NO_CAREGIVER_EDITED) }
    Column {
        Text(
            text = stringResource(R.string.settings_caregivers),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .semantics { heading() },
        )
        Text(
            text = stringResource(if (caregivers.isEmpty()) R.string.settings_caregivers_none else R.string.settings_caregivers_explanation),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
        for (caregiver in caregivers) {
            CaregiverRow(caregiver, isEnabled = !state.isWorking, horizontalPadding = horizontalPadding, onEdit = { editedId = caregiver.id })
        }
        TextButton(onClick = { editedId = NEW_CAREGIVER }, enabled = !state.isWorking, modifier = Modifier.padding(horizontal = horizontalPadding)) {
            Text(stringResource(R.string.caregiver_add))
        }
        state.failure?.let { failure ->
            Text(
                text = stringResource(if (failure == CaregiverFailure.NotSaved) R.string.caregiver_not_saved else R.string.caregiver_not_deleted),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(horizontal = horizontalPadding)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
    val close = { editedId = NO_CAREGIVER_EDITED }
    if (editedId == NEW_CAREGIVER) {
        CaregiverDialog(caregiver = null, isLast = false, onSave = { name, code -> close(); actions.onSave(null, name, code) }, onDelete = close, onClose = close)
    } else {
        caregivers.firstOrNull { it.id == editedId }?.let { caregiver ->
            CaregiverDialog(
                caregiver = caregiver,
                isLast = caregivers.size == 1,
                onSave = { name, code -> close(); actions.onSave(caregiver.id, name, code) },
                onDelete = { close(); actions.onDelete(caregiver.id) },
                onClose = close,
            )
        }
    }
}

/** Même forme que la ligne d'un utilisateur : la description nommée sur le texte, le bouton garde son clic. */
@Composable
private fun CaregiverRow(caregiver: Caregiver, isEnabled: Boolean, horizontalPadding: Dp, onEdit: () -> Unit) {
    val editDescription = stringResource(R.string.caregiver_edit_description, caregiver.name)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(caregiver.name) },
            supportingContent = caregiver.codeResetAtMs?.let { resetAtMs ->
                { Text(stringResource(R.string.caregiver_code_reset, formatDate(resetAtMs))) }
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onEdit, enabled = isEnabled, modifier = Modifier.padding(end = horizontalPadding)) {
            Text(
                text = stringResource(R.string.caregiver_edit),
                modifier = Modifier.clearAndSetSemantics { contentDescription = editDescription },
            )
        }
    }
}

/** « 7 novembre 2026 » : l'appli est en français quelle que soit la langue de l'appareil. */
private fun formatDate(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.FRENCH).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
