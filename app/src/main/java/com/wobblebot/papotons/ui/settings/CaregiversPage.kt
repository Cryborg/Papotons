package com.wobblebot.papotons.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * Les accompagnants : tous ont les mêmes droits, chacun peut modifier ou supprimer n'importe lequel.
 * Rien tant que le fichier n'est pas lu. La modale en cours vit en `remember` : sauvée, elle
 * ressurgirait seule après la mort du processus.
 */
@Composable
fun CaregiversPage(state: CaregiversUiState, actions: CaregiverActions) {
    val caregivers = state.caregivers ?: return
    var editedId by remember { mutableStateOf(NO_CAREGIVER_EDITED) }
    PageExplanation(stringResource(if (caregivers.isEmpty()) R.string.settings_caregivers_none else R.string.settings_caregivers_explanation))
    if (caregivers.isNotEmpty()) {
        SettingsCard {
            caregivers.forEachIndexed { index, caregiver ->
                if (index > 0) SettingsDivider()
                CaregiverRow(caregiver, isEnabled = !state.isWorking, onEdit = { editedId = caregiver.id })
            }
        }
    }
    AddButton(stringResource(R.string.caregiver_add), enabled = !state.isWorking) { editedId = NEW_CAREGIVER }
    state.failure?.let { failure ->
        FailureText(stringResource(failure.message))
    }
    val close = { editedId = NO_CAREGIVER_EDITED }
    if (editedId == NEW_CAREGIVER) {
        CaregiverDialog(caregiver = null, isLast = false, onSave = { name, code -> close(); actions.onSave(null, name, code) }, onDelete = close, onClose = close)
    } else {
        caregivers.firstOrNull { it.id == editedId }?.let { caregiver ->
            // Un état par accompagnant : jamais le brouillon d'un autre.
            key(caregiver.id) { CaregiverDialog(
                caregiver = caregiver,
                isLast = caregivers.size == 1,
                onSave = { name, code -> close(); actions.onSave(caregiver.id, name, code) },
                onDelete = { close(); actions.onDelete(caregiver.id) },
                onClose = close,
            ) }
        }
    }
}

/** Une seule action : toute la ligne ouvre la modale (« Modifier » lu par TalkBack), le crayon est décoratif. */
@Composable
private fun CaregiverRow(caregiver: Caregiver, isEnabled: Boolean, onEdit: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(enabled = isEnabled, onClickLabel = stringResource(R.string.caregiver_edit), role = Role.Button, onClick = onEdit)
            .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = ROW_VERTICAL_PADDING),
    ) {
        NameBadge(caregiver.name, peopleBadgeColors())
        Spacer(modifier = Modifier.width(ROW_ICON_SPACING))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = caregiver.name, style = MaterialTheme.typography.bodyLarge, maxLines = NAME_MAX_LINES, overflow = TextOverflow.Ellipsis)
            caregiver.codeResetAtMs?.let { resetAtMs ->
                Text(
                    text = stringResource(R.string.caregiver_code_reset, formatDate(resetAtMs)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Icon(painterResource(R.drawable.ic_edit), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** « 7 novembre 2026 » : l'appli est en français quelle que soit la langue de l'appareil. */
private fun formatDate(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.FRENCH).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

internal val CaregiverFailure.message: Int
    get() = when (this) {
        CaregiverFailure.NotSaved -> R.string.caregiver_not_saved
        CaregiverFailure.NotDeleted -> R.string.caregiver_not_deleted
    }
