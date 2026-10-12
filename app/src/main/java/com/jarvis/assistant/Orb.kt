package com.jarvis.assistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ───────────────────────── palette ─────────────────────────
val Ink = Color(0xFF04070C)
val Deck = Color(0xFF0B1422)
val Arc = Color(0xFF35E0FF)
val Ion = Color(0xFF9B7BFF)
val Ember = Color(0xFFFFB547)
val Mist = Color(0xFF8FA6BA)
private val Deep = Color(0xFF2F6BFF)
private val Mint = Color(0xFF2BF5C4)
private val Rose = Color(0xFFFF5CC8)
private val Coral = Color(0xFFFF7A59)

private fun palette(s: OrbState): Pair<Color, Color> = when (s) {
    OrbState.LISTENING -> Arc to Mint
    OrbState.THINKING -> Ion to Rose
    OrbState.SPEAKING -> Ember to Coral
    else -> Arc to Deep
}

/** A running clock in seconds, updated every frame. Read `.value` only inside draw blocks. */
@Composable
fun rememberClock(): State<Float> {
    val t = remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            androidx.compose.runtime.withFrameNanos { t.value = (it - start) / 1_000_000_000f }
        }
    }
    return t
}

private val TAU = (2.0 * PI).toFloat()

// ───────────────────────── the orb ─────────────────────────
@Composable
fun JarvisOrb(state: OrbState, level: State<Float>, time: State<Float>, modifier: Modifier = Modifier) {
    val (pa, pb) = palette(state)
    val c1 by animateColorAsState(pa, tween(600), label = "orb1")
    val c2 by animateColorAsState(pb, tween(600), label = "orb2")
    val smooth = remember { floatArrayOf(0f) }

    Canvas(modifier) {
        val t = time.value
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = min(size.width, size.height) * 0.28f

        val target = when (state) {
            OrbState.LISTENING -> 0.12f + level.value * 0.88f
            OrbState.SPEAKING -> 0.30f + 0.32f * (0.5f + 0.5f * sin(t * 9f)) * (0.6f + 0.4f * sin(t * 3.3f))
            OrbState.THINKING -> 0.26f + 0.10f * sin(t * 4f)
            else -> 0.07f + 0.05f * sin(t * 1.3f)
        }
        smooth[0] += (target - smooth[0]) * 0.18f
        val a = smooth[0]
        val spin = when (state) {
            OrbState.THINKING -> 3.2f
            OrbState.LISTENING -> 1.4f
            OrbState.SPEAKING -> 1.8f
            else -> 0.6f
        }

        fun ring(radius: Float, startDeg: Float, sweep: Float, width: Float, color: Color) {
            drawArc(
                color = color, startAngle = startDeg, sweepAngle = sweep, useCenter = false,
                topLeft = Offset(c.x - radius, c.y - radius), size = Size(radius * 2f, radius * 2f),
                style = Stroke(width, cap = StrokeCap.Round)
            )
        }

        // outer glow
        drawCircle(
            Brush.radialGradient(listOf(c1.copy(alpha = 0.28f + 0.30f * a), Color.Transparent), center = c, radius = r * 2.5f),
            radius = r * 2.5f, center = c
        )

        // ripples that travel outward, stronger with louder audio
        for (i in 0..2) {
            val ph = (t * 0.45f * (0.7f + spin * 0.4f) + i / 3f) % 1f
            drawCircle(
                c2.copy(alpha = (1f - ph) * (0.08f + 0.32f * a)),
                radius = r * (1.05f + 1.0f * ph), center = c, style = Stroke(2.5f)
            )
        }

        // tick ring
        val tickR = r * 1.34f
        for (k in 0 until 72) {
            val ang = k / 72f * TAU + t * 0.16f * spin
            val long = k % 6 == 0
            val len = if (long) 15f else 7f
            val cs = cos(ang)
            val sn = sin(ang)
            drawLine(
                c1.copy(alpha = if (long) 0.8f else 0.28f),
                Offset(c.x + cs * tickR, c.y + sn * tickR), Offset(c.x + cs * (tickR + len), c.y + sn * (tickR + len)),
                strokeWidth = if (long) 2.6f else 1.4f
            )
        }

        // plasma body
        val path = Path()
        val n = 90
        for (k in 0..n) {
            val ang = k.toFloat() / n * TAU
            val wob = 1f +
                (0.03f + 0.07f * a) * sin(3f * ang + t * 1.9f * minOf(spin, 2f)) +
                (0.02f + 0.05f * a) * sin(5f * ang - t * 2.4f) +
                0.012f * sin(8f * ang + t * 3.3f)
            val rr = r * 0.80f * wob * (1f + 0.10f * a)
            val x = c.x + cos(ang) * rr
            val y = c.y + sin(ang) * rr
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(
            path,
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.95f), c1, c2.copy(alpha = 0.9f), lerp(c2, Color.Black, 0.65f)),
                center = Offset(c.x - r * 0.18f, c.y - r * 0.22f), radius = r * 1.15f
            )
        )
        drawPath(path, c1.copy(alpha = 0.95f), style = Stroke(3f))

        // swirl inside the body
        for (i in 0..2) {
            ring(r * (0.30f + 0.12f * i), t * (60f + 25f * i) * spin * (if (i % 2 == 0) 1f else -1f) + i * 70f, 80f + 20f * i, 2.2f, Color.White.copy(alpha = 0.40f))
        }
        drawCircle(Color.White.copy(alpha = 0.20f), radius = r * 0.17f, center = Offset(c.x - r * 0.28f, c.y - r * 0.34f))

        // reactor rings
        ring(r * 1.00f, t * 42f * spin, 70f, 4f, c1)
        ring(r * 1.00f, 180f + t * 42f * spin, 40f, 4f, c2)
        ring(r * 1.14f, -t * 58f * spin, 110f, 2.5f, c2.copy(alpha = 0.85f))
        ring(r * 1.14f, 140f - t * 58f * spin, 30f, 2.5f, c1.copy(alpha = 0.85f))

        // orbiting particles
        for (k in 0 until 16) {
            val ph = k * 0.7f
            val ang = t * (0.35f + (k % 5) * 0.11f) * spin + ph
            val rad = r * (1.52f + 0.20f * sin(ph * 3f + t * 0.8f))
            drawCircle(
                (if (k % 2 == 0) c1 else c2).copy(alpha = 0.35f + 0.5f * abs(sin(t + ph))),
                radius = 1.8f + (k % 3) * 1.2f, center = Offset(c.x + cos(ang) * rad, c.y + sin(ang) * rad)
            )
        }
    }
}

