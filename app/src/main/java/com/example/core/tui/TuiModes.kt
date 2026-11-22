package com.example.core.tui

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.example.core.terminal.AnsiTerminalEmulator
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

sealed interface TerminalKeyEvent {
    data class CharInput(val ch: Char) : TerminalKeyEvent
    data object Enter : TerminalKeyEvent
    data object Backspace : TerminalKeyEvent
    data object Tab : TerminalKeyEvent
    data object Escape : TerminalKeyEvent
    data object ArrowUp : TerminalKeyEvent
    data object ArrowDown : TerminalKeyEvent
    data object ArrowLeft : TerminalKeyEvent
    data object ArrowRight : TerminalKeyEvent
    data object Home : TerminalKeyEvent
    data object End : TerminalKeyEvent
    data object PageUp : TerminalKeyEvent
    data object PageDown : TerminalKeyEvent
    data class Ctrl(val key: Char) : TerminalKeyEvent
}

class NanoEditorTui(
    private val file: File,
    private val editorName: String,
    private val terminal: AnsiTerminalEmulator
) {
    private val lines: MutableList<StringBuilder> = if (file.exists() && file.isFile) {
        file.readLines().map { StringBuilder(it) }.toMutableList().ifEmpty { mutableListOf(StringBuilder()) }
    } else {
        mutableListOf(StringBuilder())
    }
    private var curRow = 0
    private var curCol = 0
    private var scrollOffset = 0
    private var modified = false
    private var statusMessage = if (file.exists()) "[ Read ${lines.size} lines ]" else "[ New File ]"
    private var clipboardLine: String? = null

    fun enter() {
        terminal.write("\u001B[?1049h\u001B[?25h")
        render()
    }

    fun exit() {
        terminal.write("\u001B[?1049l\u001B[?25h")
    }

    /** Returns true to keep editing, false when user exits */
    fun handleKey(event: TerminalKeyEvent): Boolean {
        val snap = terminal.snapshot.value
        val bodyRows = (snap.rows - 4).coerceAtLeast(4)

        when (event) {
            is TerminalKeyEvent.Ctrl -> {
                when (event.key.lowercaseChar()) {
                    'x', 'q' -> {
                        exit()
                        return false
                    }
                    'o', 's' -> {
                        file.parentFile?.mkdirs()
                        file.writeText(lines.joinToString("\n") { it.toString() } + "\n")
                        modified = false
                        statusMessage = "[ Wrote ${lines.size} lines to ${file.name} ]"
                    }
                    'k' -> {
                        if (lines.size > 1) {
                            clipboardLine = lines.removeAt(curRow).toString()
                            curRow = curRow.coerceAtMost(lines.lastIndex)
                        } else {
                            clipboardLine = lines[0].toString()
                            lines[0].clear()
                        }
                        curCol = curCol.coerceAtMost(lines[curRow].length)
                        modified = true
                        statusMessage = "[ Cut line ]"
                    }
                    'u' -> {
                        clipboardLine?.let { clip ->
                            lines.add(curRow, StringBuilder(clip))
                            modified = true
                            statusMessage = "[ Uncut line ]"
                        }
                    }
                    'a' -> curCol = 0
                    'e' -> curCol = lines[curRow].length
                }
            }
            TerminalKeyEvent.ArrowUp -> {
                if (curRow > 0) curRow--
                curCol = curCol.coerceAtMost(lines[curRow].length)
            }
            TerminalKeyEvent.ArrowDown -> {
                if (curRow < lines.lastIndex) curRow++
                curCol = curCol.coerceAtMost(lines[curRow].length)
            }
            TerminalKeyEvent.ArrowLeft -> {
                if (curCol > 0) curCol--
                else if (curRow > 0) {
                    curRow--
                    curCol = lines[curRow].length
                }
            }
            TerminalKeyEvent.ArrowRight -> {
                if (curCol < lines[curRow].length) curCol++
                else if (curRow < lines.lastIndex) {
                    curRow++
                    curCol = 0
                }
            }
            TerminalKeyEvent.Home -> curCol = 0
            TerminalKeyEvent.End -> curCol = lines[curRow].length
            TerminalKeyEvent.PageUp -> {
                curRow = (curRow - bodyRows).coerceAtLeast(0)
                curCol = curCol.coerceAtMost(lines[curRow].length)
            }
            TerminalKeyEvent.PageDown -> {
                curRow = (curRow + bodyRows).coerceAtMost(lines.lastIndex)
                curCol = curCol.coerceAtMost(lines[curRow].length)
            }
            TerminalKeyEvent.Backspace -> {
                if (curCol > 0) {
                    lines[curRow].deleteCharAt(curCol - 1)
                    curCol--
                    modified = true
                } else if (curRow > 0) {
                    val prevLen = lines[curRow - 1].length
                    lines[curRow - 1].append(lines[curRow])
                    lines.removeAt(curRow)
                    curRow--
                    curCol = prevLen
                    modified = true
                }
            }
            TerminalKeyEvent.Enter -> {
                val tail = lines[curRow].substring(curCol)
                lines[curRow].delete(curCol, lines[curRow].length)
                lines.add(curRow + 1, StringBuilder(tail))
                curRow++
                curCol = 0
                modified = true
            }
            TerminalKeyEvent.Tab -> {
                lines[curRow].insert(curCol, "    ")
                curCol += 4
                modified = true
            }
            is TerminalKeyEvent.CharInput -> {
                lines[curRow].insert(curCol, event.ch)
                curCol++
                modified = true
            }
            TerminalKeyEvent.Escape -> { /* ignore in nano */ }
        }

        if (curRow < scrollOffset) scrollOffset = curRow
        if (curRow >= scrollOffset + bodyRows) scrollOffset = curRow - bodyRows + 1
        render()
        return true
    }

    fun render() {
        val snap = terminal.snapshot.value
        val cols = snap.cols
        val rows = snap.rows
        val bodyRows = (rows - 4).coerceAtLeast(4)
        val sb = StringBuilder()

        sb.append("\u001B[H")
        val titleLeft = "  GNU $editorName 8.1"
        val titleCenter = file.name + if (modified) " *" else ""
        val headerPad = (cols - titleLeft.length - titleCenter.length).coerceAtLeast(2)
        val headerLine = (titleLeft + " ".repeat(headerPad / 2) + titleCenter).padEnd(cols).take(cols)
        sb.append("\u001B[7m$headerLine\u001B[0m\r\n")

        for (r in 0 until bodyRows) {
            val lineIdx = scrollOffset + r
            sb.append("\u001B[2K")
            if (lineIdx < lines.size) {
                val text = lines[lineIdx].toString()
                sb.append(text.take(cols))
            }
            sb.append("\r\n")
        }

        val status = statusMessage.padEnd(cols).take(cols)
        sb.append("\u001B[7m$status\u001B[0m\r\n")
        val help1 = "^O Write Out   ^K Cut   ^U Uncut   ^X Exit   Ln ${curRow + 1}/${lines.size} Col ${curCol + 1}"
        sb.append("\u001B[2K").append(help1.take(cols))

        val screenRow = (curRow - scrollOffset) + 2
        val screenCol = curCol.coerceAtMost(cols - 1) + 1
        sb.append("\u001B[${screenRow};${screenCol}H")
        terminal.write(sb.toString())
    }
}

