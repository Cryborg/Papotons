package com.wobblebot.papotons.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R

internal val PAGE_PADDING = 16.dp
internal val SECTION_SPACING = 20.dp
private val SECTION_LABEL_PADDING = 8.dp
internal val CARD_SHAPE = RoundedCornerShape(16.dp)
internal val ROW_MIN_HEIGHT = 64.dp
internal val ROW_HORIZONTAL_PADDING = 16.dp
internal val ROW_VERTICAL_PADDING = 12.dp

/** Entre la pastille et le texte d'une ligne. */
internal val ROW_ICON_SPACING = 16.dp
private val BUTTON_ICON_SPACING = 8.dp

/** Une liste de prénoms se coupe à deux lignes ; une phrase jamais (coupée, elle pourrait dire l'inverse). */
internal const val NAMES_SUMMARY_MAX_LINES = 2

/** Un prénom dans une liste : deux lignes plutôt que quelques lettres à police agrandie. */
internal const val NAME_MAX_LINES = 2

/** Taille de l'initiale dans sa pastille, en dp : fixe, la pastille aussi (sinon l'initiale y serait rognée). */
private val BADGE_TEXT_SIZE = 18.dp
private val BADGE_SIZE = 40.dp
private val BADGE_ICON_SIZE = 22.dp

/** Le séparateur commence après la pastille : les lignes d'une carte se lisent comme un bloc. */
private val DIVIDER_START = ROW_HORIZONTAL_PADDING + BADGE_SIZE + ROW_ICON_SPACING

/** Une section des Réglages : un intitulé discret, puis une carte blanche qui groupe ses lignes. */
@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = ROW_HORIZONTAL_PADDING, bottom = SECTION_LABEL_PADDING)
                .semantics { heading() },
        )
        SettingsCard(content = content)
    }
}

/** Le fond blanc arrondi d'un groupe de lignes, sur le fond gris de la page. */
@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

/** Les couleurs d'une pastille : un fond pâle et son encre. */
data class BadgeColors(val container: Color, val content: Color)

@Composable
fun gridBadgeColors() = BadgeColors(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)

@Composable
fun peopleBadgeColors() = BadgeColors(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)

@Composable
fun appBadgeColors() = BadgeColors(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * Une ligne qui mène ailleurs : pastille d'icône, titre, résumé court, chevron. Toute la ligne
 * est la cible, lue d'un bloc par TalkBack (titre puis résumé) ; pastille et chevron sont décoratifs.
 */
@Composable
fun SettingsRow(
    @DrawableRes icon: Int,
    badgeColors: BadgeColors,
    title: String,
    summary: String?,
    onClick: () -> Unit,
    summaryMaxLines: Int = Int.MAX_VALUE,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = ROW_VERTICAL_PADDING),
    ) {
        IconBadge(icon, badgeColors)
        Spacer(modifier = Modifier.width(ROW_ICON_SPACING))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (!summary.isNullOrEmpty()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = summaryMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Une option à cocher : toute la ligne bascule l'interrupteur, lue d'un bloc par TalkBack. */
@Composable
fun SettingsSwitchRow(
    @DrawableRes icon: Int,
    badgeColors: BadgeColors,
    title: String,
    summary: String,
    isChecked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .toggleable(value = isChecked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = ROW_VERTICAL_PADDING),
    ) {
        IconBadge(icon, badgeColors)
        Spacer(modifier = Modifier.width(ROW_ICON_SPACING))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(ROW_ICON_SPACING))
        // Sans action propre : la ligne entière porte la bascule (une seule cible, un seul arrêt).
        Switch(checked = isChecked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(start = DIVIDER_START))
}

@Composable
private fun IconBadge(@DrawableRes icon: Int, colors: BadgeColors) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(BADGE_SIZE)
            .clip(CircleShape)
            .background(colors.container),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = colors.content, modifier = Modifier.size(BADGE_ICON_SIZE))
    }
}

/** L'initiale d'un prénom dans une pastille ronde. Décorative : le prénom est écrit à côté. */
@Composable
fun NameBadge(name: String, colors: BadgeColors, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(BADGE_SIZE)
            .clip(CircleShape)
            .background(colors.container)
            .clearAndSetSemantics {},
    ) {
        // Décorative (le prénom est écrit à côté) : sa taille ne suit pas la police, la pastille est fixe.
        val fontSize = with(LocalDensity.current) { BADGE_TEXT_SIZE.toSp() }
        Text(text = initialOf(name), style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSize, lineHeight = fontSize), color = colors.content)
    }
}

/** Le premier point de code (un émoji simple tient sur deux `Char`), en capitale. */
internal fun initialOf(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ""
    return String(Character.toChars(trimmed.codePointAt(0))).uppercase()
}

/** Un bouton d'ajout sous une liste : pleine largeur, contour, icône « + ». */
@Composable
fun AddButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null)
        Spacer(modifier = Modifier.width(BUTTON_ICON_SPACING))
        Text(text)
    }
}

/** Un texte d'explication sous l'en-tête d'une page. */
@Composable
fun PageExplanation(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = ROW_HORIZONTAL_PADDING),
    )
}

/** Un échec annoncé dès qu'il apparaît. */
@Composable
fun FailureText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier
            .padding(horizontal = ROW_HORIZONTAL_PADDING)
            .semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

/** L'espacement vertical commun des pages. */
internal val pageArrangement = Arrangement.spacedBy(SECTION_SPACING)