// ───────────────────────── side-screen effects ─────────────────────────
@Composable
fun SideFx(state: OrbState, level: State<Float>, time: State<Float>, pulse: Int) {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(pulse) {
        flash.snapTo(1f)
        flash.animateTo(0f, tween(1100))
    }
    val (pa, pb) = palette(state)
    val c1 by animateColorAsState(pa, tween(600), label = "fx1")
    val c2 by animateColorAsState(pb, tween(600), label = "fx2")
    val smooth = remember { floatArrayOf(0f) }

    Canvas(Modifier.fillMaxSize()) {
        val t = time.value
        val w = size.width
        val h = size.height
        val target = when (state) {
            OrbState.LISTENING -> level.value
            OrbState.SPEAKING -> 0.35f + 0.3f * (0.5f + 0.5f * sin(t * 9f))
            OrbState.THINKING -> 0.3f
            else -> 0.1f
        }
        smooth[0] += (target - smooth[0]) * 0.2f
        val lv = smooth[0]
        val fl = flash.value
        val strength = (0.30f + 0.55f * lv + 0.7f * fl).coerceIn(0f, 1f)

        // backdrop + faint grid
        drawRect(
            Brush.radialGradient(listOf(Color(0xFF0B1A2C), Ink), center = Offset(w / 2f, h * 0.45f), radius = maxOf(w, h) * 0.8f)
        )
        val step = 44.dp.toPx()
        var gx = -((t * 6f) % step)
        while (gx < w) {
            drawLine(Color.White.copy(alpha = 0.035f), Offset(gx, 0f), Offset(gx, h), 1f)
            gx += step
        }
        var gy = ((t * 6f) % step)
        while (gy < h) {
            drawLine(Color.White.copy(alpha = 0.035f), Offset(0f, gy), Offset(w, gy), 1f)
            gy += step
        }
        val sy = ((t * 0.12f) % 1f) * h
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, c1.copy(alpha = 0.10f), Color.Transparent), startY = sy - 70f, endY = sy + 70f),
            topLeft = Offset(0f, sy - 70f), size = Size(w, 140f)
        )

        // edge glow, left / right / bottom
        val gw = 90.dp.toPx()
        drawRect(
            Brush.horizontalGradient(listOf(c1.copy(alpha = 0.55f * strength), Color.Transparent), startX = 0f, endX = gw),
            size = Size(gw, h)
        )
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, c1.copy(alpha = 0.55f * strength)), startX = w - gw, endX = w),
            topLeft = Offset(w - gw, 0f), size = Size(gw, h)
        )
        val bh = gw * 0.8f
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, c2.copy(alpha = 0.40f * strength)), startY = h - bh, endY = h),
            topLeft = Offset(0f, h - bh), size = Size(w, bh)
        )
        drawRect(c1.copy(alpha = 0.9f * strength), size = Size(2.dp.toPx(), h))
        drawRect(c1.copy(alpha = 0.9f * strength), topLeft = Offset(w - 2.dp.toPx(), 0f), size = Size(2.dp.toPx(), h))

        // light streaks racing along the edges (opposite directions)
        val streakLen = 150.dp.toPx()
        for (i in 0 until 5) {
            val p = (t * (0.10f + 0.045f * i) + i * 0.23f) % 1f
            val y = p * (h + streakLen) - streakLen
            val brush = Brush.verticalGradient(
                listOf(Color.Transparent, c1.copy(alpha = 0.95f * strength), Color.Transparent), startY = y, endY = y + streakLen
            )
            drawRoundRect(brush, Offset(0f, y), Size(5.dp.toPx(), streakLen), CornerRadius(4f))
            val y2 = h - y - streakLen
            val brush2 = Brush.verticalGradient(
                listOf(Color.Transparent, c2.copy(alpha = 0.95f * strength), Color.Transparent), startY = y2, endY = y2 + streakLen
            )
            drawRoundRect(brush2, Offset(w - 5.dp.toPx(), y2), Size(5.dp.toPx(), streakLen), CornerRadius(4f))
        }

        // audio-reactive equalizer bars hugging both edges
        val bars = 28
        val top0 = h * 0.22f
        val slot = h * 0.54f / bars
        val maxLen = 40.dp.toPx()
        for (k in 0 until bars) {
            val y = top0 + k * slot
            val m = abs(sin(t * 2.6f + k * 0.55f) * cos(t * 1.3f + k * 0.31f))
            val m2 = abs(sin(t * 2.2f + k * 0.47f + 1.7f) * cos(t * 1.1f + k * 0.29f))
            val amp = (0.12f + 1.1f * lv + 0.5f * fl).coerceAtMost(1.3f)
            val l1 = 6.dp.toPx() + amp * m * maxLen
            val l2 = 6.dp.toPx() + amp * m2 * maxLen
            drawRoundRect(c1.copy(alpha = 0.30f + 0.55f * m), Offset(6.dp.toPx(), y), Size(l1, slot * 0.5f), CornerRadius(4f))
            drawRoundRect(c2.copy(alpha = 0.30f + 0.55f * m2), Offset(w - 6.dp.toPx() - l2, y), Size(l2, slot * 0.5f), CornerRadius(4f))
        }
    }
}

