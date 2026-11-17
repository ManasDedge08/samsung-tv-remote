package com.manas.tvremote.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manas.tvremote.Keys
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** Apple's dark system colours: black, three greys for layered surfaces, and labels by opacity. */
object Palette {
    val background = Color(0xFF000000)
    val surface = Color(0xFF1C1C1E)
    val fill = Color(0xFF2C2C2E)
    val fillPressed = Color(0xFF48484A)
    val label = Color(0xFFFFFFFF)
    val secondary = Color(0x99EBEBF5)
    val tertiary = Color(0x4DEBEBF5)
    val separator = Color(0xFF38383A)
    val blue = Color(0xFF0A84FF)
    val red = Color(0xFFFF453A)
}

/** The phone's own system font, the way Apple uses SF everywhere. Sizes follow the iOS type ramp. */
object Type {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = Palette.label)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, color = Palette.label)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp, color = Palette.label)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, color = Palette.secondary)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Palette.secondary)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = Palette.secondary)
}

@Composable
fun rememberHaptic(): (strong: Boolean) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { strong ->
            view.performHapticFeedback(
                if (strong) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.CLOCK_TICK
            )
        }
    }
}

/**
 * A key that fires on touch-down so it feels instant, lightens and sinks slightly while held,
 * and with [repeat] keeps firing.
 */
@Composable
fun Key(
    label: String,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    color: Color = Palette.fill,
    repeat: Boolean = false,
    sink: Boolean = true,
    fireOnRelease: Boolean = false,
    onPress: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val haptic = rememberHaptic()
    val action by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (pressed) Palette.fillPressed else color, tween(90), label = "key")
    val scale by animateFloatAsState(
        if (pressed && sink) 0.94f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "sink",
    )
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(bg)
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick { action(); true }
            }
            .pointerInput(repeat, fireOnRelease) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        pressed = true
                        try {
                            if (!fireOnRelease) {
                                haptic(true)
                                action()
                            }
                            val repeater = if (repeat) launch {
                                delay(420)
                                while (isActive) {
                                    haptic(false)
                                    action()
                                    delay(110)
                                }
                            } else null
                            // Released inside the key: up is non-null. Slid off or cancelled: null.
                            val up = waitForUpOrCancellation()
                            repeater?.cancel()
                            if (fireOnRelease && up != null) {
                                haptic(true)
                                action()
                            }
                        } finally {
                            pressed = false
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun IconKey(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.fill,
    tint: Color = Palette.label,
    iconSize: Int = 26,
    repeat: Boolean = false,
    onPress: () -> Unit,
) = Key(label, modifier, color = color, repeat = repeat, onPress = onPress) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize.dp))
}

/** Capsule rocker, like the volume control on the Siri Remote. */
@Composable
fun Rocker(
    name: String,
    upIcon: ImageVector,
    downIcon: ImageVector,
    onUp: () -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
    middle: @Composable () -> Unit = {},
) {
    Column(
        modifier.width(68.dp).fillMaxHeight().clip(RoundedCornerShape(34.dp)).background(Palette.fill),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Key("$name up", Modifier.weight(1f).fillMaxWidth(), shape = RectangleShape, repeat = true, sink = false, onPress = onUp) {
            Icon(upIcon, null, tint = Palette.label, modifier = Modifier.size(26.dp))
        }
        middle()
        Key("$name down", Modifier.weight(1f).fillMaxWidth(), shape = RectangleShape, repeat = true, sink = false, onPress = onDown) {
            Icon(downIcon, null, tint = Palette.label, modifier = Modifier.size(26.dp))
        }
    }
}

/**
 * Touch surface. Drag moves the TV's focus one step per ~42dp; a tap is OK. A soft light follows
 * the finger, and the surface brightens for a moment on a tap, like pressing a clickpad.
 */
@Composable
fun Trackpad(modifier: Modifier, showHint: Boolean, onMove: (String) -> Unit, onTap: () -> Unit) {
    val haptic = rememberHaptic()
    val move by rememberUpdatedState(onMove)
    val tap by rememberUpdatedState(onTap)
    var touching by remember { mutableStateOf(false) }
    var clicked by remember { mutableStateOf(false) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    val glow by animateFloatAsState(if (touching) 1f else 0f, tween(180), label = "finger")
    LaunchedEffect(clicked) {
        if (clicked) {
            delay(60)
            clicked = false
        }
    }
    val click by animateColorAsState(if (clicked) Palette.fill else Palette.surface, tween(if (clicked) 40 else 260), label = "click")

    Box(
        modifier
            .clip(RoundedCornerShape(36.dp))
            .background(click)
            .semantics {
                contentDescription = "Touch surface. Swipe to move. Tap to select."
                onClick { tap(); true }
            }
            .drawBehind {
                if (glow > 0f) {
                    val r = 130.dp.toPx()
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.06f * glow), Color.Transparent), finger, r), r, finger)
                }
            }
            .pointerInput(Unit) {
                val stepPx = 42.dp.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    touching = true
                    finger = down.position
                    try {
                    var acc = Offset.Zero
                    var travel = 0f
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // A thumb resting on the surface and lifting is not a click.
                            if (!moved && change.uptimeMillis - down.uptimeMillis < 450) {
                                tap()
                                haptic(true)
                                clicked = true
                            }
                            break
                        }
                        val d = change.positionChange()
                        change.consume()
                        finger = change.position
                        acc += d
                        travel += d.getDistance()
                        if (!moved && travel > slop) moved = true
                        if (!moved) continue
                        while (true) {
                            if (abs(acc.x) >= stepPx && abs(acc.x) >= abs(acc.y)) {
                                move(if (acc.x > 0) Keys.RIGHT else Keys.LEFT)
                                acc = Offset(acc.x - sign(acc.x) * stepPx, 0f)
                            } else if (abs(acc.y) >= stepPx) {
                                move(if (acc.y > 0) Keys.DOWN else Keys.UP)
                                acc = Offset(0f, acc.y - sign(acc.y) * stepPx)
                            } else break
                            haptic(false)
                        }
                    }
                    } finally {
                        touching = false
                    }
                }
            },
    ) {
        if (showHint) {
            Text(
                "Swipe to move. Tap to select.",
                style = Type.subhead.copy(color = Palette.tertiary),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
fun Dpad(modifier: Modifier = Modifier, onKey: (String) -> Unit) = BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val side = minOf(maxWidth, maxHeight, 280.dp)
    Box(Modifier.size(side).clip(CircleShape).background(Palette.surface)) {
        val arrow = Modifier.size(side * 0.314f)
        val clear = Color.Transparent
        IconKey(Icons.Rounded.KeyboardArrowUp, "Up", arrow.align(Alignment.TopCenter), color = clear, tint = Palette.secondary, iconSize = 32, repeat = true) { onKey(Keys.UP) }
        IconKey(Icons.Rounded.KeyboardArrowDown, "Down", arrow.align(Alignment.BottomCenter), color = clear, tint = Palette.secondary, iconSize = 32, repeat = true) { onKey(Keys.DOWN) }
        IconKey(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Left", arrow.align(Alignment.CenterStart), color = clear, tint = Palette.secondary, iconSize = 32, repeat = true) { onKey(Keys.LEFT) }
        IconKey(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Right", arrow.align(Alignment.CenterEnd), color = clear, tint = Palette.secondary, iconSize = 32, repeat = true) { onKey(Keys.RIGHT) }
        Key("Select", Modifier.size(side * 0.4f).align(Alignment.Center), onPress = { onKey(Keys.OK) }) {}
    }
}
