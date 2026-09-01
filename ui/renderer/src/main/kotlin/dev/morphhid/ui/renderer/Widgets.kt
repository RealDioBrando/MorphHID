package dev.morphhid.ui.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.morphhid.core.profile.BindingSpec
import dev.morphhid.core.profile.WidgetSpec

@Composable
fun ButtonWidget(widget: WidgetSpec.Button, host: WidgetHost, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val container = if (pressed) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(container)
            .pointerInput(widget.id) {
                detectTapGestures(
                    onTap = { widget.onTap?.let(host::onBinding) },
                    onDoubleTap = { widget.onDoubleTap?.let(host::onBinding) },
                    onLongPress = { widget.onLongPress?.let(host::onBinding) },
                    onPress = {
                        pressed = true
                        widget.momentaryKey?.let { host.onKey(it, true) }
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            widget.momentaryKey?.let { host.onKey(it, false) }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = widget.label.ifBlank { widget.id },
            color = if (pressed) Color.Black else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
fun ToggleWidget(widget: WidgetSpec.Toggle, host: WidgetHost, modifier: Modifier = Modifier) {
    var on by remember { mutableStateOf(widget.defaultOn) }
    val shape = RoundedCornerShape(16.dp)
    val container = if (on) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(container)
            .pointerInput(widget.id) {
                detectTapGestures(
                    onTap = {
                        val next = !on
                        on = next
                        if (next) {
                            widget.control?.let { host.onControlSet(it, true) }
                            widget.onTurnOn?.let(host::onBinding)
                        } else {
                            widget.control?.let { host.onControlSet(it, false) }
                            widget.onTurnOff?.let(host::onBinding)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = widget.label.ifBlank { widget.id },
                color = if (on) Color.Black else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (on) "ON" else "OFF",
                color = if (on) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
fun TrackPointWidget(widget: WidgetSpec.TrackPoint, host: WidgetHost, modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val shape = RoundedCornerShape(24.dp)
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(widget.id) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragDistance = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        if (delta != androidx.compose.ui.geometry.Offset.Zero) {
                            dragDistance += delta.getDistance()
                            host.onPointerDelta(delta.x * widget.sensitivity, delta.y * widget.sensitivity)
                            change.consume()
                        }
                    }
                    if (dragDistance < slop) {
                        val button = widget.tapButton ?: return@awaitEachGesture
                        val keyId = "pointer.button$button"
                        host.onKey(keyId, true)
                        scope.launch {
                            delay(60)
                            host.onKey(keyId, false)
                        }
                    }
                }
            },
    ) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f),
            radius = size.minDimension * 0.28f,
            center = center,
        )
        drawCircle(
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.20f),
            radius = size.minDimension * 0.10f,
            center = center,
        )
    }
}

@Composable
fun LedWidget(widget: WidgetSpec.Led, ledStates: Map<String, Boolean>, modifier: Modifier = Modifier) {
    val on = ledStates["led." + widget.led] == true
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .aspectRatio(1f)
                    .fillMaxSize(0.35f)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (on) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
            )
            Text(
                text = widget.label.ifBlank { widget.led },
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
fun LabelWidget(widget: WidgetSpec.Label, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = widget.text.ifBlank { widget.label },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SliderWidget(widget: WidgetSpec.Slider, host: WidgetHost, modifier: Modifier = Modifier) {
    var value by remember { mutableStateOf(0.5f) }
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = {
            value = it
            host.onAxis(widget.control, it * 2f - 1f)
        },
        valueRange = 0f..1f,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun DpadWidget(widget: WidgetSpec.Dpad, host: WidgetHost, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(widget.id) {
                detectTapGestures(
                    onPress = { offset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val x = offset.x / w
                        val y = offset.y / h
                        val horizontal = when {
                            x < 0.35f -> -1
                            x > 0.65f -> 1
                            else -> 0
                        }
                        val vertical = when {
                            y < 0.35f -> -1
                            y > 0.65f -> 1
                            else -> 0
                        }
                        val binding = when {
                            horizontal < 0 && vertical < 0 && widget.diagonals -> widget.left
                            horizontal > 0 && vertical < 0 && widget.diagonals -> widget.right
                            horizontal < 0 && vertical > 0 && widget.diagonals -> widget.left
                            horizontal > 0 && vertical > 0 && widget.diagonals -> widget.right
                            horizontal < 0 -> widget.left
                            horizontal > 0 -> widget.right
                            vertical < 0 -> widget.up
                            vertical > 0 -> widget.down
                            else -> null
                        }
                        binding?.let(host::onBinding)
                        tryAwaitRelease()
                    },
                )
            },
    ) {
        val line = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f)
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawLine(line, androidx.compose.ui.geometry.Offset(cx, cy * 0.35f), androidx.compose.ui.geometry.Offset(cx, cy * 1.65f), 8f)
        drawLine(line, androidx.compose.ui.geometry.Offset(cx * 0.35f, cy), androidx.compose.ui.geometry.Offset(cx * 1.65f, cy), 8f)
    }
}

@Composable
fun JoystickWidget(widget: WidgetSpec.Joystick, host: WidgetHost, modifier: Modifier = Modifier) {
    var knob by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    val knobColor = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(widget.id) {
                detectDragGestures(
                    onDragStart = { offset -> knob = offset },
                    onDragEnd = {
                        knob = null
                        if (widget.springReturn) {
                            widget.xAxis?.let { host.onAxis(it, 0f) }
                            widget.yAxis?.let { host.onAxis(it, 0f) }
                        }
                    },
                    onDragCancel = {
                        knob = null
                        if (widget.springReturn) {
                            widget.xAxis?.let { host.onAxis(it, 0f) }
                            widget.yAxis?.let { host.onAxis(it, 0f) }
                        }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    val current = knob ?: change.position
                    val next = androidx.compose.ui.geometry.Offset(
                        (current.x + dragAmount.x).coerceIn(0f, size.width.toFloat()),
                        (current.y + dragAmount.y).coerceIn(0f, size.height.toFloat()),
                    )
                    knob = next
                    val nx = (next.x / size.width) * 2f - 1f
                    val ny = (next.y / size.height) * 2f - 1f
                    val dz = widget.deadzone
                    val zx = if (kotlin.math.abs(nx) < dz) 0f else nx
                    val zy = if (kotlin.math.abs(ny) < dz) 0f else ny
                    widget.xAxis?.let { host.onAxis(it, zx) }
                    widget.yAxis?.let { host.onAxis(it, zy) }
                }
            },
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f),
            radius = size.minDimension / 2f * 0.9f,
            center = androidx.compose.ui.geometry.Offset(cx, cy),
        )
        val k = knob ?: androidx.compose.ui.geometry.Offset(cx, cy)
        drawCircle(
            color = knobColor,
            radius = size.minDimension * 0.14f,
            center = k,
        )
    }
}

