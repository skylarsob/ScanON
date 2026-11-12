@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.skyler.scanon.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.skyler.scanon.R
import com.skyler.scanon.engine.ExportFormat
import com.skyler.scanon.engine.GeoDb
import com.skyler.scanon.ui.theme.Amber
import com.skyler.scanon.ui.theme.Bad
import com.skyler.scanon.ui.theme.Good
import com.skyler.scanon.ui.theme.Neutral
import com.skyler.scanon.ui.theme.Warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shows a snackbar message from anywhere in the tree. */
val LocalSnack = staticCompositionLocalOf<(String) -> Unit> { {} }
val LocalHapticsOn = staticCompositionLocalOf { true }

class Haptics(private val view: View, private val enabled: Boolean) {
    fun tick() { if (enabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    fun success() {
        if (!enabled) return
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val on = LocalHapticsOn.current
    return remember(view, on) { Haptics(view, on) }
}

fun copyToClipboard(context: Context, text: String, label: String = "ScanON") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * Shares text. Large payloads go out as a file attachment through a FileProvider: putting >~500 KB in
 * EXTRA_TEXT crashes with TransactionTooLargeException.
 */
fun shareText(context: Context, text: String, format: ExportFormat = ExportFormat.TXT, baseName: String = "scanon") {
    val intent = Intent(Intent.ACTION_SEND)
    if (text.length > 100_000 || format != ExportFormat.TXT) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        val f = File(dir, "$baseName.${format.ext}")
        f.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        intent.type = format.mime
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** SAF "create document" with the MIME type chosen at launch time (the stock contract fixes it at construction). */
private class CreateDocAny : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

/** Text waiting for the save picker. Kept out of the saved-instance Bundle (large text would overflow it). */
private object PendingSave { var text: String = "" }

/**
 * Returns a function (fileName, format, text) that asks where to save and writes the file off the main thread.
 * 2.3 opened the destination with mode "w" (old content could survive at the end of the file) and crashed on errors.
 */
@Composable
fun rememberSaveFile(): (String, ExportFormat, String) -> Unit {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val okMsg = stringResource(R.string.saved_file)
    val launcher = rememberLauncherForActivityResult(CreateDocAny()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = PendingSave.text
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: return@withContext "cannot open destination"
                    null
                } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            }
            snack(err ?: okMsg)
            PendingSave.text = ""
        }
    }
    return remember(launcher) { { name, fmt, text -> PendingSave.text = text; launcher.launch("$name.${fmt.ext}" to fmt.mime) } }
}

/**
 * Tactile glass panel. 2.5 was a flat Card with a faint plain border, which
 * read as a plain box. 2.6: a vertical glass gradient (lighter at the top),
 * a specular gradient border (bright top-left, tinted with the accent at the
 * bottom), a 1px highlight line under the top edge, and a soft accent-tinted
 * shadow so panels lift off the animated background. Alpha still follows the
 * glass setting; radius and spacing follow the appearance settings.
 */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val glass = com.skyler.scanon.ui.theme.LocalGlass.current
    val radius = com.skyler.scanon.ui.theme.LocalRadiusScale.current
    val spacing = com.skyler.scanon.ui.theme.LocalSpacingScale.current
    val dark = com.skyler.scanon.ui.theme.LocalIsDark.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape((16f * radius).dp)
    val alpha = 0.97f - glass * 0.45f
    Box(
        modifier.fillMaxWidth()
            .shadow(10.dp, shape,
                ambientColor = Color.Black.copy(alpha = if (dark) 0.35f else 0.12f),
                spotColor = scheme.primary.copy(alpha = if (dark) 0.22f else 0.10f))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(
                scheme.surface.copy(alpha = alpha),
                scheme.surfaceVariant.copy(alpha = alpha * 0.80f))))
            .border(1.dp, Brush.linearGradient(listOf(
                scheme.onSurface.copy(alpha = 0.24f),
                scheme.onSurface.copy(alpha = 0.05f),
                scheme.primary.copy(alpha = 0.14f))), shape)
            .animateContentSize(),
    ) {
        // Cards set their own content colour too (2.8.5), so bare Text inside a
        // card is readable even in a subtree the theme provider doesn't reach.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides scheme.onSurface,
        ) {
            Column(Modifier.padding((14f * spacing).dp),
                verticalArrangement = Arrangement.spacedBy((8f * spacing).dp)) { content() }
        }
        // Specular highlight just under the top edge.
        Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.TopCenter)
            .background(Brush.horizontalGradient(listOf(
                Color.Transparent,
                Color.White.copy(alpha = if (dark) 0.20f else 0.55f),
                Color.Transparent))))
    }
}

