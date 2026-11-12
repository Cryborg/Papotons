package com.wobblebot.papotons.ui.edit

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R
import com.wobblebot.papotons.data.caregiver.CAREGIVER_CODE_LENGTH
import com.wobblebot.papotons.domain.Caregiver
import com.wobblebot.papotons.data.caregiver.isValidCaregiverCode
import com.wobblebot.papotons.ui.settings.CaregiversUiState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FIELD_SPACING = 8.dp

/**
 * Le contrôle avant une zone réservée aux adultes : le code d'un accompagnant s'il en existe un, le
 * calcul sinon. Rien tant que le fichier des accompagnants n'est pas lu (quelques millisecondes) :
 * le calcul ne s'affiche jamais à tort.
 */
@Composable
fun AdultGateDialog(
    state: CaregiversUiState,
    onVerify: suspend (Long, String) -> Boolean,
    onRecover: suspend (Long, String) -> Boolean,
    onPassed: () -> Unit,
    onClose: () -> Unit,
) {
    val caregivers = state.caregivers ?: return
    if (caregivers.isEmpty()) {
        ParentalGateDialog(onPassed = onPassed, onClose = onClose)
    } else {
        CaregiverGateDialog(caregivers, onVerify, onRecover, onPassed, onClose)
    }
}

private enum class GateStep { Code, Recovery, NewCode }

/**
 * Code de l'accompagnant choisi ; une erreur referme le contrôle (pas d'essais en rafale). « Code
 * oublié ? » : une multiplication, puis un nouveau code. Rien n'est sauvé (`remember`) : une rotation
 * ou la mort du processus fait tout reprendre au contrôle, jamais au nouveau code, et tout départ de
 * l'appli le ferme (`BoardRoute`, `ON_STOP`).
 */
@Composable
private fun CaregiverGateDialog(
    caregivers: ImmutableList<Caregiver>,
    onVerify: suspend (Long, String) -> Boolean,
    onRecover: suspend (Long, String) -> Boolean,
    onPassed: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // Sur le fil principal : le toast et l'action qui suit en ont besoin, quel que soit le contexte de la composition.
    val scope = rememberCoroutineScope()
    val wrongCode = stringResource(R.string.caregiver_gate_wrong)
    val wrongAnswer = stringResource(R.string.caregiver_recovery_wrong)
    val notChanged = stringResource(R.string.caregiver_new_code_failed)
    var step by remember { mutableStateOf(GateStep.Code) }
    // Présélectionné seulement s'il est seul : sinon un code serait vérifié, ou remis, chez un autre.
    var chosenId by remember { mutableStateOf(caregivers.singleOrNull()?.id) }
    var isChecking by remember { mutableStateOf(false) }
    val chosen = caregivers.firstOrNull { it.id == chosenId }
    // Disparu entre-temps : on ferme plutôt que d'écrire le code d'un autre.
    if (chosenId != null && chosen == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    // Annoncés (TalkBack lit les toasts) : la fenêtre qui se ferme seule ne dit pas pourquoi.
    fun closeWith(message: String) {
        // Long : la fenêtre se ferme en même temps, une lecture lente ne doit pas rater pourquoi.
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        onClose()
    }
    when (step) {
        GateStep.Code -> CodeStep(
            caregivers = caregivers,
            chosen = chosen,
            isChecking = isChecking,
            onChosen = { chosenId = it },
            onSubmit = { code ->
                // Lu à l'appui, pas à la composition : deux appuis dans la même image ne vérifient qu'une fois.
                if (chosen != null && !isChecking) {
                    isChecking = true
                    scope.launch(Dispatchers.Main) {
                        if (onVerify(chosen.id, code)) onPassed() else closeWith(wrongCode)
                        isChecking = false
                    }
                }
            },
            onForgotten = { step = GateStep.Recovery },
            onClose = onClose,
        )
        GateStep.Recovery -> RecoveryStep(
            onAnswered = { isRight -> if (isRight) step = GateStep.NewCode else closeWith(wrongAnswer) },
            onClose = onClose,
        )
        GateStep.NewCode -> NewCodeStep(
            name = chosen?.name.orEmpty(),
            isChecking = isChecking,
            onSubmit = { code ->
                if (chosen == null || isChecking) return@NewCodeStep
                isChecking = true
                scope.launch(Dispatchers.Main) {
                    // L'adulte a répondu : l'accès s'ouvre même si le code n'a pas pu s'écrire. Jusqu'au bout
                    // même si la modale part (écran éteint pendant le hachage) : sinon le nouveau code se perd.
                    if (!withContext(NonCancellable) { onRecover(chosen.id, code) }) Toast.makeText(context, notChanged, Toast.LENGTH_LONG).show()
                    isChecking = false
                    onPassed()
                }
            },
            onClose = onClose,
        )
    }
}

@Composable
private fun CodeStep(
    caregivers: ImmutableList<Caregiver>,
    chosen: Caregiver?,
    isChecking: Boolean,
    onChosen: (Long) -> Unit,
    onSubmit: (String) -> Unit,
    onForgotten: () -> Unit,
    onClose: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val canSubmit = chosen != null && isValidCaregiverCode(code) && !isChecking
    // Un code incomplet ne part pas : un appui trop tôt sur Entrée ne referme pas le contrôle.
    val submit = { if (canSubmit) onSubmit(code) }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.caregiver_gate_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FIELD_SPACING)) {
                if (caregivers.size > 1) {
                    Text(stringResource(R.string.caregiver_gate_who), modifier = Modifier.semantics { heading() })
                    Column(modifier = Modifier.selectableGroup()) {
                        for (caregiver in caregivers) {
                            ListItem(
                                leadingContent = { RadioButton(selected = caregiver.id == chosen?.id, onClick = null) },
                                headlineContent = { Text(caregiver.name) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = caregiver.id == chosen?.id, role = Role.RadioButton, onClick = { onChosen(caregiver.id) }),
                            )
                        }
                    }
                }
                // Le clavier ne monte d'emblée que s'il n'y a personne à choisir : il cacherait les noms.
                CodeField(
                    value = code,
                    onValueChange = { code = it },
                    label = stringResource(R.string.caregiver_code, CAREGIVER_CODE_LENGTH),
                    onDone = submit,
                    requestsFocus = caregivers.size == 1,
                )
                TextButton(onClick = onForgotten, enabled = chosen != null && !isChecking) { Text(stringResource(R.string.caregiver_gate_forgotten)) }
            }
        },
        confirmButton = {
            Button(onClick = submit, enabled = canSubmit) { Text(stringResource(R.string.caregiver_gate_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.caregiver_gate_cancel)) }
        },
    )
}

