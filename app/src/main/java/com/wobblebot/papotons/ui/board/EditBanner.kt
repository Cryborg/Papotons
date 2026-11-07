package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R

private val BANNER_SPACING = 8.dp
private const val BANNER_MAX_LINES = 2

/** À la place de la phrase en édition, dans la même barre à hauteur fixe : rien ne bouge (invariant 3). */
@Composable
fun EditBanner(onDone: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(BANNER_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Court : à police agrandie, une consigne longue se couperait. Annoncé à l'entrée en édition.
        Text(
            text = stringResource(R.string.edit_banner),
            style = MaterialTheme.typography.titleMedium,
            maxLines = BANNER_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
        )
        Button(onClick = onDone) {
            Text(stringResource(R.string.edit_done))
        }
    }
}
