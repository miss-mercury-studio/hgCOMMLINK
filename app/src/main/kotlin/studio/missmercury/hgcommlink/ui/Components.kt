package studio.missmercury.hgcommlink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.runtime.setValue
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
            // The readout on the right shares the title's line while there is room, and drops to its own line when there is not,
            // so a long readout or a large font never squeezes the title into a broken "[ TITLE" / "]".
            FlowRow(
                Modifier.fillMaxWidth().then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier),
                horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildAnnotatedString {
                        append("[ ")
                        if (tag != null) { withStyle(SpanStyle(color = accent)) { append(tag) }; append(" // ") }
                        withStyle(SpanStyle(color = if (tag == null) accent else Quicksilver)) { append(title.uppercase().replace(' ', '_')) }
                        append(" ]")
                    },
                    style = MaterialTheme.typography.titleSmall.copy(shadow = Shadow(accent.copy(alpha = 0.55f), blurRadius = 14f)),
                )
                if (trailing != null || collapsed != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelSmall, color = accent)
                    if (collapsed != null) Text(if (collapsed) "  +" else "  –", style = MaterialTheme.typography.labelLarge, color = accent)
                }
            }
            if (collapsed != true) content()
        }
    }
}

/** A labelled readout in a cell: the label small on top, the value large in mono, a thin accent bar along the top edge. */
@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier, accent: Color = Quicksilver, sub: String? = null) {
    Column(modifier.clip(SquareShape).background(SlateHigh).border(1.dp, TerminalGrid, SquareShape)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(accent.copy(alpha = if (accent == Quicksilver) 0.25f else 0.8f)))
        Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp, start = 4.dp, end = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MutedSilver, textAlign = TextAlign.Center, maxLines = 1)
            Text(
                value, style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMono, shadow = Shadow(accent.copy(alpha = 0.35f), blurRadius = 10f)),
                color = accent, textAlign = TextAlign.Center, maxLines = 1,
            )
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MutedSilver, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

/**
 * A thin strip of key:value telemetry, like the pinned bar in the mockups. The items share one line while they fit and wrap onto
 * the next line when they do not (a narrow phone, a large font, a long amount), so no value is ever cut off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelemetryStrip(items: List<Pair<String, String>>, modifier: Modifier = Modifier, accent: Color = PhosphorGreen, stacked: Boolean = false, valueColors: Map<String, Color> = emptyMap()) {
    if (stacked) {
        // One item per line, all left-aligned.
        Column(
            modifier.fillMaxWidth().clip(SquareShape).background(MercurySlate).border(1.dp, TerminalGrid, SquareShape).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEach { (label, value) ->
                Text(
                    buildAnnotatedString {
                        if (label.isNotEmpty()) withStyle(SpanStyle(color = MutedSilver)) { append("${label.uppercase()}: ") }
                        withStyle(SpanStyle(color = valueColors[label] ?: accent)) { append(value) }
                    },
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
        return
    }
    FlowRow(
        modifier.fillMaxWidth().clip(SquareShape).background(MercurySlate).border(1.dp, TerminalGrid, SquareShape).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // No "|" separators: when the items wrap, a separator would be left dangling at the end of a line. The muted labels
        // already tell the items apart.
        items.forEach { (label, value) ->
            Text(
                buildAnnotatedString {
                    if (label.isNotEmpty()) withStyle(SpanStyle(color = MutedSilver)) { append("${label.uppercase()}: ") }
                    withStyle(SpanStyle(color = accent)) { append(value) }
                },
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** A command button: [ LABEL ]. Filled for the main action, outlined otherwise. */
@Composable
fun TerminalButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    accent: Color = PhosphorGreen, filled: Boolean = false, enabled: Boolean = true, outlineTint: Color? = null,
) {
    Box(
        modifier.alpha(if (enabled) 1f else 0.4f)
            .then(if (filled && enabled) Modifier.shadow(10.dp, CardShape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(CardShape)
            .background(if (filled) accent else MercurySlate)
            .border(1.dp, if (filled) accent else outlineTint ?: TerminalGrid, CardShape)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The label shrinks until it fits on one line, so a narrow phone, a large font or a long label never cuts it off.
        // The label shrinks only when it is too wide. (Overflow in height does not count: in a tall sheet the button row can be squeezed, and
        // that used to shrink the label to its smallest size for good.)
        val base = MaterialTheme.typography.labelLarge
        var fit by androidx.compose.runtime.remember(label) { androidx.compose.runtime.mutableFloatStateOf(1f) }
        Text(
            "[ ${label.uppercase().replace(' ', '_')} ]", style = base, fontSize = base.fontSize * fit, maxLines = 1, softWrap = false,
            color = if (filled) Obsidian else outlineTint ?: Quicksilver,
            onTextLayout = { if (it.didOverflowWidth && fit > 0.55f) fit *= 0.92f },
        )
    }
}

/**
 * A compact action on a list item (upgrade, sell, remove, plug in): an outlined chip in the action's colour. Every one has the same height and
 * type size, so a row of them reads as one set whatever the words say; they wrap to the next line on a narrow screen.
 */
@Composable
fun CardAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = PhosphorGreen, enabled: Boolean = true) {
    Box(
        modifier.alpha(if (enabled) 1f else 0.4f).border(1.dp, accent.copy(alpha = 0.7f)).clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = accent, textAlign = TextAlign.Center)
    }
}