@Composable
private fun RecoveryStep(onAnswered: (Boolean) -> Unit, onClose: () -> Unit) {
    val question = remember { RecoveryQuestion.random() }
    var answer by remember { mutableStateOf("") }
    val canSubmit = answer.isNotBlank()
    // Comme pour le code : une réponse vide (Entrée trop tôt) ne referme pas le contrôle.
    val submit = { if (canSubmit) onAnswered(question.isAnsweredBy(answer)) }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.caregiver_gate_forgotten)) },
        text = {
            // Défilant : à police agrandie, clavier ouvert, le champ sortirait sinon de la fenêtre.
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FIELD_SPACING)) {
                Text(stringResource(R.string.caregiver_recovery_question, question.first, question.second))
                CodeField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = stringResource(R.string.caregiver_recovery_answer),
                    onDone = submit,
                    requestsFocus = true,
                    maxLength = null,
                    isSecret = false,
                )
            }
        },
        confirmButton = { Button(onClick = submit, enabled = canSubmit) { Text(stringResource(R.string.caregiver_gate_confirm)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.caregiver_gate_cancel)) } },
    )
}

@Composable
private fun NewCodeStep(name: String, isChecking: Boolean, onSubmit: (String) -> Unit, onClose: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val submit = {
        error = codeError(code, again)
        if (error == null && !isChecking) onSubmit(code)
    }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.caregiver_new_code_title, name)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FIELD_SPACING)) {
                Text(stringResource(R.string.caregiver_code_hint))
                CodeField(
                    value = code,
                    onValueChange = { code = it },
                    label = stringResource(R.string.caregiver_code, CAREGIVER_CODE_LENGTH),
                    imeAction = ImeAction.Next,
                    requestsFocus = true,
                    isError = error != null,
                )
                CodeField(value = again, onValueChange = { again = it }, label = stringResource(R.string.caregiver_code_again), onDone = submit, isError = error != null)
                error?.let { CodeError(it) }
            }
        },
        confirmButton = { Button(onClick = submit, enabled = !isChecking) { Text(stringResource(R.string.caregiver_gate_confirm)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.caregiver_gate_cancel)) } },
    )
}

/** Le message d'erreur d'un code et de sa confirmation, `null` s'ils conviennent. */
@StringRes
internal fun codeError(code: String, again: String): Int? = when {
    !isValidCaregiverCode(code) -> R.string.caregiver_code_invalid
    code != again -> R.string.caregiver_code_mismatch
    else -> null
}

/** Annoncée : le focus de TalkBack reste sur le bouton qui n'a rien fait. */
@Composable
internal fun CodeError(@StringRes message: Int) {
    Text(
        // La longueur du code en argument : « Le code fait exactement 4 chiffres. » ; ignorée par les autres.
        text = stringResource(message, CAREGIVER_CODE_LENGTH),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

/**
 * Un champ de chiffres seulement, masqué s'il porte un code.
 */
@Composable
internal fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onDone: () -> Unit = {},
    imeAction: ImeAction = ImeAction.Done,
    requestsFocus: Boolean = false,
    maxLength: Int? = CAREGIVER_CODE_LENGTH,
    isSecret: Boolean = true,
    isError: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    OutlinedTextField(
        value = value,
        // 0 à 9 seulement, comme isValidCaregiverCode : `isDigit` laisserait passer les chiffres arabes-indiens.
        onValueChange = { typed -> onValueChange(typed.filter { it in '0'..'9' }.let { digits -> maxLength?.let(digits::take) ?: digits }) },
        isError = isError,
        label = { Text(label) },
        singleLine = true,
        // `Number` et non `NumberPassword` : un champ « mot de passe » est proposé à l'autoremplissage
        // d'Android, qui pourrait enregistrer le code puis le suggérer à l'enfant. Le masquage reste
        // visuel, et le filtre écarte point et signe.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.focusRequester(focusRequester),
    )
    if (requestsFocus) LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