/**
 * Entrance cascade (2.6): the composable fades in and rises with a spring,
 * delayed by [index] so a screen's cards arrive in a stagger instead of
 * popping in at once. Static when motion is reduced.
 */
@Composable
fun Modifier.entrance(index: Int = 0): Modifier {
    val reduce = com.skyler.scanon.ui.theme.LocalReduceMotion.current
    if (reduce) return this
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 55L)
        anim.animateTo(1f, spring(stiffness = 300f, dampingRatio = 0.85f))
    }
    return graphicsLayer {
        alpha = anim.value
        translationY = (1f - anim.value) * 52f
    }
}

/** Tactile press feedback (2.6): the composable dips to [pressedScale] with a spring while pressed. */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.975f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduce = com.skyler.scanon.ui.theme.LocalReduceMotion.current
    val scale by animateFloatAsState(
        if (pressed && !reduce) pressedScale else 1f,
        spring(stiffness = 520f, dampingRatio = 0.55f), label = "pressScale")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Stat box whose number counts up (or down) to the new value instead of snapping (2.6). */
@Composable
fun AnimatedStatBox(label: String, value: Int, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val reduce = com.skyler.scanon.ui.theme.LocalReduceMotion.current
    val animated by animateIntAsState(value, tween(550), label = "statCount")
    StatBox(label, (if (reduce) value else animated).toString(), modifier, color)
}

/**
 * Arrival flash (2.7): a fresh result card glows with the accent colour and
 * settles over ~700 ms, so results streaming in during a scan visibly land
 * instead of just appearing. Still under reduced motion.
 */
@Composable
fun Modifier.flashOnAppear(): Modifier {
    val reduce = com.skyler.scanon.ui.theme.LocalReduceMotion.current
    if (reduce) return this
    val radius = com.skyler.scanon.ui.theme.LocalRadiusScale.current
    val color = MaterialTheme.colorScheme.primary
    val anim = remember { Animatable(1f) }
    LaunchedEffect(Unit) { anim.animateTo(0f, tween(750)) }
    return drawWithContent {
        drawContent()
        if (anim.value > 0.01f) {
            val r = 16.dp.toPx() * radius
            drawRoundRect(color.copy(alpha = 0.28f * anim.value), cornerRadius = CornerRadius(r, r))
        }
    }
}

/**
 * Inline sparkline of real per-attempt totals (2.5). Draws the measured runs
 * in order - the same numbers the card prints as "total runs" - normalised to
 * their own min/max, with a soft fill under the line. 2.7: the line draws
 * itself in left to right when the card appears.
 */
@Composable
fun Sparkline(samples: List<Double>, color: Color, modifier: Modifier = Modifier) {
    if (samples.size < 2) return
    val reduce = com.skyler.scanon.ui.theme.LocalReduceMotion.current
    val drawProgress = if (reduce) 1f else {
        val a = remember { Animatable(0f) }
        LaunchedEffect(samples) { a.animateTo(1f, tween(650)) }
        a.value
    }
    Canvas(modifier) {
        val min = samples.min()
        val max = samples.max()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val stepX = size.width / (samples.size - 1)
        val pts = samples.mapIndexed { i, v ->
            Offset(i * stepX, size.height - ((v - min) / range).toFloat() * (size.height * 0.82f) - size.height * 0.09f)
        }
        val line = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val fill = Path().apply {
            moveTo(0f, size.height)
            pts.forEach { lineTo(it.x, it.y) }
            lineTo(size.width, size.height)
            close()
        }
        clipRect(right = size.width * drawProgress) {
            drawPath(fill, color.copy(alpha = 0.16f))
            drawPath(line, color, style = Stroke(width = 2f, cap = StrokeCap.Round))
        }
        if (drawProgress > 0.97f) drawCircle(color, 3f, pts.last())
    }
}

