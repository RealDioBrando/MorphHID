package dev.morphhid.ui.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.morphhid.core.profile.KeyRow
import dev.morphhid.core.profile.WidgetSpec

private val MODIFIER_KEYS = setOf("shift", "leftShift", "rightShift", "leftCtrl", "rightCtrl", "leftAlt", "rightAlt", "leftGui", "rightGui", "winKey", "cmdKey")

private fun display(name: String): String = when (name) {
    "space" -> "SPACE"
    "enter", "return" -> "ENTER"
    "backspace" -> "BKSP"
    "escape", "esc" -> "ESC"
    "tab" -> "TAB"
    "delete", "del" -> "DEL"
    "capsLock" -> "CAPS"
    "leftShift", "rightShift", "shift" -> "SHIFT"
    "leftCtrl", "rightCtrl" -> "CTRL"
    "leftAlt", "rightAlt" -> "ALT"
    "leftGui", "rightGui", "winKey", "cmdKey" -> "OS"
    "up" -> "▲"
    "down" -> "▼"
    "left" -> "◀"
    "right" -> "▶"
    else -> name.replaceFirstChar { it.uppercase() }
}

@Composable
fun KeyGridWidget(widget: WidgetSpec.KeyGrid, host: WidgetHost, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        widget.rows.forEachIndexed { rowIndex, row: KeyRow ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.keys.forEach { key ->
                    val displayLabel = display(key)
                    val isModifier = key in MODIFIER_KEYS
                    KeyCap(
                        label = displayLabel,
                        modifier = Modifier.weight(1f),
                        emphasize = isModifier,
                    ) { pressed ->
                        host.onKey("keyboard.$key", pressed)
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(
    label: String,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
    onPress: (Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val container = when {
        pressed -> MaterialTheme.colorScheme.primary
        emphasize -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .background(container)
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onPress(true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            onPress(false)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (pressed) Color.Black else MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}