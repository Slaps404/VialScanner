package org.lji.vialscanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Shared widgets. Deliberately few: the app is used one-handed, in gloves, in a
// cold room, so every screen is a list of large rows and a small number of
// full-width buttons.

object Ink {
    val bg = Color(0xFF0E0E10)
    val card = Color(0xFF1A1A1E)
    val line = Color(0xFF2A2A30)
    val text = Color(0xFFF2F2F5)
    val dim = Color(0xFF9A9AA5)
    val good = Color(0xFF3DDC84)
    val warn = Color(0xFFFFC107)
    val info = Color(0xFF64B5F6)
    val bad = Color(0xFFFF6B6B)
}

@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Ink.card,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Text(title, color = Ink.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    if (value.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, color = Ink.dim, fontSize = 13.sp, modifier = Modifier.width(120.dp))
        Text(value, color = Ink.text, fontSize = 13.sp)
    }
}

@Composable
fun Mono(text: String, color: Color = Ink.dim, size: Int = 12) {
    if (text.isEmpty()) return
    Text(text, color = color, fontSize = size.sp, fontFamily = FontFamily.Monospace)
}

@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/**
 * A text field that also offers what is already in the data set. Typing a new
 * box name has to stay possible - a box exists in the freezer before it exists
 * in the app - so this suggests rather than constrains.
 */
@Composable
fun SuggestField(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    Column {
        Field(label, value, onChange = onChange)
        val shown = options.filter { it.contains(value, ignoreCase = true) && it != value }.take(6)
        if (shown.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (o in shown) {
                    AssistChip(
                        onClick = { onChange(o) },
                        label = { Text(o) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Ink.dim, fontSize = 15.sp, modifier = Modifier.padding(32.dp))
    }
}

@Composable
fun <T> SimpleList(items: List<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = key) { row(it) }
    }
}

@Composable
fun ListRow(title: String, subtitle: String, trailing: String = "", onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ink.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            if (trailing.isNotEmpty()) Text(trailing, color = Ink.dim, fontSize = 12.sp)
        }
        if (subtitle.isNotEmpty()) Text(subtitle, color = Ink.dim, fontSize = 13.sp)
    }
    HorizontalDivider(color = Ink.line)
}
