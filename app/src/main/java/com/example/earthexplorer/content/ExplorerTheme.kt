package com.example.earthexplorer.content

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.earthexplorer.data.FeatureLayer
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Command-deck palette shared by every 2D window and the native 3D labels.
 * The interface itself is cyan; each live data type keeps one fixed accent so a
 * marker on the globe, its list entry and its detail card always match.
 */
object ExplorerPalette {
    val void = Color(0xF2070D18)
    val voidTop = Color(0xF00C1628)
    val ink = Color(0xFF07101C)
    val surface = Color(0xCC0F1A2B)
    val surfaceRaised = Color(0xFF16253B)
    val cyan = Color(0xFF4FE3FF)
    val line = Color(0x664FE3FF)
    val lineSoft = Color(0x264FE3FF)
    val textPrimary = Color(0xFFEAF6FF)
    val textSecondary = Color(0xFF93B3C9)
    val textTertiary = Color(0xFF5F7E96)
    val gold = Color(0xFFFFC857)
    val aircraft = Color(0xFF5CC8FF)
    val quake = Color(0xFFFF7A45)
    val satellite = Color(0xFFB08CFF)
    val webcam = Color(0xFF3EE8C5)
    val sun = Color(0xFFFFD166)
    val danger = Color(0xFFFF5C6C)

    fun layer(layer: FeatureLayer): Color = when (layer) {
        FeatureLayer.AIRCRAFT -> aircraft
        FeatureLayer.EARTHQUAKES -> quake
        FeatureLayer.POSITIONS -> satellite
        FeatureLayer.SUN -> sun
        FeatureLayer.WEBCAMS -> webcam
    }
}

private val UTC_CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)

fun mono(base: TextStyle): TextStyle = base.copy(fontFamily = FontFamily.Monospace)

/** Window root. The system glass is switched off in Main.kt so this surface stays dark in any room. */
@Composable
fun ExplorerWindow(modifier: Modifier = Modifier, bootDelayMillis: Long = 0L, content: @Composable ColumnScope.() -> Unit) {
    // design-style: opaque-root — the containers are created with enableMaterialBackground = false.
    val shape = RoundedCornerShape(30.dp)
    // Boot sequence: each window powers on with a short rise and fade, staggered per window.
    var powered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(bootDelayMillis); powered = true }
    val power by animateFloatAsState(if (powered) 1f else 0f, tween(650, easing = FastOutSlowInEasing))
    Column(
        modifier.fillMaxSize()
            .graphicsLayer { alpha = power; translationY = (1f - power) * 36.dp.toPx() }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(ExplorerPalette.voidTop, ExplorerPalette.void)))
            .border(1.dp, ExplorerPalette.line, shape),
        content = content,
    )
}

@Composable
fun Eyebrow(text: String, color: Color = ExplorerPalette.cyan, modifier: Modifier = Modifier) {
    Text(text.uppercase(Locale.ENGLISH), modifier, color = color,
        style = PicoTheme.typography.labelSmall.copy(letterSpacing = 2.2.sp, fontWeight = FontWeight.SemiBold))
}

@Composable
fun HudDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(
        listOf(Color.Transparent, ExplorerPalette.line, Color.Transparent))))
}

@Composable
fun HudHeader(
    eyebrow: String,
    title: String,
    accent: Color = ExplorerPalette.cyan,
    trailing: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = if (compact) 12.dp else 22.dp, bottom = if (compact) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow(eyebrow, accent)
            Text(title, style = PicoTheme.typography.headlineMedium, color = ExplorerPalette.textPrimary)
        }
        trailing?.invoke()
    }
    HudDivider()
}

@Composable
fun UtcClock(modifier: Modifier = Modifier) {
    val now = liveClockMillis()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiveDot(ExplorerPalette.cyan)
        Text(UTC_CLOCK_FORMAT.format(Instant.ofEpochMilli(now)), style = mono(PicoTheme.typography.bodySmall),
            color = ExplorerPalette.textSecondary)
    }
}

@Composable
fun LiveDot(color: Color, size: Dp = 8.dp) {
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart),
    )
    Box(Modifier.size(size * 2.6f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * (1f + 1.6f * pulse)).background(color.copy(alpha = 0.35f * (1f - pulse)), CircleShape))
        Box(Modifier.size(size).background(color, CircleShape))
    }
}

