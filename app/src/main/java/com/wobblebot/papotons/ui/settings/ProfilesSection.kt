package com.wobblebot.papotons.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
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

/** La liste des utilisateurs : toucher une ligne ouvre cet utilisateur derrière les Réglages. */
@Composable
fun ProfilesSection(state: ProfilesUiState, actions: ProfileActions, horizontalPadding: Dp) {
    var editedProfileId by rememberSaveable { mutableStateOf(NO_PROFILE_EDITED) }
    Column(modifier = Modifier.selectableGroup()) {
        Text(
            text = stringResource(R.string.settings_profiles),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .semantics { heading() },
        )
        Text(
            text = stringResource(R.string.settings_profiles_explanation),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
        for (profile in state.profiles) {
            ProfileRow(
                profile = profile,
                isOpen = profile.id == state.openProfileId,
                isEnabled = !state.isWorking,
                horizontalPadding = horizontalPadding,
                onChosen = { actions.onChosen(profile.id) },
                onEdit = { editedProfileId = profile.id },
            )
        }
        val isFull = state.profiles.size >= MAX_PROFILES
        TextButton(
            onClick = { editedProfileId = NEW_PROFILE },
            enabled = !state.isWorking && !isFull,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        ) {
            Text(stringResource(R.string.profile_add))
        }
        if (isFull) {
            Text(
                text = stringResource(R.string.profile_limit_reached, MAX_PROFILES),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = horizontalPadding),
            )
        }
        state.failure?.let { failure ->
            Text(
                text = stringResource(failure.message),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(horizontal = horizontalPadding)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
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
            ProfileDialog(
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
            )
        }
    }
}

/**
 * Deux cibles côte à côte, jamais imbriquées : la ligne ouvre l'utilisateur, « Modifier » ouvre sa
 * modale. La description nomme l'utilisateur (« Modifier Léo ») : posée sur le texte, elle laisse au
 * bouton son action de clic pour TalkBack, Switch Access et Voice Access.
 */
@Composable
private fun ProfileRow(
    profile: Profile,
    isOpen: Boolean,
    isEnabled: Boolean,
    horizontalPadding: Dp,
    onChosen: () -> Unit,
    onEdit: () -> Unit,
) {
    val editDescription = stringResource(R.string.profile_edit_description, profile.name)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { RadioButton(selected = isOpen, onClick = null) },
            headlineContent = { Text(profile.name) },
            modifier = Modifier
                .weight(1f)
                .selectable(selected = isOpen, enabled = isEnabled, role = Role.RadioButton, onClick = onChosen),
        )
        TextButton(onClick = onEdit, enabled = isEnabled, modifier = Modifier.padding(end = horizontalPadding)) {
            Text(
                text = stringResource(R.string.profile_edit),
                modifier = Modifier.clearAndSetSemantics { contentDescription = editDescription },
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
