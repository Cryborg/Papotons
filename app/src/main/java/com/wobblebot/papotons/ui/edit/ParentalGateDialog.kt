package com.wobblebot.papotons.ui.edit

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wobblebot.papotons.R

private val QUESTION_SPACING = 12.dp

/**
 * Le contrôle parental avant une zone réservée aux adultes (édition, acquittement d'une perte).
 * Une bonne réponse appelle [onPassed] ; une mauvaise referme le contrôle par [onClose] : pas
 * d'essais en rafale. La question n'est pas gardée à la rotation (`remember`) : une nouvelle
 * question, c'est sans gêne pour un adulte.
 */
@Composable
fun ParentalGateDialog(onPassed: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val wrongAnswer = stringResource(R.string.parental_gate_wrong)
    val question = remember { ParentalQuestion.random() }
    var answer by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val submit = {
        if (question.isAnsweredBy(answer)) {
            onPassed()
        } else {
            // Annoncé (TalkBack lit les toasts) : la fenêtre qui se ferme seule ne dit pas pourquoi.
            Toast.makeText(context, wrongAnswer, Toast.LENGTH_SHORT).show()
            onClose()
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        // Un tap à côté ne ferme jamais une modale : seuls ses boutons et le retour arrière.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.parental_gate_title)) },
        text = {
            // Grande police et clavier ouvert : sans défilement, le champ de réponse sortirait de la fenêtre.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.parental_gate_question, question.first, question.second))
                Spacer(modifier = Modifier.height(QUESTION_SPACING))
                OutlinedTextField(
                    value = answer,
                    onValueChange = { typed -> answer = typed.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.parental_gate_answer)) },
                    singleLine = true,
                    // NumberPassword : le clavier numérique de certains constructeurs propose sinon point et signe.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.focusRequester(focusRequester),
                )
                // Dans le contenu du dialogue : le champ est attaché quand le focus est demandé.
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            }
        },
        confirmButton = {
            Button(onClick = submit) {
                Text(stringResource(R.string.parental_gate_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.parental_gate_cancel))
            }
        },
    )
}

@Preview
@Composable
private fun ParentalGateDialogPreview() {
    ParentalGateDialog(onPassed = {}, onClose = {})
}
