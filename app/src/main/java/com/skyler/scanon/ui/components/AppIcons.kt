package com.skyler.scanon.ui.components

import androidx.compose.material3.MaterialTheme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.skyler.scanon.ui.theme.LocalIsDark
import com.skyler.scanon.ui.theme.LocalReduceMotion
import kotlin.math.cos
import kotlin.math.sin

/** Hand-drawn line icons (no 2 MB icon library). The original 16 kinds plus the ones the new features need. */
enum class AppIconKind {
    Globe, Dns, Target, Activity, Star, Folder, Info, Clock, Sliders, Back, Close, Check, Warn, ChevronDown, ChevronUp, Radar,
    Copy, Share, Save, Search, Sort, Play, Stop, Refresh, Trash, Upload, Speed, Edit, Plus, Filter,
}

/** Icons that point in a reading direction and must be mirrored in RTL (Persian). */
private val MIRRORED = setOf(AppIconKind.Back, AppIconKind.Share, AppIconKind.Play)

@Composable
fun AppIcon(kind: AppIconKind, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp, filled: Boolean = false) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.size(size)) {
        if (rtl && kind in MIRRORED) scale(-1f, 1f) { drawIcon(kind, tint, filled) } else drawIcon(kind, tint, filled)
    }
}

private fun DrawScope.drawIcon(kind: AppIconKind, tint: Color, filled: Boolean) {
    val w = size.width
    val h = size.height
    val sw = w * 0.09f
    val stroke = Stroke(sw, cap = StrokeCap.Round)
    fun p(x: Float, y: Float) = Offset(x * w, y * h)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), sw, StrokeCap.Round)
    fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) =
        drawCircle(tint, r * w, p(cx, cy), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
    fun poly(vararg pts: Float, close: Boolean = false, fill: Boolean = false) {
        val path = Path()
        path.moveTo(pts[0] * w, pts[1] * h)
        var i = 2
        while (i < pts.size) { path.lineTo(pts[i] * w, pts[i + 1] * h); i += 2 }
        if (close) path.close()
        drawPath(path, tint, style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
    }
    fun rrect(x: Float, y: Float, rw: Float, rh: Float) =
        drawRoundRect(tint, p(x, y), Size(rw * w, rh * h), CornerRadius(w * 0.08f), style = stroke)

    when (kind) {
        AppIconKind.Globe -> {
            circle(0.5f, 0.5f, 0.4f)
            drawOval(tint, p(0.32f, 0.1f), Size(0.36f * w, 0.8f * h), style = stroke)
            line(0.1f, 0.5f, 0.9f, 0.5f)
        }
        AppIconKind.Dns -> { rrect(0.12f, 0.1f, 0.76f, 0.34f); rrect(0.12f, 0.56f, 0.76f, 0.34f); circle(0.26f, 0.27f, 0.04f, true); circle(0.26f, 0.73f, 0.04f, true) }
        AppIconKind.Target -> { circle(0.5f, 0.5f, 0.4f); circle(0.5f, 0.5f, 0.22f); circle(0.5f, 0.5f, 0.06f, true) }
        AppIconKind.Activity -> poly(0.06f, 0.55f, 0.32f, 0.55f, 0.43f, 0.28f, 0.58f, 0.76f, 0.67f, 0.55f, 0.94f, 0.55f)
        AppIconKind.Star -> {
            val pts = ArrayList<Float>()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 0.44f else 0.19f
                val a = Math.toRadians((-90 + i * 36).toDouble())
                pts += 0.5f + (r * cos(a)).toFloat(); pts += 0.53f + (r * sin(a)).toFloat()
            }
            poly(*pts.toFloatArray(), close = true, fill = filled)
            if (filled) poly(*pts.toFloatArray(), close = true)
        }
        AppIconKind.Folder -> poly(0.08f, 0.26f, 0.38f, 0.26f, 0.46f, 0.36f, 0.92f, 0.36f, 0.92f, 0.8f, 0.08f, 0.8f, close = true)
        AppIconKind.Info -> { circle(0.5f, 0.5f, 0.4f); line(0.5f, 0.46f, 0.5f, 0.72f); circle(0.5f, 0.31f, 0.045f, true) }
        AppIconKind.Clock -> { circle(0.5f, 0.5f, 0.4f); line(0.5f, 0.5f, 0.5f, 0.27f); line(0.5f, 0.5f, 0.67f, 0.6f) }
        AppIconKind.Sliders -> { line(0.15f, 0.3f, 0.85f, 0.3f); line(0.15f, 0.7f, 0.85f, 0.7f); circle(0.35f, 0.3f, 0.09f, true); circle(0.65f, 0.7f, 0.09f, true) }
        AppIconKind.Back -> { line(0.8f, 0.5f, 0.2f, 0.5f); poly(0.45f, 0.24f, 0.2f, 0.5f, 0.45f, 0.76f) }
        AppIconKind.Close -> { line(0.25f, 0.25f, 0.75f, 0.75f); line(0.75f, 0.25f, 0.25f, 0.75f) }
        AppIconKind.Check -> poly(0.18f, 0.52f, 0.4f, 0.74f, 0.82f, 0.28f)
        AppIconKind.Warn -> { poly(0.5f, 0.12f, 0.92f, 0.84f, 0.08f, 0.84f, close = true); line(0.5f, 0.4f, 0.5f, 0.6f); circle(0.5f, 0.72f, 0.035f, true) }
        AppIconKind.ChevronDown -> poly(0.22f, 0.38f, 0.5f, 0.66f, 0.78f, 0.38f)
        AppIconKind.ChevronUp -> poly(0.22f, 0.62f, 0.5f, 0.34f, 0.78f, 0.62f)
        AppIconKind.Radar -> { circle(0.5f, 0.5f, 0.42f); circle(0.5f, 0.5f, 0.25f); line(0.5f, 0.5f, 0.78f, 0.22f); circle(0.5f, 0.5f, 0.06f, true) }
        AppIconKind.Copy -> { rrect(0.3f, 0.3f, 0.56f, 0.6f); poly(0.2f, 0.7f, 0.14f, 0.7f, 0.14f, 0.1f, 0.64f, 0.1f, 0.64f, 0.18f) }
        AppIconKind.Share -> { circle(0.25f, 0.5f, 0.1f); circle(0.75f, 0.22f, 0.1f); circle(0.75f, 0.78f, 0.1f); line(0.34f, 0.45f, 0.66f, 0.27f); line(0.34f, 0.55f, 0.66f, 0.73f) }
        AppIconKind.Save -> { line(0.5f, 0.12f, 0.5f, 0.64f); poly(0.28f, 0.44f, 0.5f, 0.66f, 0.72f, 0.44f); line(0.15f, 0.86f, 0.85f, 0.86f) }
        AppIconKind.Upload -> { line(0.5f, 0.66f, 0.5f, 0.14f); poly(0.28f, 0.36f, 0.5f, 0.14f, 0.72f, 0.36f); line(0.15f, 0.86f, 0.85f, 0.86f) }
        AppIconKind.Search -> { circle(0.43f, 0.43f, 0.27f); line(0.63f, 0.63f, 0.88f, 0.88f) }
        AppIconKind.Sort -> { line(0.15f, 0.25f, 0.85f, 0.25f); line(0.15f, 0.5f, 0.65f, 0.5f); line(0.15f, 0.75f, 0.45f, 0.75f) }
        AppIconKind.Filter -> poly(0.12f, 0.18f, 0.88f, 0.18f, 0.58f, 0.52f, 0.58f, 0.84f, 0.42f, 0.76f, 0.42f, 0.52f, close = true)
        AppIconKind.Play -> poly(0.3f, 0.18f, 0.82f, 0.5f, 0.3f, 0.82f, close = true, fill = true)
        AppIconKind.Stop -> drawRoundRect(tint, p(0.24f, 0.24f), Size(0.52f * w, 0.52f * h), CornerRadius(w * 0.06f))
        AppIconKind.Refresh -> {
            drawArc(tint, 30f, 280f, false, p(0.15f, 0.15f), Size(0.7f * w, 0.7f * h), style = stroke)
            poly(0.86f, 0.22f, 0.83f, 0.42f, 0.64f, 0.36f)
        }
        AppIconKind.Trash -> { line(0.16f, 0.24f, 0.84f, 0.24f); poly(0.24f, 0.24f, 0.3f, 0.88f, 0.7f, 0.88f, 0.76f, 0.24f); line(0.4f, 0.24f, 0.42f, 0.12f); line(0.6f, 0.24f, 0.58f, 0.12f); line(0.42f, 0.12f, 0.58f, 0.12f) }
        AppIconKind.Speed -> {
            drawArc(tint, 160f, 220f, false, p(0.1f, 0.18f), Size(0.8f * w, 0.8f * h), style = stroke)
            line(0.5f, 0.58f, 0.72f, 0.36f); circle(0.5f, 0.58f, 0.05f, true)
        }
        AppIconKind.Edit -> { poly(0.18f, 0.82f, 0.22f, 0.64f, 0.66f, 0.2f, 0.8f, 0.34f, 0.36f, 0.78f, close = true) }
        AppIconKind.Plus -> { line(0.5f, 0.18f, 0.5f, 0.82f); line(0.18f, 0.5f, 0.82f, 0.5f) }
    }
}