@Composable
fun PointerPadWidget(widget: WidgetSpec.PointerPad, host: WidgetHost, modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val shape = RoundedCornerShape(16.dp)
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .aspectRatio(if (widget.wide) 2.0f else 1.2f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(widget.id) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragDistance = 0f
                    val scrollMode = widget.scrollStrip && down.position.x > size.width * 0.72f
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            if (delta != androidx.compose.ui.geometry.Offset.Zero) {
                                dragDistance += delta.getDistance()
                                if (scrollMode) {
                                    host.onWheel(-delta.y * widget.sensitivity)
                                } else {
                                    host.onPointerDelta(delta.x * widget.sensitivity, delta.y * widget.sensitivity)
                                }
                                change.consume()
                            }
                        }
                    } finally {
                        if (!scrollMode && dragDistance < slop) {
                            val button = widget.tapButton ?: return@awaitEachGesture
                            val keyId = "pointer.button$button"
                            host.onKey(keyId, true)
                            scope.launch {
                                delay(60)
                                host.onKey(keyId, false)
                            }
                        }
                    }
                }
            },
    ) {
        // Subtle cross-hatch to indicate a touch surface.
        val line = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f)
        val step = size.width / 6f
        var x = step
        while (x < size.width) {
            drawLine(line, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 2f)
            x += step
        }
        var y = step
        while (y < size.height) {
            drawLine(line, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 2f)
            y += step
        }
    }
}