/** The heading over a group of list items: its name in the group's colour, how many there are, and a rule. */
@Composable
fun GroupHeader(title: String, count: Int, accent: Color) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("// ${title.uppercase()}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = accent)
            Text("$count ${if (count == 1) "item" else "items"}", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
        }
        Hairline(Modifier.padding(top = 4.dp), color = accent.copy(alpha = 0.4f))
    }
}

/**
 * One list item as a card: a bar in the group's colour down the left edge, then whatever [content] holds (a [CardTitle], [CardTags], notes and
 * [CardActions]). The bar is drawn behind the content, so the card is exactly as tall as what is in it, wrapped actions included.
 * [compact] tightens the padding, for long lists of simple items.
 */
@Composable
fun ItemCard(accent: Color, modifier: Modifier = Modifier, compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier.fillMaxWidth().background(MercurySlate).border(1.dp, TerminalGrid)
            .drawBehind { drawRect(accent.copy(alpha = 0.85f), size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) },
    ) {
        val v = if (compact) 8.dp else 12.dp
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = v, end = 12.dp, bottom = v), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp), content = content)
    }
}

/** An item's name, with a figure on the right (essence, damage, quantity) and, under it, a small hint such as "EDIT ›" when tapping the card does something. */
@Composable
fun CardTitle(name: String, modifier: Modifier = Modifier, trailing: String? = null, hint: String? = null, bold: Boolean = true) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(
            name, Modifier.weight(1f), fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null, color = Quicksilver,
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
        )
        if (!trailing.isNullOrBlank() || hint != null) Column(horizontalAlignment = Alignment.End) {
            if (!trailing.isNullOrBlank()) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MutedSilver, textAlign = TextAlign.End)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = PhosphorGreen.copy(alpha = 0.8f))
        }
    }
}

/** Small tags for an item's grade, rating, category and figures; they wrap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardTags(tags: List<Pair<String, Color>>) {
    if (tags.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.forEach { (text, accent) -> Tag(text, accent) }
    }
}

/** A muted line of detail under an item's tags. */
@Composable
fun CardNote(text: String) {
    if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.labelSmall, color = MutedSilver)
}

/** The row of [CardAction]s at the foot of a card; they wrap onto more lines when they do not fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardActions(content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** A bracketed − / + control, [-] and [+]. */
@Composable
fun StepButton(label: String, size: Int = 40, color: Color = PhosphorGreen, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text("[$label]", style = MaterialTheme.typography.labelLarge.copy(shadow = Shadow(color.copy(alpha = 0.5f), blurRadius = 10f)), color = color)
    }
}

/**
 * A labelled number with a minus and a plus around it, as in "RATING  1 to 6  [−] 3 [+]". Tapping the buttons steps by one within [min] and
 * [max]; the number can also be typed (a rating of 40 or 500 rounds are quicker that way). [onChange] gets the new text, which may be empty
 * while typing, so the caller decides what an empty value means. A value outside the range shows in amber. [note] replaces the "min to max" line.
 */
