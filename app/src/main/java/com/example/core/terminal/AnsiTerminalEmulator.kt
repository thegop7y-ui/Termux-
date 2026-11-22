package com.example.core.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

sealed interface TermColor {
    data object Default : TermColor
    data class Indexed(val index: Int) : TermColor
    data class Rgb(val r: Int, val g: Int, val b: Int) : TermColor
}

data class CellStyle(
    val fg: TermColor = TermColor.Default,
    val bg: TermColor = TermColor.Default,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val strikethrough: Boolean = false
)

data class TerminalCell(
    val ch: Char = ' ',
    val style: CellStyle = CellStyle()
)

data class TerminalScreenSnapshot(
    val lines: List<List<TerminalCell>>,
    val scrollbackCount: Int,
    val totalLines: Int,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val cols: Int,
    val rows: Int,
    val isAlternateScreen: Boolean,
    val title: String,
    val version: Long
)

class AnsiTerminalEmulator(
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val maxScrollback: Int = 2500
) {
    private var cols = initialCols.coerceIn(20, 240)
    private var rows = initialRows.coerceIn(8, 120)

    private val primaryScrollback = ArrayDeque<MutableList<TerminalCell>>()
    private var primaryGrid = createGrid(rows, cols)
    private var primaryCursorRow = 0
    private var primaryCursorCol = 0

    private var altGrid = createGrid(rows, cols)
    private var altCursorRow = 0
    private var altCursorCol = 0

    private var usingAlt = false
    private var cursorVisible = true
    private var title = "bash"
    private var currentStyle = CellStyle()

    private enum class ParseState { GROUND, ESC, CSI, OSC, OSC_ESC }
    private var parseState = ParseState.GROUND
    private val csiBuffer = StringBuilder()
    private val oscBuffer = StringBuilder()

    private val versionCounter = AtomicLong(1L)
    private val lock = Any()

    var onBell: (() -> Unit)? = null

    private val _snapshot = MutableStateFlow(buildSnapshotLocked())
    val snapshot: StateFlow<TerminalScreenSnapshot> = _snapshot.asStateFlow()

    private fun activeGrid(): MutableList<MutableList<TerminalCell>> = if (usingAlt) altGrid else primaryGrid
    private var cursorRow: Int
        get() = if (usingAlt) altCursorRow else primaryCursorRow
        set(value) {
            if (usingAlt) altCursorRow = value else primaryCursorRow = value
        }
    private var cursorCol: Int
        get() = if (usingAlt) altCursorCol else primaryCursorCol
        set(value) {
            if (usingAlt) altCursorCol = value else primaryCursorCol = value
        }

    private fun createRow(width: Int): MutableList<TerminalCell> =
        MutableList(width) { TerminalCell() }

    private fun createGrid(r: Int, c: Int): MutableList<MutableList<TerminalCell>> =
        MutableList(r) { createRow(c) }

    fun resize(newCols: Int, newRows: Int) {
        val targetCols = newCols.coerceIn(20, 240)
        val targetRows = newRows.coerceIn(8, 120)
        synchronized(lock) {
            if (targetCols == cols && targetRows == rows) return
            cols = targetCols
            rows = targetRows
            primaryGrid = resizeGrid(primaryGrid, rows, cols)
            altGrid = resizeGrid(altGrid, rows, cols)
            primaryCursorRow = primaryCursorRow.coerceIn(0, rows - 1)
            primaryCursorCol = primaryCursorCol.coerceIn(0, cols - 1)
            altCursorRow = altCursorRow.coerceIn(0, rows - 1)
            altCursorCol = altCursorCol.coerceIn(0, cols - 1)
            emitLocked()
        }
    }

    private fun resizeGrid(
        old: MutableList<MutableList<TerminalCell>>,
        newRows: Int,
        newCols: Int
    ): MutableList<MutableList<TerminalCell>> {
        val result = createGrid(newRows, newCols)
        val copyRows = minOf(old.size, newRows)
        for (r in 0 until copyRows) {
            val src = old[r]
            val copyCols = minOf(src.size, newCols)
            for (c in 0 until copyCols) {
                result[r][c] = src[c]
            }
        }
        return result
    }

    fun write(text: String) {
        synchronized(lock) {
            for (ch in text) {
                processCharLocked(ch)
            }
            emitLocked()
        }
    }

    fun clearAll() {
        synchronized(lock) {
            primaryScrollback.clear()
            primaryGrid = createGrid(rows, cols)
            primaryCursorRow = 0
            primaryCursorCol = 0
            emitLocked()
        }
    }

    fun getPlainText(): String = synchronized(lock) {
        val sb = StringBuilder()
        if (!usingAlt) {
            for (row in primaryScrollback) {
                sb.append(rowToTrimmedString(row)).append('\n')
            }
        }
        val grid = activeGrid()
        for (i in grid.indices) {
            sb.append(rowToTrimmedString(grid[i]))
            if (i < grid.lastIndex) sb.append('\n')
        }
        sb.toString().trimEnd('\n')
    }

    private fun rowToTrimmedString(row: List<TerminalCell>): String {
        var lastNonSpace = -1
        for (i in row.indices) {
            if (row[i].ch != ' ') lastNonSpace = i
        }
        if (lastNonSpace < 0) return ""
        val sb = StringBuilder(lastNonSpace + 1)
        for (i in 0..lastNonSpace) sb.append(row[i].ch)
        return sb.toString()
    }

    private fun processCharLocked(ch: Char) {
        when (parseState) {
            ParseState.GROUND -> when (ch) {
                '\u001B' -> parseState = ParseState.ESC
                '\u0007' -> onBell?.invoke()
                '\b' -> if (cursorCol > 0) cursorCol--
                '\t' -> {
                    val nextTab = ((cursorCol / 8) + 1) * 8
                    val target = minOf(nextTab, cols - 1)
                    while (cursorCol < target) putPrintableLocked(' ')
                }
                '\r' -> cursorCol = 0
                '\n' -> lineFeedLocked()
                else -> if (ch.code >= 32) putPrintableLocked(ch)
            }

            ParseState.ESC -> when (ch) {
                '[' -> {
                    csiBuffer.clear()
                    parseState = ParseState.CSI
                }
                ']' -> {
                    oscBuffer.clear()
                    parseState = ParseState.OSC
                }
                'c' -> {
                    currentStyle = CellStyle()
                    clearScreenLocked()
                    parseState = ParseState.GROUND
                }
                else -> parseState = ParseState.GROUND
            }

            ParseState.CSI -> {
                if (ch.code in 0x40..0x7E) {
                    executeCsiLocked(csiBuffer.toString(), ch)
                    parseState = ParseState.GROUND
                } else {
                    csiBuffer.append(ch)
                    if (csiBuffer.length > 64) parseState = ParseState.GROUND
                }
            }

            ParseState.OSC -> when (ch) {
                '\u0007' -> {
                    handleOscLocked(oscBuffer.toString())
                    parseState = ParseState.GROUND
                }
                '\u001B' -> parseState = ParseState.OSC_ESC
                else -> {
                    oscBuffer.append(ch)
                    if (oscBuffer.length > 256) parseState = ParseState.GROUND
                }
            }

            ParseState.OSC_ESC -> {
                if (ch == '\\') {
                    handleOscLocked(oscBuffer.toString())
                }
                parseState = ParseState.GROUND
            }
        }
    }

    private fun putPrintableLocked(ch: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeedLocked()
        }
        val grid = activeGrid()
        val row = cursorRow.coerceIn(0, rows - 1)
        val col = cursorCol.coerceIn(0, cols - 1)
        grid[row][col] = TerminalCell(ch, currentStyle)
        cursorCol++
    }

    private fun lineFeedLocked() {
        if (cursorRow < rows - 1) {
            cursorRow++
        } else {
            val grid = activeGrid()
            val top = grid.removeAt(0)
            if (!usingAlt) {
                primaryScrollback.addLast(top)
                while (primaryScrollback.size > maxScrollback) {
                    primaryScrollback.removeFirst()
                }
            }
            grid.add(createRow(cols))
        }
    }

    private fun clearScreenLocked() {
        val grid = activeGrid()
        for (r in 0 until rows) {
            grid[r] = createRow(cols)
        }
        cursorRow = 0
        cursorCol = 0
    }

    private fun handleOscLocked(payload: String) {
        val idx = payload.indexOf(';')
        if (idx > 0) {
            val code = payload.substring(0, idx)
            if (code == "0" || code == "2") {
                title = payload.substring(idx + 1).take(48)
            }
        }
    }

    private fun executeCsiLocked(rawParams: String, finalChar: Char) {
        val isPrivate = rawParams.startsWith("?")
        val clean = if (isPrivate) rawParams.substring(1) else rawParams
        val params = if (clean.isEmpty()) emptyList() else clean.split(';').map { it.toIntOrNull() ?: 0 }

        fun p(index: Int, defaultVal: Int): Int =
            params.getOrNull(index)?.takeIf { it > 0 } ?: defaultVal

        if (isPrivate) {
            val mode = params.firstOrNull() ?: return
            when (mode) {
                25 -> cursorVisible = (finalChar == 'h')
                1049, 47, 1047 -> {
                    if (finalChar == 'h' && !usingAlt) {
                        usingAlt = true
                        altGrid = createGrid(rows, cols)
                        altCursorRow = 0
                        altCursorCol = 0
                    } else if (finalChar == 'l' && usingAlt) {
                        usingAlt = false
                    }
                }
            }
            return
        }

        when (finalChar) {
            'A' -> cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + p(0, 1)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + p(0, 1)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - p(0, 1)).coerceAtLeast(0)
            'E' -> {
                cursorRow = (cursorRow + p(0, 1)).coerceAtMost(rows - 1)
                cursorCol = 0
            }
            'F' -> {
                cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(0)
                cursorCol = 0
            }
            'G' -> cursorCol = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (p(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> {
                val mode = params.firstOrNull() ?: 0
                val grid = activeGrid()
                when (mode) {
                    0 -> {
                        for (c in cursorCol until cols) grid[cursorRow][c] = TerminalCell()
                        for (r in (cursorRow + 1) until rows) grid[r] = createRow(cols)
                    }
                    1 -> {
                        for (r in 0 until cursorRow) grid[r] = createRow(cols)
                        for (c in 0..cursorCol.coerceAtMost(cols - 1)) grid[cursorRow][c] = TerminalCell()
                    }
                    2, 3 -> {
                        if (mode == 3 && !usingAlt) primaryScrollback.clear()
                        for (r in 0 until rows) grid[r] = createRow(cols)
                    }
                }
            }
            'K' -> {
                val mode = params.firstOrNull() ?: 0
                val row = activeGrid()[cursorRow.coerceIn(0, rows - 1)]
                when (mode) {
                    0 -> for (c in cursorCol.coerceAtLeast(0) until cols) row[c] = TerminalCell()
                    1 -> for (c in 0..cursorCol.coerceAtMost(cols - 1)) row[c] = TerminalCell()
                    2 -> for (c in 0 until cols) row[c] = TerminalCell()
                }
            }
            'm' -> applySgrLocked(if (params.isEmpty()) listOf(0) else params)
        }
    }

    private fun applySgrLocked(codes: List<Int>) {
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> currentStyle = CellStyle()
                1 -> currentStyle = currentStyle.copy(bold = true)
                2 -> currentStyle = currentStyle.copy(dim = true)
                3 -> currentStyle = currentStyle.copy(italic = true)
                4 -> currentStyle = currentStyle.copy(underline = true)
                7 -> currentStyle = currentStyle.copy(inverse = true)
                9 -> currentStyle = currentStyle.copy(strikethrough = true)
                22 -> currentStyle = currentStyle.copy(bold = false, dim = false)
                23 -> currentStyle = currentStyle.copy(italic = false)
                24 -> currentStyle = currentStyle.copy(underline = false)
                27 -> currentStyle = currentStyle.copy(inverse = false)
                29 -> currentStyle = currentStyle.copy(strikethrough = false)
                in 30..37 -> currentStyle = currentStyle.copy(fg = TermColor.Indexed(code - 30))
                39 -> currentStyle = currentStyle.copy(fg = TermColor.Default)
                in 40..47 -> currentStyle = currentStyle.copy(bg = TermColor.Indexed(code - 40))
                49 -> currentStyle = currentStyle.copy(bg = TermColor.Default)
                in 90..97 -> currentStyle = currentStyle.copy(fg = TermColor.Indexed(code - 90 + 8))
                in 100..107 -> currentStyle = currentStyle.copy(bg = TermColor.Indexed(code - 100 + 8))
                38, 48 -> {
                    val isFg = code == 38
                    val mode = codes.getOrNull(i + 1)
                    if (mode == 5 && i + 2 < codes.size) {
                        val idx = codes[i + 2].coerceIn(0, 255)
                        currentStyle = if (isFg) currentStyle.copy(fg = TermColor.Indexed(idx))
                        else currentStyle.copy(bg = TermColor.Indexed(idx))
                        i += 2
                    } else if (mode == 2 && i + 4 < codes.size) {
                        val r = codes[i + 2].coerceIn(0, 255)
                        val g = codes[i + 3].coerceIn(0, 255)
                        val b = codes[i + 4].coerceIn(0, 255)
                        currentStyle = if (isFg) currentStyle.copy(fg = TermColor.Rgb(r, g, b))
                        else currentStyle.copy(bg = TermColor.Rgb(r, g, b))
                        i += 4
                    }
                }
            }
            i++
        }
    }

    private fun emitLocked() {
        _snapshot.value = buildSnapshotLocked()
    }

    private fun buildSnapshotLocked(): TerminalScreenSnapshot {
        val allLines = ArrayList<List<TerminalCell>>(
            (if (usingAlt) 0 else primaryScrollback.size) + rows
        )
        if (!usingAlt) {
            for (line in primaryScrollback) {
                allLines.add(line.toList())
            }
        }
        val grid = activeGrid()
        for (line in grid) {
            allLines.add(line.toList())
        }
        val sbCount = if (usingAlt) 0 else primaryScrollback.size
        return TerminalScreenSnapshot(
            lines = allLines,
            scrollbackCount = sbCount,
            totalLines = allLines.size,
            cursorRow = sbCount + cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible,
            cols = cols,
            rows = rows,
            isAlternateScreen = usingAlt,
            title = title,
            version = versionCounter.getAndIncrement()
        )
    }
}