/** Pulsing status dot (2.5): emerald/amber/crimson by reliability, still when motion is reduced. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    val reduce = com.skyler.scanon.ui.theme.LocalReduceMotion.current
    var scale = 1f
    var halo = 0f
    if (!reduce) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition("dot")
        val a by t.animateFloat(initialValue = 0f, targetValue = 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(1600),
                androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
        scale = 0.82f + a * 0.18f
        halo = a * 0.35f
    }
    Box(modifier.size(12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = halo)))
        Box(Modifier.size((8f * scale).dp).clip(RoundedCornerShape(4.dp)).background(color))
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
}

@Composable
fun StatBox(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** IPs, ports, hostnames and numbers are always laid out left-to-right, even in the Persian (RTL) UI. */
val LtrStyle = TextStyle(textDirection = TextDirection.Ltr)
val MonoLtr = TextStyle(textDirection = TextDirection.Ltr, fontFamily = FontFamily.Monospace)

@Composable
fun LtrText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color = Color.Unspecified, fontWeight: FontWeight? = null) {
    Text(text, modifier, style = style.merge(LtrStyle), color = color, fontWeight = fontWeight)
}

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    error: String? = null, number: Boolean = false, singleLine: Boolean = true, minLines: Int = 1, maxLines: Int = 1,
    ltr: Boolean = true, placeholder: String? = null,
) {
    // Label ABOVE the box (3.0, Skylar's request): the floating label sat inside
    // the outline and collided with the entered text ("the text is in the middle
    // of the field"). Every field in the app now reads label, then box — which
    // also lines rows of fields up by their boxes.
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = singleLine, minLines = minLines, maxLines = if (singleLine) 1 else maxLines,
            textStyle = if (ltr) MaterialTheme.typography.bodyMedium.merge(LtrStyle) else MaterialTheme.typography.bodyMedium,
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
            // Solid fill (2.9, strengthened 2.9.1): surfaceVariant tinted 12%
            // toward the accent so the box visibly lifts off the glass card,
            // 14 dp corners shared with the buttons, primary border on focus.
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, 0.12f),
                unfocusedContainerColor = lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, 0.12f),
                disabledContainerColor = lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, 0.12f).copy(alpha = 0.5f),
                errorContainerColor = lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, 0.12f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
fun DropDown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit, display: @Composable (String) -> String = { it }) {
    var open by remember { mutableStateOf(false) }
    Box {
        ScanButton(onClick = { open = true }) {
            Text("$label: ${display(selected)}", maxLines = 1)
            Spacer(Modifier.width(4.dp))
            AppIcon(AppIconKind.ChevronDown, MaterialTheme.colorScheme.primary, size = 16.dp)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(display(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

/**
 * The app's two button styles (2.9.1 visual pass, from Hossein's review:
 * "make the buttons and fields prettier, no glass"). Secondary actions get a
 * SOLID primaryContainer fill — clearly visible against the glass cards,
 * unlike the transparent outlined buttons they replace — and primary
 * actions get the full primary fill with generous padding. Both share a
 * 12 dp corner radius with the input fields so the whole UI reads as one
 * system. TextButton remains only for true inline text actions.
 */
@Composable
fun ScanButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick, modifier = modifier, enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
fun PrimaryButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled, interactionSource = interactionSource,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
fun IconTextButton(text: String, icon: AppIconKind, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    ScanButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        AppIcon(icon, if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun formatEta(seconds: Long?): String = when {
    seconds == null -> "-"
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
}

/**
 * Gradient progress bar (2.6) replacing the stock Material indicator: the
 * fill animates smoothly to the real fraction, a light band shimmers across
 * the filled part, and with no known total a band sweeps the track instead.
 */
@Composable
fun AnimatedProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val reduce = com.skyler.scanon.ui.theme.LocalReduceMotion.current
    val animFrac by animateFloatAsState(fraction?.coerceIn(0f, 1f) ?: 0f, tween(380), label = "progressFrac")
    val sweep = if (reduce) 0.35f else {
        val t = rememberInfiniteTransition("shimmer")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1250, easing = LinearEasing), RepeatMode.Restart), label = "shimmerV")
        v
    }
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val r = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(scheme.surfaceVariant.copy(alpha = 0.85f), cornerRadius = r)
        if (fraction == null) {
            val bw = size.width * 0.35f
            val x = sweep * (size.width + bw) - bw
            clipRect(x, 0f, x + bw, size.height) {
                drawRoundRect(Brush.horizontalGradient(listOf(scheme.primary, scheme.secondary)), cornerRadius = r)
            }
            return@Canvas
        }
        val w = size.width * animFrac
        if (w > 0f) {
            drawRoundRect(Brush.horizontalGradient(listOf(scheme.primary, scheme.secondary)),
                size = Size(w, size.height), cornerRadius = r)
            clipRect(0f, 0f, w, size.height) {
                val bw = 56f
                val x = sweep * (w + bw) - bw
                drawRect(Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.30f), Color.Transparent),
                    start = Offset(x, 0f), end = Offset(x + bw, 0f)))
            }
        }
    }
}

