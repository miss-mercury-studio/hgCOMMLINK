package studio.missmercury.hgcommlink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

val CardShape = androidx.compose.ui.graphics.RectangleShape
private val SquareShape = androidx.compose.ui.graphics.RectangleShape

/** A panel with a bracketed header, [ TITLE ]. Optionally folds away; [trailing] is a small readout on the right. */
@Composable
fun TerminalCard(
    title: String,
    modifier: Modifier = Modifier,
    tag: String? = null,
    accent: Color = PhosphorGreen,
    trailing: String? = null,
    collapsed: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = CardShape, color = MercurySlate.copy(alpha = 0.85f), border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildAnnotatedString {
                        append("[ ")
                        if (tag != null) { withStyle(SpanStyle(color = accent)) { append(tag) }; append(" // ") }
                        withStyle(SpanStyle(color = if (tag == null) accent else Quicksilver)) { append(title.uppercase().replace(' ', '_')) }
                        append(" ]")
                    },
                    style = MaterialTheme.typography.titleSmall.copy(shadow = Shadow(accent.copy(alpha = 0.55f), blurRadius = 14f)),
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelSmall, color = accent)
                if (collapsed != null) Text(if (collapsed) "  +" else "  –", style = MaterialTheme.typography.labelLarge, color = accent)
            }
            if (collapsed != true) content()
        }
    }
}

/** A labelled readout in a cell: the value large in mono, the label small above or below. */
@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier, accent: Color = Quicksilver) {
    Column(
        modifier.clip(SquareShape).background(SlateHigh).border(1.dp, TerminalGrid, SquareShape).padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono), color = accent, textAlign = TextAlign.Center)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** A thin strip of key:value telemetry, like the pinned bar in the mockups. */
@Composable
fun TelemetryStrip(items: List<Pair<String, String>>, modifier: Modifier = Modifier, accent: Color = PhosphorGreen) {
    Row(
        modifier.fillMaxWidth().clip(SquareShape).background(MercurySlate).border(1.dp, TerminalGrid, SquareShape).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, (label, value) ->
            Text(
                buildAnnotatedString {
                    if (label.isNotEmpty()) withStyle(SpanStyle(color = MutedSilver)) { append("${label.uppercase()}: ") }
                    withStyle(SpanStyle(color = accent)) { append(value) }
                },
                style = MaterialTheme.typography.labelSmall, maxLines = 1,
            )
            if (i < items.lastIndex) Text("|", style = MaterialTheme.typography.labelSmall, color = TerminalGrid)
        }
    }
}

/** A command button: [ LABEL ]. Filled for the main action, outlined otherwise. */
@Composable
fun TerminalButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    accent: Color = PhosphorGreen, filled: Boolean = false, enabled: Boolean = true,
) {
    Box(
        modifier.alpha(if (enabled) 1f else 0.4f)
            .then(if (filled && enabled) Modifier.shadow(10.dp, CardShape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(CardShape)
            .background(if (filled) accent else MercurySlate)
            .border(1.dp, if (filled) accent else TerminalGrid, CardShape)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("[ ${label.uppercase().replace(' ', '_')} ]", style = MaterialTheme.typography.labelLarge, color = if (filled) Obsidian else Quicksilver)
    }
}

/** A bracketed − / + control, [-] and [+]. */
@Composable
fun StepButton(label: String, size: Int = 40, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text("[$label]", style = MaterialTheme.typography.labelLarge.copy(shadow = Shadow(PhosphorGreen.copy(alpha = 0.5f), blurRadius = 10f)), color = PhosphorGreen)
    }
}

/** A small coloured tag, for marking magic, matrix, bio and so on. */
@Composable
fun Tag(text: String, accent: Color = AstralMagenta, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Obsidian,
        modifier = modifier.background(accent, RoundedCornerShape(1.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** A fold-away sub-section header: a tappable bar with a [+] / [–] box, so it reads as a control and not as a label. */
@Composable
fun CollapsibleHeader(title: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, summary: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).clip(SquareShape)
            .background(if (expanded) SlateHigh else MercurySlate).border(1.dp, if (expanded) PhosphorGreen.copy(alpha = 0.5f) else TerminalGrid, SquareShape)
            .clickable(onClick = onToggle).heightIn(min = 44.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).border(1.dp, PhosphorGreen, SquareShape), contentAlignment = Alignment.Center) {
            Text(if (expanded) "–" else "+", style = MaterialTheme.typography.labelLarge, color = PhosphorGreen)
        }
        Text("// ${title.uppercase().replace(' ', '_')}", style = MaterialTheme.typography.titleSmall, color = PhosphorGreen, modifier = Modifier.weight(1f).padding(start = 10.dp))
        if (summary != null) Text(summary, style = MaterialTheme.typography.labelSmall, color = DrainAmber)
    }
}

/** CRT over the whole screen: static scanlines and edge darkening, plus a slow bright roll-bar and a faint flicker. Draws only. */
@Composable
fun ScanlineOverlay(modifier: Modifier = Modifier, spacing: androidx.compose.ui.unit.Dp = 3.dp, alpha: Float = 0.12f) {
    Canvas(modifier.fillMaxSize()) {
        val step = spacing.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(Color.Black.copy(alpha = alpha), Offset(0f, y), Offset(size.width, y), 1f)
            y += step
        }
        drawRect(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f)),
                center = center, radius = size.maxDimension * 0.9f,
            ),
        )
    }
    val t = rememberInfiniteTransition(label = "crt")
    val roll by t.animateFloat(-0.2f, 1.2f, infiniteRepeatable(tween(7000, easing = androidx.compose.animation.core.LinearEasing)), label = "roll")
    val flicker by t.animateFloat(0.0f, 1f, infiniteRepeatable(tween(90, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Reverse), label = "flicker")
    Canvas(modifier.fillMaxSize()) {
        val h = size.height * 0.16f; val top = size.height * roll - h / 2
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color.Transparent, PhosphorGreen.copy(alpha = 0.045f), Color.Transparent), startY = top, endY = top + h,
            ),
            topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, h),
        )
        drawRect(Color.White.copy(alpha = 0.006f + 0.008f * flicker))
    }
}

/** Faint square grid for the screen background. */
@Composable
fun GridBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val step = 32.dp.toPx(); val c = PhosphorGreen.copy(alpha = 0.035f)
        var x = 0f
        while (x < size.width) { drawLine(c, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = 0f
        while (y < size.height) { drawLine(c, Offset(0f, y), Offset(size.width, y), 1f); y += step }
    }
}

/** Terminal output with a blinking block cursor: "> PREPARING_GAME_DATA_". */
@Composable
fun TerminalLine(text: String, modifier: Modifier = Modifier, color: Color = PhosphorGreen) {
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "cursor",
    )
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = MutedSilver)) { append("> ") }
            append(text)
            withStyle(SpanStyle(color = color.copy(alpha = if (blink > 0.5f) 1f else 0f))) { append(" █") }
        },
        style = MaterialTheme.typography.labelLarge.copy(shadow = Shadow(color.copy(alpha = 0.5f), blurRadius = 12f)),
        color = color, modifier = modifier,
    )
}