@Composable
fun GlowCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).background(ExplorerPalette.surface)
        .border(1.dp, ExplorerPalette.lineSoft, shape)) {
        if (accent != null) Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.weight(1f).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier,
    accent: Color = ExplorerPalette.cyan, monospace: Boolean = true) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(ExplorerPalette.surfaceRaised.copy(alpha = 0.72f))
        .border(1.dp, ExplorerPalette.lineSoft, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow(label, accent)
        Text(value, style = if (monospace) mono(PicoTheme.typography.titleMedium) else PicoTheme.typography.titleMedium,
            color = ExplorerPalette.textPrimary)
    }
}

@Composable
fun HudButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = ExplorerPalette.cyan,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    Button(
        onClick = onClick,
        modifier = modifier.border(1.dp, accent.copy(alpha = if (filled) 0.9f else 0.65f), shape),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) accent else accent.copy(alpha = 0.10f),
            contentColor = if (filled) ExplorerPalette.ink else accent,
        ),
    ) {
        Text(text, style = PicoTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (filled) ExplorerPalette.ink else accent)
    }
}

@Composable
fun PillTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, accent: Color = ExplorerPalette.cyan) {
    val shape = RoundedCornerShape(50)
    Row(modifier.fillMaxWidth().clip(shape).background(ExplorerPalette.surface)
        .border(1.dp, ExplorerPalette.lineSoft, shape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            val color = if (active) accent else ExplorerPalette.textSecondary
            Button(
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) accent.copy(alpha = 0.22f) else Color.Transparent,
                    contentColor = color,
                ),
            ) {
                Text(label, color = color, style = PicoTheme.typography.labelLarge)
            }
        }
    }
}

/** Presentation-only switch: the whole row is the control, so no nested tap targets. */
@Composable
fun TogglePill(on: Boolean, accent: Color) {
    val knob by animateFloatAsState(if (on) 1f else 0f, tween(220))
    val shape = RoundedCornerShape(50)
    Box(Modifier.width(48.dp).height(26.dp).clip(shape)
        .background(if (on) accent.copy(alpha = 0.35f) else ExplorerPalette.surfaceRaised)
        .border(1.dp, if (on) accent else ExplorerPalette.textTertiary, shape).padding(3.dp)) {
        Box(Modifier.fillMaxHeight().aspectRatio(1f).offset(x = 22.dp * knob)
            .background(if (on) accent else ExplorerPalette.textSecondary, CircleShape))
    }
}

@Composable
fun HudProgress(progress: Float, modifier: Modifier = Modifier, accent: Color = ExplorerPalette.cyan) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400))
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(ExplorerPalette.surfaceRaised)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(animated.coerceAtLeast(0.03f))
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.45f), accent)), RoundedCornerShape(50)))
    }
}

@Composable
fun StatusLine(text: String, accent: Color = ExplorerPalette.cyan, live: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (live) LiveDot(accent, 6.dp)
        Text(text, style = PicoTheme.typography.bodySmall, color = ExplorerPalette.textSecondary)
    }
}

@Composable
fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = PicoTheme.typography.bodySmall, color = ExplorerPalette.textSecondary)
        Text(value, style = if (monospace) mono(PicoTheme.typography.bodyMedium) else PicoTheme.typography.bodyMedium,
            color = ExplorerPalette.textPrimary)
    }
}

@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = PicoTheme.typography.bodySmall, color = ExplorerPalette.textTertiary)
}

@Composable
fun FeatureCard(title: String, subtitle: String, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().border(1.dp, ExplorerPalette.lineSoft, shape),
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = ExplorerPalette.surface, contentColor = ExplorerPalette.textPrimary),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(10.dp).background(accent, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = PicoTheme.typography.titleMedium, color = ExplorerPalette.textPrimary)
                if (subtitle.isNotBlank()) Text(subtitle, style = PicoTheme.typography.bodySmall, color = ExplorerPalette.textSecondary)
            }
            Text("›", style = PicoTheme.typography.titleLarge, color = accent)
        }
    }
}