@Composable
fun NumberStepper(label: String, value: String, onChange: (String) -> Unit, min: Int, max: Int, modifier: Modifier = Modifier, note: String? = null, integer: Boolean = true) {
    val current = value.toDoubleOrNull()
    fun set(v: Double) {
        val c = v.coerceIn(min.toDouble(), maxOf(min, max).toDouble())
        onChange(if (c == Math.rint(c)) c.toLong().toString() else c.toString())
    }
    val outside = current != null && (current < min || current > max)
    Row(modifier.fillMaxWidth().background(SlateHigh).border(1.dp, TerminalGrid), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = Quicksilver)
            Text(note ?: "$min to $max", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
        }
        StepButton("−", 48) { set((current ?: min.toDouble()) - 1) }
        BasicTextField(
            value, { text -> onChange(text.filter { it.isDigit() || (!integer && it == '.') }.take(7)) }, singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMono, color = if (outside) DrainAmber else PhosphorGreen, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            cursorBrush = SolidColor(PhosphorGreen),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(72.dp),
        )
        StepButton("+", 48) { set((current ?: (min - 1).toDouble()) + 1) }
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

/** Flavor lines for the app's real waiting spots; each loader tells a short story about what the app is doing. */
object LoadLines {
    val storage = listOf("mounting storage volumes", "locating character folder", "scanning for .chum5 files", "reading character headers", "sorting the roster", "checking for black ICE")
    val gameData = listOf(
        "verifying Chummer data pack", "parsing metatypes.xml", "parsing skills.xml", "parsing qualities.xml", "parsing spells.xml", "parsing gear.xml",
        "indexing entries by id", "registering bonus handlers", "building picker catalogs", "polishing the chrome", "waking the decker",
    )
    val character = listOf(
        "jacking in", "resolving settings profile", "checking custom data", "parsing game data", "reading the improvements", "tallying karma and nuyen",
        "weighing essence against magic", "rolling the dice pools", "counting contacts", "paying the fixer", "handshaking with the matrix",
    )
}

/**
 * A loading indicator written as terminal output: [lines] are logged one after another from the top left, each finished line gets
 * a green [ OK ], and the line in progress has a spinner. The log grows down through all the height it is offered and only then scrolls
 * (when the height is unbounded, as inside a scrolling sheet, it keeps the last [fallbackLines]). If the wait outlasts the lines they
 * start over, so the log keeps moving for as long as the wait does. When [real] has lines (what the app is truly doing, from LoadLog), those
 * are shown instead of the flavor lines, as they arrive.
 */
@Composable
fun TerminalLoader(lines: List<String>, modifier: Modifier = Modifier, real: List<String> = emptyList(), fallbackLines: Int = 6) {
    var shown by androidx.compose.runtime.remember(lines) { androidx.compose.runtime.mutableIntStateOf(1) }
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(lines) {
        var n = 0
        while (true) {
            kotlinx.coroutines.delay(90)
            tick++; n++
            if (n % 3 == 0) shown++
        }
    }
    val spinner = "|/-\\"
    val rowHeight = 24.dp
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val capacity = if (constraints.hasBoundedHeight) (maxHeight / rowHeight).toInt().coerceAtLeast(1) else fallbackLines
        val total = if (real.isNotEmpty()) real.size else shown
        val first = (total - capacity).coerceAtLeast(0)
        Column {
            for (i in first until total) {
                val done = i < total - 1
                Row(Modifier.height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                    Text("> ", style = MaterialTheme.typography.labelMedium, color = MutedSilver)
                    Text(if (real.isNotEmpty()) real[i] else lines[i % lines.size], Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelMedium, color = if (done) Quicksilver else PhosphorGreen)
                    if (done) Text("[ OK ]", style = MaterialTheme.typography.labelMedium, color = PhosphorGreen)
                    else Text("[ ${spinner[tick % spinner.length]} ]", style = MaterialTheme.typography.labelMedium, color = PhosphorGreen)
                }
            }
        }
    }
}

