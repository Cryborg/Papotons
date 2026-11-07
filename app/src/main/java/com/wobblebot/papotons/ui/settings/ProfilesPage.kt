package com.wobblebot.papotons.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.wobblebot.papotons.data.profile.MAX_PROFILES
import com.wobblebot.papotons.domain.Profile

/** Les actions sur les utilisateurs, regroupées pour la signature des Réglages. */
@Immutable
data class ProfileActions(
    val onChosen: (Long) -> Unit,
    val onCreate: (String) -> Unit,
    val onRename: (Long, String) -> Unit,
    val onDelete: (Long) -> Unit,
)

/** Pas de modale ouverte. */
private const val NO_PROFILE_EDITED = -1L

/** Pour « Ajouter un utilisateur ». */
private const val NEW_PROFILE = 0L

/**
 * Les utilisateurs : toucher une ligne ouvre cet utilisateur derrière les Réglages, le crayon ouvre sa
 * modale. La modale en cours vit en `remember` : sauvée, elle ressurgirait seule après la mort du
 * processus, à la prochaine ouverture des Réglages.
 */
@Composable
fun ProfilesPage(state: ProfilesUiState, actions: ProfileActions) {
    var editedProfileId by remember { mutableStateOf(NO_PROFILE_EDITED) }
    PageExplanation(stringResource(R.string.settings_profiles_explanation))
    SettingsCard(modifier = Modifier.selectableGroup()) {
        state.profiles.forEachIndexed { index, profile ->
            if (index > 0) SettingsDivider()
            ProfileRow(
                profile = profile,
                isOpen = profile.id == state.openProfileId,
                isEnabled = !state.isWorking,
                onChosen = { actions.onChosen(profile.id) },
                onEdit = { editedProfileId = profile.id },
            )
        }
    }
    val isFull = state.profiles.size >= MAX_PROFILES
    AddButton(stringResource(R.string.profile_add), enabled = !state.isWorking && !isFull) { editedProfileId = NEW_PROFILE }
    if (isFull) PageExplanation(stringResource(R.string.profile_limit_reached, MAX_PROFILES))
    state.failure?.let { failure -> FailureText(stringResource(failure.message)) }
    if (editedProfileId == NEW_PROFILE) {
        ProfileDialog(
            initialName = null,
            onSave = { name ->
                editedProfileId = NO_PROFILE_EDITED
                actions.onCreate(name)
            },
            onDelete = null,
            onClose = { editedProfileId = NO_PROFILE_EDITED },
        )
    } else {
        state.profiles.firstOrNull { it.id == editedProfileId }?.let { profile ->
            // Un état par utilisateur : jamais le brouillon d'un autre.
            key(profile.id) { ProfileDialog(
                initialName = profile.name,
                onSave = { name ->
                    editedProfileId = NO_PROFILE_EDITED
                    if (name != profile.name) actions.onRename(profile.id, name)
                },
                // Le dernier utilisateur ne se supprime pas : il faut des grilles pour parler.
                onDelete = if (state.profiles.size > 1) {
                    {
                        editedProfileId = NO_PROFILE_EDITED
                        actions.onDelete(profile.id)
                    }
                } else {
                    null
                },
                onClose = { editedProfileId = NO_PROFILE_EDITED },
            ) }
        }
    }
}

/**
 * Deux cibles côte à côte, jamais imbriquées : la ligne (bouton radio du groupe) ouvre l'utilisateur,
 * le crayon (« Modifier Léo ») ouvre sa modale. L'ouvert est dit par la coche et par « Ouvert ».
 */
@Composable
private fun ProfileRow(profile: Profile, isOpen: Boolean, isEnabled: Boolean, onChosen: () -> Unit, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ROW_MIN_HEIGHT)
                .selectable(selected = isOpen, enabled = isEnabled, role = Role.RadioButton, onClick = onChosen)
                .padding(start = ROW_HORIZONTAL_PADDING, top = ROW_VERTICAL_PADDING, bottom = ROW_VERTICAL_PADDING),
        ) {
            NameBadge(profile.name, if (isOpen) gridBadgeColors() else peopleBadgeColors())
            Spacer(modifier = Modifier.width(ROW_ICON_SPACING))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = profile.name, style = MaterialTheme.typography.bodyLarge, maxLines = NAME_MAX_LINES, overflow = TextOverflow.Ellipsis)
                if (isOpen) {
                    Text(
                        text = stringResource(R.string.profile_open),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (isOpen) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onEdit, enabled = isEnabled) {
            Icon(
                painterResource(R.drawable.ic_edit),
                stringResource(R.string.profile_edit_description, profile.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val ProfileFailure.message: Int
    get() = when (this) {
        ProfileFailure.NotCreated -> R.string.profile_not_created
        ProfileFailure.NotRenamed -> R.string.profile_not_renamed
        ProfileFailure.NotDeleted -> R.string.profile_not_deleted
        ProfileFailure.NotSwitched -> R.string.profile_not_switched
    }