/** Progress bar with done/total, percentage, rate and ETA (2.3 showed only "Scanning… n/m"). */
@Composable
fun ProgressCard(phase: String, done: Long, total: Long, startedAt: Long, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val secs = (now - startedAt) / 1000.0
    val rate = if (secs > 0.5) done / secs else 0.0
    val eta = if (rate > 0 && total > done) ((total - done) / rate).toLong() else null
    val pct by animateIntAsState(if (total > 0) (done * 100 / total).toInt() else 0, tween(300), label = "pct")
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpinningRadar(MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(phase, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
        AnimatedProgressBar(if (total > 0) done.toFloat() / total else null)
        if (total > 0) LtrText(
            "$done / $total · $pct% · ${String.format(java.util.Locale.US, "%.1f", rate)}/s · ETA ${formatEta(eta)}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyState(icon: AppIconKind, text: String) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppIcon(icon, MaterialTheme.colorScheme.onSurfaceVariant, size = 40.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Copy / share / save the working or full list, as TXT, CSV or JSON. */
@Composable
fun ExportBar(
    fileBase: String,
    plainList: (workingOnly: Boolean) -> String,
    render: (ExportFormat, workingOnly: Boolean) -> String,
    formats: List<ExportFormat> = ExportFormat.entries,
) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val haptics = rememberHaptics()
    val save = rememberSaveFile()
    val copied = stringResource(R.string.copied)
    var format by remember { mutableStateOf(ExportFormat.TXT) }
    var workingOnly by remember { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.material3.FilterChip(workingOnly, { workingOnly = true }, label = { Text(stringResource(R.string.working_only)) })
            androidx.compose.material3.FilterChip(!workingOnly, { workingOnly = false }, label = { Text(stringResource(R.string.all_results)) })
            formats.forEach { f ->
                androidx.compose.material3.FilterChip(format == f, { format = f }, label = { Text(f.ext.uppercase()) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconTextButton(stringResource(R.string.copy_ips), AppIconKind.Copy, {
                copyToClipboard(context, plainList(workingOnly)); haptics.tick(); snack(copied)
            })
            IconTextButton(stringResource(R.string.copy_report), AppIconKind.Copy, {
                copyToClipboard(context, render(format, workingOnly)); haptics.tick(); snack(copied)
            })
            IconTextButton(stringResource(R.string.share), AppIconKind.Share, {
                try { shareText(context, render(format, workingOnly), format, fileBase) } catch (e: Exception) { snack(e.message ?: "share failed") }
            })
            IconTextButton(stringResource(R.string.save_file), AppIconKind.Save, { save(fileBase, format, render(format, workingOnly)) })
        }
    }
}

fun reliabilityColor(r: String): Color = when (r) {
    "success" -> Good
    "failed" -> Bad
    "partial" -> Warn
    "unstable" -> Amber
    else -> Neutral
}

fun latencyColor(ms: Double?): Color = when {
    ms == null -> Color(0xFF757575)
    ms < 120 -> Color(0xFF66BB6A)
    ms < 300 -> Color(0xFFFFCA28)
    else -> Color(0xFFEF5350)
}

/** Offline GeoIP line: country chip, country/city, AS number + organisation. */
@Composable
fun GeoLine(ip: String) {
    val state by GeoDb.state.collectAsState()
    val geo = remember(ip, state) { GeoDb.lookup(ip) } ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        geo.countryCode?.let {
            Text("${geo.flag} $it", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 6.dp, vertical = 1.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(listOfNotNull(geo.countryName, geo.city, geo.asLabel).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun isRtl() = LocalLayoutDirection.current == LayoutDirection.Rtl

@Composable
fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