/** A small spinning radar used as an inline "working" indicator. */
@Composable
fun SpinningRadar(tint: Color, size: Dp = 18.dp, durationMillis: Int = 1400) {
    if (LocalReduceMotion.current) { AppIcon(AppIconKind.Radar, tint, size = size); return }
    val t = rememberInfiniteTransition("spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(durationMillis, easing = LinearEasing)), label = "a")
    AppIcon(AppIconKind.Radar, tint, Modifier.rotate(a), size)
}

/**
 * Animated gradient background (blobs, sonar rings, drifting particles). With "reduce motion" on - or the
 * system animator scale at 0 - a static frame is drawn instead of an endless 60 fps animation.
 * 2.5: colours come from the active colour scheme (preset + accent), and the
 * glow strength follows the glass setting.
 */
@Composable
fun AnimatedBackground(boost: Boolean = false) {
    val dark = LocalIsDark.current
    val reduce = LocalReduceMotion.current
    val glass = com.skyler.scanon.ui.theme.LocalGlass.current
    val scheme = MaterialTheme.colorScheme
    // While a scan runs, the background comes alive (2.7): faster pulse
    // rings and a brighter wash fade in; they fade back out when it ends.
    val boostAlpha by androidx.compose.animation.core.animateFloatAsState(
        if (boost) 1f else 0f, tween(650), label = "bgBoost")
    val fastPhase = if (reduce) 0f else {
        val t2 = rememberInfiniteTransition("bgfast")
        val v2 by t2.animateFloat(0f, 6.2831855f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "fastPhase")
        v2
    }
    // Reduce motion used to freeze the background on one static frame, which
    // reads as "no effects at all" - and MIUI battery saver / "remove
    // animations" sets the system animator scale to 0 on many phones, which
    // 2.4/2.5 treated the same as the in-app toggle. 2.6: reduced motion is a
    // very slow drift (one 2-minute loop), never a dead screen.
    val phase = run {
        val t = rememberInfiniteTransition("bg")
        val v by t.animateFloat(0f, 6.2831855f, infiniteRepeatable(
            tween(if (reduce) 120000 else 18000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
        v
    }
    val glowScale = 0.35f + glass * 0.85f
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val bg = scheme.background
        drawRect(Brush.verticalGradient(
            if (dark) listOf(
                androidx.compose.ui.graphics.lerp(bg, Color.Black, 0.25f), bg,
                androidx.compose.ui.graphics.lerp(bg, Color(0xFF0A1428), 0.35f))
            else listOf(
                androidx.compose.ui.graphics.lerp(bg, Color.White, 0.5f), bg,
                androidx.compose.ui.graphics.lerp(bg, scheme.primary, 0.06f))
        ))
        // Faint dot grid for depth (2.6).
        val gridCol = (if (dark) Color.White else scheme.primary).copy(alpha = 0.05f)
        val gap = w / 13f
        var gx = gap * 0.5f
        while (gx < w) {
            var gy = gap * 0.5f
            while (gy < h) { drawCircle(gridCol, 1.6f, Offset(gx, gy)); gy += gap }
            gx += gap
        }
        val blobs = listOf(
            Triple(0.2f, 0.18f, scheme.primary),
            Triple(0.85f, 0.4f, scheme.secondary),
            Triple(0.35f, 0.85f, androidx.compose.ui.graphics.lerp(scheme.primary, scheme.secondary, 0.5f)))
        blobs.forEachIndexed { i, (bx, by, col) ->
            val cx = (bx + cos(phase + i * 2.1f) * 0.08f) * w
            val cy = (by + sin(phase * 0.8f + i * 1.7f) * 0.08f) * h
            val r = w * (sin(phase + i) * 0.08f + 0.55f)
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = (if (dark) 0.19f else 0.13f) * glowScale), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
        }
        // Aurora wash drifting diagonally across the whole screen (2.6).
        run {
            val ax = (0.5f + cos(phase * 0.45f) * 0.38f) * w
            val ay = (0.42f + sin(phase * 0.45f) * 0.30f) * h
            val ar = w * 0.95f
            val aCol = androidx.compose.ui.graphics.lerp(scheme.primary, scheme.secondary,
                0.5f + 0.5f * sin(phase * 0.6f))
            drawCircle(Brush.radialGradient(
                listOf(aCol.copy(alpha = (if (dark) 0.10f else 0.07f) * glowScale), Color.Transparent),
                Offset(ax, ay), ar), ar, Offset(ax, ay))
        }
        // Radar sweep wedge rotating from the top-right (2.6).
        run {
            val c = Offset(w * 0.86f, h * 0.13f)
            val r = w * 0.52f
            val startDeg = Math.toDegrees((phase * 1.6f).toDouble()).toFloat()
            val wedge = Path().apply {
                moveTo(c.x, c.y)
                arcTo(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r),
                    startDeg, 42f, false)
                close()
            }
            drawPath(wedge, Brush.radialGradient(
                listOf(scheme.primary.copy(alpha = 0.13f * glowScale), Color.Transparent), c, r))
            drawCircle(scheme.primary.copy(alpha = 0.20f * glowScale), 3f, c)
        }
        val ring = scheme.primary
        for (i in 0 until 3) {
            val t = ((phase / 6.2831855f) + i / 3f) % 1f
            drawCircle(ring.copy(alpha = (1f - t) * 0.14f * glowScale), w * 0.15f + t * w * 0.7f, Offset(w * 0.5f, h * 0.32f), style = Stroke(w * 0.004f))
        }
        // Boost layer (2.7): fast pulse rings + a brighter wash while scanning.
        if (boostAlpha > 0.01f) {
            val bc = Offset(w * 0.5f, h * 0.32f)
            drawCircle(Brush.radialGradient(
                listOf(scheme.primary.copy(alpha = 0.10f * boostAlpha * glowScale), Color.Transparent), bc, w * 0.85f),
                w * 0.85f, bc)
            for (i in 0 until 3) {
                val tt = ((fastPhase / 6.2831855f) + i / 3f) % 1f
                drawCircle(scheme.primary.copy(alpha = (1f - tt) * 0.24f * boostAlpha),
                    w * 0.06f + tt * w * 0.88f, bc, style = Stroke(w * 0.006f))
            }
        }
        val dot = if (dark) Color.White else scheme.primary
        for (i in 0 until 26) {
            val seedX = ((i * 73) % 100) / 100f
            val seedY = ((i * 37) % 100) / 100f
            val speed = 0.02f + (i % 5) * 0.006f
            val y = (((seedY - phase * speed) % 1f) + 1f) % 1f
            val x = seedX + sin(phase + i * 1.3f) * 0.012f
            drawCircle(dot.copy(alpha = 0.10f + 0.08f * sin(phase * 2 + i).coerceAtLeast(0f)), w * 0.004f, Offset(x * w, y * h))
        }
    }
}