class HtopTui(
    private val context: Context,
    private val terminal: AnsiTerminalEmulator,
    private val sessionNamesProvider: () -> List<String>
) {
    suspend fun run(isCancelled: () -> Boolean, pollKey: () -> TerminalKeyEvent?) {
        terminal.write("\u001B[?1049h\u001B[?25l")
        try {
            var tick = 0
            while (!isCancelled()) {
                val key = pollKey()
                if (key is TerminalKeyEvent.CharInput && key.ch.lowercaseChar() == 'q') break
                if (key is TerminalKeyEvent.Ctrl && key.key.lowercaseChar() == 'c') break
                if (key == TerminalKeyEvent.Escape) break
                render(tick++)
                delay(650)
            }
        } finally {
            terminal.write("\u001B[?25h\u001B[?1049l")
        }
    }

    private fun render(tick: Int) {
        val snap = terminal.snapshot.value
        val cols = snap.cols
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMb = (mi.totalMem / (1024 * 1024)).coerceAtLeast(1)
        val usedMb = (mi.totalMem - mi.availMem) / (1024 * 1024)
        val memPct = (usedMb * 100 / totalMb).toInt().coerceIn(1, 100)

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)

        val cores = Runtime.getRuntime().availableProcessors().coerceAtMost(8)
        val sb = StringBuilder("\u001B[H")
        sb.append("\u001B[1;36mhtop 3.3.0 - Termux++ (${Build.MODEL})\u001B[0m\u001B[K\r\n")
        for (c in 0 until cores) {
            val pct = (12 + ((tick * 13 + c * 29) % 58))
            val barWidth = (cols - 22).coerceIn(10, 32)
            val filled = (pct * barWidth / 100)
            val bar = "\u001B[32m" + "|".repeat(filled) + "\u001B[90m" + " ".repeat(barWidth - filled) + "\u001B[0m"
            sb.append(String.format(Locale.US, "  CPU%d [%s %2d%%]\u001B[K\r\n", c, bar, pct))
        }
        val memBarW = (cols - 26).coerceIn(10, 32)
        val memFill = memPct * memBarW / 100
        val memBar = "\u001B[33m" + "|".repeat(memFill) + "\u001B[90m" + " ".repeat(memBarW - memFill) + "\u001B[0m"
        sb.append(String.format(Locale.US, "  Mem  [%s %d/%dMB]\u001B[K\r\n", memBar, usedMb, totalMb))
        sb.append("  Bat  [${batPct}%]   Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\u001B[K\r\n\r\n")
        sb.append("\u001B[7m  PID USER      VIRT   RES S  CPU% MEM%  Command                                \u001B[0m\r\n")

        val sessions = sessionNamesProvider()
        sessions.forEachIndexed { idx, name ->
            val pid = 1000 + idx
            sb.append(String.format(Locale.US, "%5d u0_a100   128M   34M R   1.2  0.8  bash (%s)\u001B[K\r\n", pid, name))
        }
        sb.append(String.format(Locale.US, "%5d u0_a100   256M   68M S   0.4  1.4  termux-cpp-runtime\u001B[K\r\n", 999))
        sb.append("\r\n\u001B[36mPress 'q' or ESC or Ctrl+C to quit htop.\u001B[0m\u001B[J")
        terminal.write(sb.toString())
    }
}
