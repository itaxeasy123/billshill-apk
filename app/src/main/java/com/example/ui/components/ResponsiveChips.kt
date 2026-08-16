package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.OnAccent
import com.example.ui.theme.RoyalPurplePrimary

/**
 * Chip groups that wrap instead of crushing their own labels.
 *
 * Every chip row in this app used to be a `Row` with `Modifier.weight(1f)` on each chip,
 * which divides the width EQUALLY regardless of what the labels need. On a 360dp phone
 * that produced, in order of severity:
 *
 *  * the GST rate slabs — nine chips sharing ~300dp is 33dp each, less than the 32dp
 *    minimum touch target plus padding, so every label measured to zero width and the
 *    user was offered eight identical blank pills to pick a tax rate from;
 *  * "Journal" rendering as "Journ / al" and "Contra (Cash/Bank)" as four stacked lines,
 *    which then set the height of the whole row; and
 *  * two-chip rows where one label fit on one line and the other wrapped to two, so the
 *    pair rendered at visibly different heights.
 *
 * A chip is as wide as its label. When the labels do not fit on one line they move to the
 * next line — which is what [FlowRow] does and what a `Row` cannot do at any weight.
 */
// FlowRowScope is deliberately NOT part of the signature: it is an experimental API, and
// exposing it would force every calling screen to opt in as well. Callers only need to
// emit chips, so a plain composable lambda is the whole contract.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChipRow(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 8.dp,
    verticalSpacing: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing)
    ) {
        content()
    }
}

/**
 * A [FilterChip] whose label is measured at its natural width and never wraps.
 *
 * `softWrap = false` is the guarantee: the chip asks for the width its text actually
 * needs. Ellipsis is the last resort for a label longer than the whole row — it keeps a
 * pathological string from pushing the layout sideways, but in practice no label here
 * reaches it.
 */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.sp,
    fontWeight: FontWeight = FontWeight.Medium,
    selectedContainerColor: Color = RoyalPurplePrimary,
    selectedLabelColor: Color = OnAccent,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    testTag: String? = null
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                text = label,
                style = TextStyle(fontSize = fontSize, fontWeight = fontWeight),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = leadingIcon,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = selectedContainerColor,
            selectedLabelColor = selectedLabelColor,
            selectedLeadingIconColor = selectedLabelColor
        ),
        modifier = if (testTag != null) modifier.testTag(testTag) else modifier
    )
}
