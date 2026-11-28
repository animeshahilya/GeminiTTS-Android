package com.animeshahilya.geminitts.ui.main

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

fun openTtsSettings(context: Context) {
    try {
        context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })
}

/**
 * AlertDialog configured to respect system bars and the on-screen keyboard so input fields,
 * validation messages, and Confirm/Cancel actions stay visible while typing.
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = modifier.systemBarsPadding().imePadding(),
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton
    )
}

/** Clean Settings list item leading to a focused sub-screen. */
@Composable
fun PreferenceRow(title: String, summary: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$title, $summary"
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Text("Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider()
    }
}

@Composable
fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun CheckboxRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
fun <T> RadioOptionGroup(
    options: List<T>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    summary: (T) -> String,
    modifier: Modifier = Modifier
) {
    Column(modifier.selectableGroup()) {
        options.forEach { item ->
            val selected = isSelected(item)
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .selectable(
                        selected = selected,
                        onClick = { onSelect(item) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(label(item))
                    Text(summary(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun <T> DropdownSelector(
    label: String,
    options: List<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text(label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(itemLabel(option)) },
                    onClick = { onSelect(option); open = false }
                )
            }
        }
    }
}

@Composable
fun OverrideSliders(
    prefix: String,
    speed: Float,
    pitch: Float,
    volume: Float,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    FactorSlider("$prefix speed", speed, 0.5f..2.5f, step = 0.05f, defaultValue = 1f, onChange = onSpeedChange)
    FactorSlider("$prefix pitch", pitch, 0.5f..2f, step = 0.05f, defaultValue = 1f, onChange = onPitchChange)
    PercentSlider("$prefix volume", (volume * 100).roundToInt(), 25..200, step = 5, defaultPercent = 100) {
        onVolumeChange(it / 100f)
    }
}

fun formatFactor(factor: Float): String = "%.2fx".format(Locale.ROOT, factor)

fun formatOverrideFactors(speed: Float, pitch: Float, volume: Float): List<String> = listOfNotNull(
    if (speed != 1f) "${formatFactor(speed)} speed" else null,
    if (pitch != 1f) "${formatFactor(pitch)} pitch" else null,
    if (volume != 1f) "${(volume * 100).roundToInt()}% vol" else null
)

@Composable
fun FactorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float = 0.05f,
    defaultValue: Float = 1f,
    onChange: (Float) -> Unit
) {
    val clamped = value.coerceIn(range.start, range.endInclusive)
    val shown = formatFactor(clamped)
    fun snap(v: Float): Float {
        val stepped = ((v / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
        return (stepped * 1000f).roundToInt() / 1000f
    }
    StepSliderRow(
        label = label,
        shown = shown,
        value = clamped,
        range = range,
        steps = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0),
        canDecrease = clamped > range.start + 0.001f,
        canIncrease = clamped < range.endInclusive - 0.001f,
        onDecrease = { onChange(snap(clamped - step)) },
        onIncrease = { onChange(snap(clamped + step)) },
        onSlide = { raw -> onChange(snap(raw)) },
        onReset = if (abs(clamped - defaultValue) > 0.01f) { { onChange(defaultValue) } } else null
    )
}

@Composable
fun PercentSlider(
    label: String,
    valuePercent: Int,
    range: IntRange = 25..200,
    step: Int = 5,
    defaultPercent: Int = 100,
    onChange: (Int) -> Unit
) {
    val clamped = valuePercent.coerceIn(range.first, range.last)
    StepSliderRow(
        label = label,
        shown = "$clamped%",
        value = clamped.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
        steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        canDecrease = clamped > range.first,
        canIncrease = clamped < range.last,
        onDecrease = { onChange((clamped - step).coerceIn(range.first, range.last)) },
        onIncrease = { onChange((clamped + step).coerceIn(range.first, range.last)) },
        onSlide = { raw -> onChange(((raw / step).roundToInt() * step).coerceIn(range.first, range.last)) },
        onReset = if (clamped != defaultPercent) { { onChange(defaultPercent) } } else null
    )
}

@Composable
fun IntSlider(
    label: String,
    value: Int,
    range: IntRange,
    unit: String,
    defaultValue: Int,
    onChange: (Int) -> Unit
) {
    val clamped = value.coerceIn(range.first, range.last)
    StepSliderRow(
        label = label,
        shown = "$clamped $unit",
        value = clamped.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
        canDecrease = clamped > range.first,
        canIncrease = clamped < range.last,
        onDecrease = { onChange((clamped - 1).coerceIn(range.first, range.last)) },
        onIncrease = { onChange((clamped + 1).coerceIn(range.first, range.last)) },
        onSlide = { raw -> onChange(raw.roundToInt().coerceIn(range.first, range.last)) },
        onReset = if (clamped != defaultValue) { { onChange(defaultValue) } } else null
    )
}

@Composable
fun PauseSlider(label: String, valueMs: Int, maxMs: Int, onChange: (Int) -> Unit) {
    val clamped = valueMs.coerceIn(0, maxMs)
    val shown = if (clamped == 0) "none" else "${"%.2f".format(Locale.ROOT, clamped / 1000f)} seconds"
    StepSliderRow(
        label = label,
        shown = shown,
        value = clamped.toFloat(),
        range = 0f..maxMs.toFloat(),
        steps = (maxMs / 50 - 1).coerceAtLeast(0),
        canDecrease = clamped > 0,
        canIncrease = clamped < maxMs,
        onDecrease = { onChange((clamped - 50).coerceIn(0, maxMs)) },
        onIncrease = { onChange((clamped + 50).coerceIn(0, maxMs)) },
        onSlide = { raw -> onChange(((raw / 50).roundToInt() * 50).coerceIn(0, maxMs)) },
        onReset = null
    )
}

@Suppress("DEPRECATION")
@Composable
private fun StepSliderRow(
    label: String,
    shown: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onSlide: (Float) -> Unit,
    onReset: (() -> Unit)?
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$label: $shown", modifier = Modifier.weight(1f))
            if (onReset != null) {
                TextButton(
                    onClick = onReset,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Reset $label to default" }
                ) { Text("Reset") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onDecrease,
                enabled = canDecrease,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Decrease $label" }
            ) { Text("−") }
            Slider(
                value = value,
                onValueChange = onSlide,
                valueRange = range,
                steps = steps,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = label
                    stateDescription = shown
                }
            )
            OutlinedButton(
                onClick = onIncrease,
                enabled = canIncrease,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Increase $label" }
            ) { Text("+") }
        }
    }
}

/** Friendly voice descriptions for known Google GenAI star voices, falling back cleanly for new IDs. */
fun describeVoice(id: String, gender: String): String {
    val hint = when (id.lowercase()) {
        "star02" -> "female, medium pitch"
        "star04" -> "male, medium-high pitch"
        "star05" -> "male, low pitch"
        "star08" -> "female, low pitch"
        "star11" -> "male, medium pitch"
        "star12" -> "female, medium-high pitch"
        "star13" -> "male, deep medium"
        "star16" -> "female, medium-low"
        else -> if (gender == "unknown" || gender.isBlank()) "" else gender
    }
    return if (hint.isEmpty()) id else "$id, $hint"
}
