package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.terminal.TermColor
import com.example.core.terminal.TerminalCell
import com.example.core.terminal.TerminalScreenSnapshot
import com.example.core.tui.TerminalKeyEvent
import com.example.ui.theme.CursorStyle
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TerminalColorScheme
import kotlinx.coroutines.delay
import kotlin.math.floor

@Composable
fun TerminalCanvas(
    snapshot: TerminalScreenSnapshot,
    scheme: TerminalColorScheme,
    fontSizeSp: Float,
    cursorStyle: CursorStyle,
    onResizeGrid: (cols: Int, rows: Int) -> Unit,
    onCharInput: (Char) -> Unit,
    onSpecialKey: (TerminalKeyEvent) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onOpenContextMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scrollState = rememberScrollState()

    val charStyle = remember(fontSizeSp) {
        TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * 1.28f).sp
        )
    }

    val cellMetrics = remember(fontSizeSp) {
        val res = textMeasurer.measure("M", style = charStyle)
        val w = res.size.width.toFloat().coerceAtLeast(8f)
        val h = res.size.height.toFloat().coerceAtLeast(14f)
        w to h
    }
    val cellW = cellMetrics.first
    val cellH = cellMetrics.second

    var blinkVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            blinkVisible = !blinkVisible
        }
    }

    // Auto-scroll to bottom on new output
    LaunchedEffect(snapshot.version) {
        if (!snapshot.isAlternateScreen) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    // Sentinel technique so Android IME Backspace always fires reliably even on empty field!
    val sentinel = "\u200B"
    var imeText by remember { mutableStateOf(sentinel) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .testTag("terminal_canvas")
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    },
                    onLongPress = { onOpenContextMenu() }
                )
            }
            .pointerInput(fontSizeSp) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) {
                        val next = (fontSizeSp * zoom).coerceIn(9.5f, 23f)
                        if (kotlin.math.abs(next - fontSizeSp) > 0.25f) {
                            onFontSizeChange(next)
                        }
                    }
                }
            }
    ) {
        val padPx = with(density) { 8.dp.toPx() }
        val availW = (constraints.maxWidth.toFloat() - padPx * 2).coerceAtLeast(cellW * 20)
        val availH = (constraints.maxHeight.toFloat() - padPx).coerceAtLeast(cellH * 8)
        val targetCols = floor(availW / cellW).toInt().coerceIn(24, 220)
        val targetRows = floor(availH / cellH).toInt().coerceIn(8, 100)

        LaunchedEffect(targetCols, targetRows) {
            onResizeGrid(targetCols, targetRows)
        }

        // Hidden IME input bridge
        BasicTextField(
            value = imeText,
            onValueChange = { updated ->
                if (updated.length < sentinel.length) {
                    onSpecialKey(TerminalKeyEvent.Backspace)
                    imeText = sentinel
                } else {
                    val added = updated.removePrefix(sentinel)
                    for (ch in added) {
                        if (ch == '\n') onSpecialKey(TerminalKeyEvent.Enter)
                        else if (ch != '\u200B') onCharInput(ch)
                    }
                    imeText = sentinel
                }
            },
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .testTag("terminal_ime_input")
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        ev.isCtrlPressed && ev.key == Key.C -> { onSpecialKey(TerminalKeyEvent.Ctrl('c')); true }
                        ev.isCtrlPressed && ev.key == Key.D -> { onSpecialKey(TerminalKeyEvent.Ctrl('d')); true }
                        ev.isCtrlPressed && ev.key == Key.L -> { onSpecialKey(TerminalKeyEvent.Ctrl('l')); true }
                        ev.isCtrlPressed && ev.key == Key.O -> { onSpecialKey(TerminalKeyEvent.Ctrl('o')); true }
                        ev.isCtrlPressed && ev.key == Key.X -> { onSpecialKey(TerminalKeyEvent.Ctrl('x')); true }
                        ev.key == Key.Backspace -> { onSpecialKey(TerminalKeyEvent.Backspace); true }
                        ev.key == Key.Enter -> { onSpecialKey(TerminalKeyEvent.Enter); true }
                        ev.key == Key.Tab -> { onSpecialKey(TerminalKeyEvent.Tab); true }
                        ev.key == Key.Escape -> { onSpecialKey(TerminalKeyEvent.Escape); true }
                        ev.key == Key.DirectionUp -> { onSpecialKey(TerminalKeyEvent.ArrowUp); true }
                        ev.key == Key.DirectionDown -> { onSpecialKey(TerminalKeyEvent.ArrowDown); true }
                        ev.key == Key.DirectionLeft -> { onSpecialKey(TerminalKeyEvent.ArrowLeft); true }
                        ev.key == Key.DirectionRight -> { onSpecialKey(TerminalKeyEvent.ArrowRight); true }
                        ev.key == Key.MoveHome -> { onSpecialKey(TerminalKeyEvent.Home); true }
                        ev.key == Key.MoveEnd -> { onSpecialKey(TerminalKeyEvent.End); true }
                        else -> false
                    }
                }
        )

        val renderedLines = remember(snapshot.version, scheme.id) {
            snapshot.lines.map { cells -> buildStyledLine(cells, scheme) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .then(if (!snapshot.isAlternateScreen) Modifier.verticalScroll(scrollState) else Modifier)
        ) {
            renderedLines.forEachIndexed { rowIdx, annotated ->
                val isCursorRow = snapshot.cursorVisible && rowIdx == snapshot.cursorRow
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = annotated,
                        style = charStyle,
                        softWrap = false,
                        maxLines = 1
                    )
                    if (isCursorRow && blinkVisible) {
                        val cursorX = snapshot.cursorCol * cellW
                        Canvas(modifier = Modifier.matchParentSize()) {
                            when (cursorStyle) {
                                CursorStyle.BLOCK -> drawRect(
                                    color = scheme.cursor.copy(alpha = 0.72f),
                                    topLeft = Offset(cursorX, 0f),
                                    size = Size(cellW, cellH)
                                )
                                CursorStyle.BAR -> drawRect(
                                    color = scheme.cursor,
                                    topLeft = Offset(cursorX, 0f),
                                    size = Size(with(density) { 2.dp.toPx() }, cellH)
                                )
                                CursorStyle.UNDERLINE -> drawRect(
                                    color = scheme.cursor,
                                    topLeft = Offset(cursorX, cellH - with(density) { 2.5.dp.toPx() }),
                                    size = Size(cellW, with(density) { 2.5.dp.toPx() })
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildStyledLine(cells: List<TerminalCell>, scheme: TerminalColorScheme): AnnotatedString {
    var lastUsed = -1
    for (i in cells.indices) {
        if (cells[i].ch != ' ' || cells[i].style.bg != TermColor.Default || cells[i].style.inverse) {
            lastUsed = i
        }
    }
    if (lastUsed < 0) return AnnotatedString(" ")

    return buildAnnotatedString {
        var i = 0
        while (i <= lastUsed) {
            val style = cells[i].style
            val start = i
            while (i <= lastUsed && cells[i].style == style) {
                append(cells[i].ch)
                i++
            }
            val end = i
            val fgColor = resolveTermColor(style.fg, scheme, isFg = true)
            val bgColor = resolveTermColor(style.bg, scheme, isFg = false)
            val effectiveFg = if (style.inverse) (if (bgColor == Color.Unspecified) scheme.background else bgColor)
            else if (fgColor == Color.Unspecified) scheme.foreground else fgColor
            val effectiveBg = if (style.inverse) (if (fgColor == Color.Unspecified) scheme.foreground else fgColor)
            else bgColor

            addStyle(
                SpanStyle(
                    color = if (style.dim) effectiveFg.copy(alpha = 0.6f) else effectiveFg,
                    background = effectiveBg,
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = when {
                        style.underline && style.strikethrough -> TextDecoration.combine(
                            listOf(TextDecoration.Underline, TextDecoration.LineThrough)
                        )
                        style.underline -> TextDecoration.Underline
                        style.strikethrough -> TextDecoration.LineThrough
                        else -> TextDecoration.None
                    }
                ),
                start,
                end
            )
        }
    }
}

private fun resolveTermColor(color: TermColor, scheme: TerminalColorScheme, isFg: Boolean): Color {
    return when (color) {
        TermColor.Default -> if (isFg) scheme.foreground else Color.Unspecified
        is TermColor.Rgb -> Color(color.r, color.g, color.b)
        is TermColor.Indexed -> {
            val idx = color.index
            when {
                idx in 0..15 -> scheme.ansiColors[idx]
                idx in 16..231 -> {
                    val v = idx - 16
                    val r = (v / 36) * 51
                    val g = ((v / 6) % 6) * 51
                    val b = (v % 6) * 51
                    Color(r, g, b)
                }
                idx in 232..255 -> {
                    val gray = 8 + (idx - 232) * 10
                    Color(gray, gray, gray)
                }
                else -> scheme.foreground
            }
        }
    }
}