/** A single-line input as a shell prompt: "> text", with a green block cursor and a clear button. */
@Composable
fun TerminalField(
    value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Quicksilver),
        cursorBrush = SolidColor(PhosphorGreen),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().background(SlateHigh).border(1.dp, if (value.isEmpty()) TerminalGrid else PhosphorGreen.copy(alpha = 0.6f))
                    .heightIn(min = 48.dp).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("> ", style = MaterialTheme.typography.bodyLarge, color = PhosphorGreen)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = MutedSilver.copy(alpha = 0.7f))
                    inner()
                }
                if (value.isNotEmpty()) Box(Modifier.size(48.dp).clickable { onChange("") }, contentAlignment = Alignment.Center) {
                    Text("×", style = MaterialTheme.typography.titleMedium, color = MutedSilver)
                }
            }
        },
    )
}

/** A row of filter choices, [ALL] [LANGUAGE] ...: the chosen one in inverse video. */
@Composable
fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val on = o == selected
            Text(
                o.uppercase(), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                color = if (on) Obsidian else MutedSilver,
                modifier = Modifier.background(if (on) PhosphorGreen else SlateHigh).border(1.dp, if (on) PhosphorGreen else TerminalGrid)
                    .clickable { onSelect(o) }.heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** A line along the sheet or panel: used under headers. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = TerminalGrid) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * A bottom sheet in the terminal style: sharp edges, a glowing handle, a [ TITLE ] header with a close box.
 * [content] gets a function that closes the sheet with its slide-down animation. A [tall] sheet fills most of the screen (for lists).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSheet(
    title: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, accent: Color = PhosphorGreen, tall: Boolean = false,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, shape = SquareShape,
        containerColor = MercurySlate, scrimColor = Color.Black.copy(alpha = 0.65f),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 44.dp, height = 3.dp).shadow(8.dp, ambientColor = accent, spotColor = accent).background(accent))
        },
    ) {
        Column(modifier.fillMaxWidth().then(if (tall) Modifier.fillMaxHeight(0.86f) else Modifier).padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "[ ${title.uppercase().replace(' ', '_')} ]",
                    style = MaterialTheme.typography.titleSmall.copy(shadow = Shadow(accent.copy(alpha = 0.55f), blurRadius = 14f)),
                    color = accent, modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(44.dp).clickable(onClick = close), contentAlignment = Alignment.Center) {
                    Text("[×]", style = MaterialTheme.typography.labelLarge, color = MutedSilver)
                }
            }
            Hairline(color = accent.copy(alpha = 0.4f))
            content(close)
            Box(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

/** A labelled group of choices that wrap onto several lines: one is chosen, shown in inverse video. Each option is an id and a label. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(label: String, options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("// ${label.uppercase().replace(' ', '_')}", style = MaterialTheme.typography.labelMedium, color = PhosphorGreen)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (id, text) ->
                val on = id == selected
                Text(
                    text.uppercase(), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    color = if (on) Obsidian else Quicksilver,
                    modifier = Modifier.background(if (on) PhosphorGreen else SlateHigh).border(1.dp, if (on) PhosphorGreen else TerminalGrid)
                        .clickable { onSelect(id) }.heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** A yes or no question in a short bottom sheet. [danger] colours it red, for deleting. */
@Composable
fun ConfirmSheet(
    title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    val accent = if (danger) CriticalRed else PhosphorGreen
    TerminalSheet(title, onDismiss, accent = accent) { close ->
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Quicksilver)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TerminalButton("Cancel", close, Modifier.weight(1f))
                TerminalButton(confirmLabel, { close(); onConfirm() }, Modifier.weight(1f), accent = accent, filled = true)
            }
        }
    }
}

/** A dropdown menu in the terminal style: sharp, outlined in green. */
@Composable
fun TerminalMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        containerColor = MercurySlate, shape = SquareShape, border = BorderStroke(1.dp, PhosphorGreen.copy(alpha = 0.45f)),
        content = content,
    )
}

@Composable
fun TerminalMenuItem(label: String, onClick: () -> Unit, danger: Boolean = false, tag: String? = null, tagColor: Color = Quicksilver) {
    androidx.compose.material3.DropdownMenuItem(
        text = {
            // The label keeps its colour; an optional tag after it has its own.
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    append("> ${label.uppercase()}")
                    if (tag != null) withStyle(androidx.compose.ui.text.SpanStyle(color = tagColor)) { append(" [ ${tag.uppercase()} ]") }
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (danger) CriticalRed else Quicksilver,
            )
        },
        onClick = onClick, modifier = Modifier.heightIn(min = 52.dp),
    )
}