// ───────────────────────── full-screen wake overlay ─────────────────────────
@Composable
fun OrbOverlay() {
    val state by Jarvis.state.collectAsState()
    val partial by Jarvis.partial.collectAsState()
    val messages by Jarvis.messages.collectAsState()
    val pulse by Jarvis.wakePulse.collectAsState()
    val level = Jarvis.level.collectAsState()
    val time = rememberClock()

    val label = when (state) {
        OrbState.LISTENING -> "Listening"
        OrbState.THINKING -> "Thinking"
        OrbState.SPEAKING -> "Speaking"
        else -> "Ready"
    }
    val lastUser = messages.lastOrNull { it.user }?.text ?: ""
    val lastReply = messages.lastOrNull { !it.user }?.text ?: ""
    val line = when (state) {
        OrbState.LISTENING -> partial.ifEmpty { "Go ahead, I'm listening" }
        OrbState.THINKING -> lastUser
        OrbState.SPEAKING -> lastReply
        else -> ""
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        SideFx(state, level, time, pulse)

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .align(Alignment.Center)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Jarvis.manualListen() }
        ) {
            JarvisOrb(state, level, time, Modifier.fillMaxSize())
        }

        Text(
            label,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 1.5.sp,
            modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(top = 28.dp)
        )

        IconButton(
            onClick = { Jarvis.dismiss() },
            modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.8f))
        }

        Text(
            line,
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            maxLines = 5,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(start = 64.dp, end = 64.dp, bottom = 56.dp)
                .alpha(0.95f)
        )
    }
}
