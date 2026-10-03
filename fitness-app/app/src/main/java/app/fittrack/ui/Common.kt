package app.fittrack.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fittrack.domain.Calc
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ------------------------------------------------------------------ Theme

private val Orange = Color(0xFFFF6D00)

@Composable
fun FitTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Orange)
        else -> lightColorScheme(primary = Orange)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ------------------------------------------------------------------ Eingabefelder

/** Textfeld mit lokalem Zustand (kein Cursor-Springen beim Tippen). */
@Composable
fun SyncedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValueChange(it) },
        modifier = modifier,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
    )
}

/** Zahlenfeld (Komma oder Punkt), 0 wird als leeres Feld dargestellt. */
@Composable
fun NumberField(
    value: Double,
    onValue: (Double) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    decimals: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    var text by remember { mutableStateOf(Calc.numInput(value)) }
    LaunchedEffect(value) { if (Calc.parseNum(text) != value) text = Calc.numInput(value) }
    val pattern = if (decimals) Regex("\\d{0,4}([.,]\\d{0,2})?") else Regex("\\d{0,5}")
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            if (t.matches(pattern)) {
                text = t
                onValue(Calc.parseNum(t) ?: 0.0)
            }
        },
        modifier = modifier,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, style = textStyle) } },
        singleLine = true,
        textStyle = textStyle,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateChip(date: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text(Calc.dateDe(date)) },
        leadingIcon = { Icon(Icons.Filled.CalendarMonth, null, Modifier.height(AssistChipDefaults.IconSize)) },
        modifier = modifier,
    )
    if (open) {
        val initial = (Calc.parseDate(date) ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().format(Calc.DATE))
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Abbrechen") } },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeChip(
    label: String,
    time: String?,
    onChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    allowClear: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text("$label ${time ?: "–"}") },
        leadingIcon = { Icon(Icons.Filled.Schedule, null, Modifier.height(AssistChipDefaults.IconSize)) },
        modifier = modifier,
    )
    if (open) {
        val t = Calc.parseTime(time) ?: java.time.LocalTime.now()
        val state = rememberTimePickerState(initialHour = t.hour, initialMinute = t.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(String.format(java.util.Locale.US, "%02d:%02d", state.hour, state.minute))
                    open = false
                }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    if (allowClear) TextButton(onClick = { onChange(null); open = false }) { Text("Leeren") }
                    TextButton(onClick = { open = false }) { Text("Abbrechen") }
                }
            },
        )
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Löschen", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// ------------------------------------------------------------------ Anzeige

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
fun StatBox(label: String, value: String, modifier: Modifier = Modifier, big: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = if (big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Einfaches Liniendiagramm (x = Reihenfolge, y = Wert) mit Min/Max-Beschriftung. */
@Composable
fun LineChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    labels: List<String> = emptyList(),
    format: (Double) -> String = { Calc.num(it) },
) {
    if (values.size < 2) {
        Box(modifier.height(60.dp), contentAlignment = Alignment.Center) {
            Text("Für ein Diagramm braucht es mindestens 2 Einträge.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 11.sp, color = textColor)
    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        val min = values.min()
        val max = values.max()
        val span = if (max - min < 1e-6) 1.0 else max - min
        val left = 44.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val top = 8.dp.toPx()
        val w = size.width - left - 8.dp.toPx()
        fun x(i: Int) = left + w * i / (values.size - 1)
        fun y(v: Double) = (bottom - (bottom - top) * ((v - min) / span)).toFloat()
        drawLine(grid, Offset(left, top), Offset(left + w, top))
        drawLine(grid, Offset(left, bottom), Offset(left + w, bottom))
        drawText(measurer, format(max), Offset(0f, top - 6.dp.toPx()), style)
        drawText(measurer, format(min), Offset(0f, bottom - 10.dp.toPx()), style)
        if (labels.size == values.size) {
            drawText(measurer, labels.first(), Offset(left, bottom + 2.dp.toPx()), style)
            val lastLabel = measurer.measure(labels.last(), style)
            drawText(lastLabel, topLeft = Offset(left + w - lastLabel.size.width, bottom + 2.dp.toPx()))
        }
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
        values.forEachIndexed { i, v -> drawCircle(line, 4.dp.toPx(), Offset(x(i), y(v))) }
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

/** Listeneintrag mit mehreren Elementen untereinander. */
fun LazyListScope.block(key: Any? = null, content: @Composable ColumnScope.() -> Unit) =
    item(key = key) { Column(Modifier.fillMaxWidth()) { content() } }

/** Kompaktes Zahlenfeld für Satz-Zeilen (Gewicht, Wiederholungen). */
@Composable
fun CompactNumberField(
    value: Double,
    onValue: (Double) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    decimals: Boolean = true,
) {
    var text by remember { mutableStateOf(Calc.numInput(value)) }
    LaunchedEffect(value) { if (Calc.parseNum(text) != value) text = Calc.numInput(value) }
    val pattern = if (decimals) Regex("\\d{0,4}([.,]\\d{0,2})?") else Regex("\\d{0,4}")
    val style = MaterialTheme.typography.bodyLarge.copy(
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium,
    )
    BasicTextField(
        value = text,
        onValueChange = { t ->
            if (t.matches(pattern)) {
                text = t
                onValue(Calc.parseNum(t) ?: 0.0)
            }
        },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize().padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                if (text.isEmpty()) Text(placeholder, style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)))
                inner()
            }
        },
    )
}
