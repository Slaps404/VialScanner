package org.lji.vialscanner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Shared widgets, matching `App/Components.swift`.
//
// Deliberately few: the app is used one-handed, in gloves, in a cold room, so
// every screen is a list of large rows and a small number of full-width
// buttons. The shapes come from Swift - a bordered card on the page colour, a
// capsule for anything you pick between, a circle for anything you press.

/**
 * A card: page-coloured, hairline border, no shadow. Swift uses the border
 * rather than elevation because in light mode the surface and the page are the
 * same white, so a shadow is the only thing that would separate them and a
 * shadow reads as Material, not as this.
 */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Metric.cardRadius))
            .background(Ink.surface)
            .border(1.dp, Ink.hairline, RoundedCornerShape(Metric.cardRadius))
            .padding(Metric.pad),
        content = content,
    )
}

/** A card with the small uppercase section title Swift puts above its groups. */
@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.padding(horizontal = Metric.pad, vertical = 8.dp)) {
        Card {
            if (title != null) {
                Text(
                    title.uppercase(),
                    color = Ink.textSecond,
                    fontSize = Type.caption,
                    fontWeight = Type.labelWeight,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            content()
        }
    }
}

/**
 * A setting: what it does on the left, the control on the right, and one line
 * of plain English underneath. The explanation is not optional - every one of
 * these changes when the scanner is allowed to answer, and a bare label like
 * "Park uncertain" tells nobody what it costs.
 */
@Composable
fun SettingRow(
    title: String,
    detail: String? = null,
    control: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Ink.text, fontSize = Type.heading, fontWeight = FontWeight.Normal)
            if (detail != null) {
                Text(detail, color = Ink.textSecond, fontSize = Type.label, lineHeight = Type.body)
            }
        }
        control?.invoke()
    }
}

@Composable
fun ToggleRow(
    title: String,
    detail: String? = null,
    value: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(title, detail) {
        Switch(
            checked = value,
            enabled = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Ink.accent,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Ink.fillStrong,
                uncheckedThumbColor = Ink.surface,
                uncheckedBorderColor = Ink.hairline,
            ),
        )
    }
}

/** Label, explanation, slider, and the live value in mono on the right. */
@Composable
fun SliderRow(
    title: String,
    detail: String? = null,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    format: (Double) -> String,
    onChange: (Double) -> Unit,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(title, color = Ink.text, fontSize = Type.heading)
        if (detail != null) {
            Text(detail, color = Ink.textSecond, fontSize = Type.label, lineHeight = Type.body)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
                onValueChange = { onChange(it.toDouble()) },
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Ink.accent,
                    inactiveTrackColor = Ink.fillStrong,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(format(value), color = Ink.textSecond, fontSize = Type.mono, fontFamily = Type.monoFamily)
        }
    }
}

/**
 * The capsule picker Swift uses everywhere instead of a segmented control:
 * black-filled when chosen, grey fill when not. Scrolls, because a box name is
 * as long as somebody felt like typing.
 */
@Composable
fun <T> PillRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    onPick: (T) -> Unit,
) {
    Row(modifier.horizontalScroll(rememberScrollState())) {
        for (o in options) {
            // Every Swift call site that picks from a PillRow-shaped control
            // (AuditView's scope picker, InventoryView's filter, SettingsView's
            // auto-commit bar) fires Haptics.tap() right after; putting it here
            // once covers all of them instead of at each call site.
            Pill(label(o), o == selected) { onPick(o); Haptics.tap() }
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Ink.onSolid else Ink.textSecond,
        fontSize = Type.label,
        fontWeight = Type.labelWeight,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Ink.solid else Ink.fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** A full-width primary action: solid, capsule, the one thing you came to do. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) Ink.onSolid else Ink.textFaint,
        fontSize = Type.heading,
        fontWeight = Type.headingWeight,
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (enabled) Ink.solid else Ink.fill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** The same shape, outlined: everything that is not the primary action. */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        color = Ink.text,
        fontSize = Type.label,
        fontWeight = Type.labelWeight,
        modifier = modifier
            .clip(CircleShape)
            .background(Ink.fill)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 16.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
fun KeyValueRow(key: String, value: String) {
    if (value.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(key, color = Ink.textSecond, fontSize = Type.label, modifier = Modifier.width(120.dp))
        Text(value, color = Ink.text, fontSize = Type.label)
    }
}

@Composable
fun Mono(text: String, color: Color = Ink.textSecond) {
    if (text.isEmpty()) return
    Text(text, color = color, fontSize = Type.caption, fontFamily = Type.monoFamily)
}

/** The rounded search field from the inventory screen: a fill, not a box. */
@Composable
fun SearchField(placeholder: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Ink.fill)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = Ink.textSecond, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicField(placeholder, value, Modifier.weight(1f).padding(vertical = 14.dp), onChange)
    }
}

@Composable
private fun BasicField(placeholder: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    Box(modifier) {
        if (value.isEmpty()) {
            Text(placeholder, color = Ink.textFaint, fontSize = Type.body)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Ink.text, fontSize = Type.body),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink.text),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A labelled entry field inside a card.
 *
 * `source`, when set, is a quiet badge naming where the value came from - a
 * barcode decode, an OCR read, or a remembered default - so the operator
 * knows what to double check without the field turning into a report. */
@Composable
fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    source: FieldSource? = null,
    onChange: (String) -> Unit,
) {
    Column(modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Ink.textSecond, fontSize = Type.caption, fontWeight = Type.labelWeight)
            if (source != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    source.label,
                    color = if (source == FieldSource.BARCODE) Ink.accent else Ink.dim,
                    fontSize = Type.caption,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Ink.fill)
                .padding(horizontal = 14.dp),
        ) {
            BasicField("", value, Modifier.weight(1f).padding(vertical = 12.dp), onChange)
        }
    }
}

/**
 * A text field that also offers what is already in the data set. Typing a new
 * box name has to stay possible - a box exists in the freezer before it exists
 * in the app - so this suggests rather than constrains.
 */
@Composable
fun SuggestField(
    label: String,
    value: String,
    options: List<String>,
    source: FieldSource? = null,
    onChange: (String) -> Unit,
) {
    Column {
        Field(label, value, source = source, onChange = onChange)
        val shown = options.filter { it.contains(value, ignoreCase = true) && it != value }.take(6)
        if (shown.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (o in shown) {
                    Pill(o, false) { onChange(o); Haptics.tap() }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Ink.textSecond,
            fontSize = Type.body,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(40.dp),
        )
    }
}

@Composable
fun <T> SimpleList(items: List<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Metric.pad, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = key) { row(it) }
    }
}

/**
 * One vial, one row, one card. Swift stopped using dividers here: at arm's
 * length in a cold room a separated card is easier to aim a gloved thumb at
 * than a row in a continuous list, and the leading circle gives the AbNo - the
 * only number anyone actually reads off this screen - a fixed place to be.
 */
@Composable
fun ListRow(
    title: String,
    subtitle: String,
    leading: String? = null,
    trailing: String = "",
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Metric.radius))
            .background(Ink.surface)
            .border(1.dp, Ink.hairline, RoundedCornerShape(Metric.radius))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(Ink.fill),
                contentAlignment = Alignment.Center,
            ) {
                Text(leading, color = Ink.text, fontSize = Type.label, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink.text, fontSize = Type.heading, fontWeight = FontWeight.Normal)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = Ink.textSecond, fontSize = Type.label)
            }
        }
        if (trailing.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Text(trailing, color = tint ?: Ink.textSecond, fontSize = Type.caption, fontWeight = Type.labelWeight)
        }
    }
}
