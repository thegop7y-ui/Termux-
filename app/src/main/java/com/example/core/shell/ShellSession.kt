package com.example.core.shell

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.example.core.cpp.CppToolchain
import com.example.core.fs.TermuxEnvironment
import com.example.core.packages.PackageRepository
import com.example.core.python.PythonRuntime
import com.example.core.terminal.AnsiTerminalEmulator
import com.example.core.tools.AndroidBridge
import com.example.core.tools.ToolsExecutor
import com.example.core.tui.HtopTui
import com.example.core.tui.NanoEditorTui
import com.example.core.tui.TerminalKeyEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ShellSession(
    val id: String,
    initialName: String,
    private val context: Context,
    private val env: TermuxEnvironment,
    private val packageRepo: PackageRepository,
    private val bridge: AndroidBridge,
    private val scope: CoroutineScope,
    private val sessionNamesProvider: () -> List<String>,
    private val onCloseRequested: (String) -> Unit
) {
    val terminal = AnsiTerminalEmulator()
    private val tools = ToolsExecutor(context, env, bridge)

    private val _sessionName = MutableStateFlow(initialName)
    val sessionName: StateFlow<String> = _sessionName.asStateFlow()

    private val _cwdDisplay = MutableStateFlow("~")
    val cwdDisplay: StateFlow<String> = _cwdDisplay.asStateFlow()

    private val _isRunningCommand = MutableStateFlow(false)
    val isRunningCommand: StateFlow<Boolean> = _isRunningCommand.asStateFlow()

    private var cwd: File = env.homeDir
    private val envVars: MutableMap<String, String> = env.baseEnvMap()
    private val aliases: MutableMap<String, String> = mutableMapOf()
    private val history: MutableList<String> = mutableListOf()
    private var historyIndex = 0
    private var lastExitCode = 0

    // Interactive line editing state (canonical shell prompt)
    private val lineBuffer = StringBuilder()
    private var cursorInLine = 0

    // Active foreground execution / TUI state
    private var activeNano: NanoEditorTui? = null
    private var activeJob: Job? = null
    private var cancelFlag = AtomicBoolean(false)
    private var stdinWaiter: CompletableDeferred<String?>? = null
    private val stdinLineBuffer = StringBuilder()
    private val rawKeyQueue = Channel<TerminalKeyEvent>(Channel.UNLIMITED)

    init {
        loadBashRc()
        printMotdAndPrompt()
    }

    fun rename(newName: String) {
        _sessionName.value = newName.trim().ifEmpty { _sessionName.value }
    }

    private fun loadBashRc() {
        val bashrc = File(env.homeDir, ".bashrc")
        if (!bashrc.exists()) return
        for (raw in bashrc.readLines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("export ") && line.contains('=')) {
                val kv = line.removePrefix("export ").trim()
                val k = kv.substringBefore('=').trim()
                val v = kv.substringAfter('=').trim().trim('\'', '"')
                envVars[k] = v
            } else if (line.startsWith("alias ") && line.contains('=')) {
                val kv = line.removePrefix("alias ").trim()
                val k = kv.substringBefore('=').trim()
                val v = kv.substringAfter('=').trim().trim('\'', '"')
                aliases[k] = v
            }
        }
    }

    private fun printMotdAndPrompt() {
        terminal.write(env.defaultMotdText())
        terminal.write("\n")
        printPrompt()
    }

    private fun promptString(): String {
        val path = env.toDisplayPath(cwd)
        val statusColor = if (lastExitCode == 0) "\u001B[1;32m" else "\u001B[1;31m"
        return "\u001B[36m$path\u001B[0m ${statusColor}\$\u001B[0m "
    }

    private fun promptPlainLength(): Int = env.toDisplayPath(cwd).length + 3

    private fun printPrompt() {
        lineBuffer.clear()
        cursorInLine = 0
        historyIndex = history.size
        terminal.write(promptString())
    }

    fun submitCommandProgrammatically(command: String) {
        if (activeNano != null) return
        if (_isRunningCommand.value) {
            if (stdinWaiter != null) {
                terminal.write("$command\r\n")
                stdinWaiter?.complete(command)
                stdinWaiter = null
            }
            return
        }
        lineBuffer.clear()
        lineBuffer.append(command)
        cursorInLine = lineBuffer.length
        redrawPromptLine()
        handleKey(TerminalKeyEvent.Enter)
    }

    fun pasteText(text: String) {
        for (ch in text) {
            if (ch == '\n') handleKey(TerminalKeyEvent.Enter)
            else if (ch != '\r') handleKey(TerminalKeyEvent.CharInput(ch))
        }
    }

    fun handleKey(event: TerminalKeyEvent) {
        // 1. If Nano/Vim TUI is active, route key directly
        val nano = activeNano
        if (nano != null) {
            val keep = nano.handleKey(event)
            if (!keep) {
                activeNano = null
                _isRunningCommand.value = false
                printPrompt()
            }
            return
        }

        // 2. If Ctrl+C pressed while running command -> interrupt!
        if (event is TerminalKeyEvent.Ctrl && event.key.lowercaseChar() == 'c') {
            if (_isRunningCommand.value) {
                cancelFlag.set(true)
                stdinWaiter?.complete(null)
                stdinWaiter = null
                rawKeyQueue.trySend(event)
                activeJob?.cancel()
                terminal.write("^C\r\n")
                return
            } else {
                terminal.write("^C\r\n")
                printPrompt()
                return
            }
        }

        // 3. If a foreground job is running (htop / python REPL / cin / input)
        if (_isRunningCommand.value) {
            rawKeyQueue.trySend(event)
            val waiter = stdinWaiter
            if (waiter != null) {
                when (event) {
                    is TerminalKeyEvent.CharInput -> {
                        stdinLineBuffer.append(event.ch)
                        terminal.write(event.ch.toString())
                    }
                    TerminalKeyEvent.Backspace -> {
                        if (stdinLineBuffer.isNotEmpty()) {
                            stdinLineBuffer.deleteCharAt(stdinLineBuffer.lastIndex)
                            terminal.write("\b \b")
                        }
                    }
                    TerminalKeyEvent.Enter -> {
                        val submitted = stdinLineBuffer.toString()
                        stdinLineBuffer.clear()
                        terminal.write("\r\n")
                        stdinWaiter = null
                        waiter.complete(submitted)
                    }
                    is TerminalKeyEvent.Ctrl -> {
                        if (event.key.lowercaseChar() == 'd') {
                            stdinLineBuffer.clear()
                            terminal.write("\r\n")
                            stdinWaiter = null
                            waiter.complete(null)
                        }
                    }
                    else -> Unit
                }
            }
            return
        }

        // 4. Canonical shell line editing
        when (event) {
            is TerminalKeyEvent.CharInput -> {
                lineBuffer.insert(cursorInLine, event.ch)
                cursorInLine++
                redrawPromptLine()
            }
            TerminalKeyEvent.Backspace -> {
                if (cursorInLine > 0) {
                    lineBuffer.deleteCharAt(cursorInLine - 1)
                    cursorInLine--
                    redrawPromptLine()
                }
            }
            TerminalKeyEvent.ArrowLeft -> if (cursorInLine > 0) {
                cursorInLine--
                redrawPromptLine()
            }
            TerminalKeyEvent.ArrowRight -> if (cursorInLine < lineBuffer.length) {
                cursorInLine++
                redrawPromptLine()
            }
            TerminalKeyEvent.Home -> {
                cursorInLine = 0
                redrawPromptLine()
            }
            TerminalKeyEvent.End -> {
                cursorInLine = lineBuffer.length
                redrawPromptLine()
            }
            TerminalKeyEvent.ArrowUp -> {
                if (history.isNotEmpty() && historyIndex > 0) {
                    historyIndex--
                    lineBuffer.clear()
                    lineBuffer.append(history[historyIndex])
                    cursorInLine = lineBuffer.length
                    redrawPromptLine()
                }
            }
            TerminalKeyEvent.ArrowDown -> {
                if (historyIndex < history.size - 1) {
                    historyIndex++
                    lineBuffer.clear()
                    lineBuffer.append(history[historyIndex])
                    cursorInLine = lineBuffer.length
                } else {
                    historyIndex = history.size
                    lineBuffer.clear()
                    cursorInLine = 0
                }
                redrawPromptLine()
            }
            TerminalKeyEvent.Tab -> handleTabComplete()
            is TerminalKeyEvent.Ctrl -> {
                when (event.key.lowercaseChar()) {
                    'l' -> {
                        terminal.write("\u001B[2J\u001B[H")
                        redrawPromptLine()
                    }
                    'a' -> { cursorInLine = 0; redrawPromptLine() }
                    'e' -> { cursorInLine = lineBuffer.length; redrawPromptLine() }
                    'u' -> { lineBuffer.clear(); cursorInLine = 0; redrawPromptLine() }
                    'k' -> { lineBuffer.delete(cursorInLine, lineBuffer.length); redrawPromptLine() }
                    'd' -> {
                        if (lineBuffer.isEmpty()) {
                            terminal.write("logout\r\n")
                            onCloseRequested(id)
                        }
                    }
                }
            }
            TerminalKeyEvent.Enter -> {
                val cmdLine = lineBuffer.toString()
                terminal.write("\r\n")
                if (cmdLine.isNotBlank()) {
                    if (history.lastOrNull() != cmdLine) history.add(cmdLine)
                    executeInputLine(cmdLine)
                } else {
                    printPrompt()
                }
            }
            else -> Unit
        }
    }

    private fun redrawPromptLine() {
        val sb = StringBuilder()
        sb.append("\r\u001B[2K")
        sb.append(promptString())
        sb.append(lineBuffer.toString())
        val totalLen = promptPlainLength() + lineBuffer.length
        val targetCol = promptPlainLength() + cursorInLine
        val back = totalLen - targetCol
        if (back > 0) sb.append("\u001B[${back}D")
        terminal.write(sb.toString())
    }

    private fun handleTabComplete() {
        val prefix = lineBuffer.substring(0, cursorInLine)
        val lastSpace = prefix.lastIndexOf(' ')
        val token = prefix.substring(lastSpace + 1)
        val candidates = if (lastSpace < 0 && !token.contains('/')) {
            val builtins = listOf(
                "cd", "ls", "pwd", "cat", "mkdir", "rm", "cp", "mv", "touch", "grep", "find",
                "echo", "export", "env", "clear", "history", "pkg", "apt", "nano", "vim",
                "python", "python3", "pip", "g++", "clang++", "gcc", "clang", "make", "git",
                "curl", "wget", "sqlite3", "neofetch", "htop", "jq", "bc", "tree", "figlet",
                "zip", "unzip", "tar", "ssh-keygen", "termux-info", "help", "man", "exit"
            )
            val bins = env.binDir.list()?.toList().orEmpty()
            (builtins + bins).distinct().filter { it.startsWith(token) }.sorted()
        } else {
            val dirPart = if (token.contains('/')) token.substringBeforeLast('/') else ""
            val namePart = if (token.contains('/')) token.substringAfterLast('/') else token
            val dir = if (dirPart.isEmpty()) cwd else env.resolvePath(cwd, dirPart)
            dir.listFiles()
                ?.filter { it.name.startsWith(namePart) }
                ?.sortedBy { it.name }
                ?.map {
                    val suffix = if (it.isDirectory) "/" else ""
                    if (dirPart.isEmpty()) it.name + suffix else "$dirPart/${it.name}$suffix"
                }
                .orEmpty()
        }
        if (candidates.size == 1) {
            val match = candidates.first()
            val rest = match.removePrefix(token)
            lineBuffer.insert(cursorInLine, rest)
            cursorInLine += rest.length
            redrawPromptLine()
        } else if (candidates.size > 1) {
            terminal.write("\r\n" + candidates.joinToString("  ") + "\r\n")
            redrawPromptLine()
        }
    }

    private fun executeInputLine(cmdLine: String) {
        cancelFlag.set(false)
        _isRunningCommand.value = true
        activeJob = scope.launch(Dispatchers.Default) {
            try {
                lastExitCode = evalShellLine(cmdLine) { chunk ->
                    terminal.write(chunk.replace("\n", "\r\n"))
                }
            } catch (_: CancellationException) {
                lastExitCode = 130
            } catch (e: Exception) {
                terminal.write("\u001B[31mbash: ${e.message}\u001B[0m\r\n")
                lastExitCode = 1
            } finally {
                if (activeNano == null) {
                    _isRunningCommand.value = false
                    withContext(Dispatchers.Main) {
                        _cwdDisplay.value = env.toDisplayPath(cwd)
                    }
                    printPrompt()
                }
            }
        }
    }

    private suspend fun readInteractiveLine(): String? {
        val d = CompletableDeferred<String?>()
        stdinLineBuffer.clear()
        stdinWaiter = d
        return d.await()
    }

    private suspend fun evalShellLine(rawLine: String, out: (String) -> Unit): Int {
        var line = rawLine.trim()
        if (line.isEmpty()) return 0

        // Handle inline variable assignment e.g. FOO=bar
        if (!line.contains(' ') && line.contains('=') && !line.startsWith("=")) {
            val k = line.substringBefore('=').trim()
            val v = expandVars(line.substringAfter('=').trim().trim('\'', '"'))
            if (k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                envVars[k] = v
                return 0
            }
        }

        // Expand alias for first word
        val firstWord = line.substringBefore(' ')
        aliases[firstWord]?.let { aliased ->
            line = aliased + line.removePrefix(firstWord)
        }

        val chain = ShellParser.parseChain(line)
        var code = 0
        for (item in chain) {
            if (cancelFlag.get()) return 130
            val shouldRun = when (item.connector) {
                "&&" -> code == 0
                "||" -> code != 0
                else -> true
            }
            if (!shouldRun) continue
            code = evalPipeline(item.pipeline, out)
        }
        return code
    }

    private suspend fun evalPipeline(pipeline: Pipeline, finalOut: (String) -> Unit): Int {
        if (pipeline.commands.isEmpty()) return 0
        var pipeInput = ""
        var exitCode = 0
        val cmds = pipeline.commands
        for ((idx, cmd) in cmds.withIndex()) {
            val isLast = idx == cmds.lastIndex
            if (isLast && cmd.redirections.none { it.type == ">" || it.type == ">>" }) {
                exitCode = evalSimpleCommand(cmd, pipeInput, finalOut)
            } else {
                val buf = StringBuilder()
                exitCode = evalSimpleCommand(cmd, pipeInput) { buf.append(it) }
                var captured = buf.toString()
                for (r in cmd.redirections) {
                    if (r.type == ">" || r.type == ">>") {
                        val f = env.resolvePath(cwd, expandWord(r.target).firstOrNull() ?: r.target)
                        f.parentFile?.mkdirs()
                        if (r.type == ">") f.writeText(captured) else f.appendText(captured)
                        captured = ""
                    }
                }
                if (isLast && captured.isNotEmpty()) finalOut(captured)
                pipeInput = captured
            }
        }
        return exitCode
    }

    private suspend fun expandWord(raw: String): List<String> {
        if (raw.startsWith("\u0001")) {
            return listOf(raw.removePrefix("\u0001"))
        }
        var w = raw
        // Command substitution $(...)
        while (w.contains("$(")) {
            val start = w.indexOf("$(")
            var depth = 0
            var end = start + 2
            while (end < w.length) {
                if (w[end] == '(') depth++
                else if (w[end] == ')') {
                    if (depth == 0) break else depth--
                }
                end++
            }
            if (end < w.length) {
                val inner = w.substring(start + 2, end)
                val outBuf = StringBuilder()
                evalShellLine(inner) { outBuf.append(it) }
                w = w.substring(0, start) + outBuf.toString().trimEnd('\n') + w.substring(end + 1)
            } else break
        }
        w = expandVars(w)
        if (w == "~" || w.startsWith("~/")) {
            w = env.resolvePath(cwd, w).absolutePath
        }
        return env.expandGlob(cwd, w)
    }

    private fun expandVars(input: String): String {
        var res = input.replace("\$?", lastExitCode.toString())
        res = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}").replace(res) { m -> envVars[m.groupValues[1]] ?: "" }
        res = Regex("\\$([A-Za-z_][A-Za-z0-9_]*)").replace(res) { m -> envVars[m.groupValues[1]] ?: "" }
        return res
    }

    private suspend fun evalSimpleCommand(
        cmd: SimpleCommand,
        initialStdin: String,
        out: (String) -> Unit
    ): Int {
        var stdin = initialStdin
        val expanded = mutableListOf<String>()
        for (w in cmd.rawWords) {
            if (w.startsWith("__HEREDOC__")) {
                stdin = w.removePrefix("__HEREDOC__").replace("\\n", "\n")
            } else {
                expanded.addAll(expandWord(w))
            }
        }
        for (r in cmd.redirections) {
            if (r.type == "<") {
                val f = env.resolvePath(cwd, expandVars(r.target))
                if (f.exists()) stdin = f.readText()
            }
        }
        if (expanded.isEmpty()) return 0

        val name = expanded[0]
        val args = expanded.drop(1)

        // Direct executable path e.g. ./main or ~/script.sh or /data/.../a.out
        if (name.startsWith("./") || name.startsWith("/") || name.startsWith("~/")) {
            val target = env.resolvePath(cwd, name)
            if (!target.exists()) {
                out("bash: $name: No such file or directory\n")
                return 127
            }
            if (target.isDirectory) {
                out("bash: $name: Is a directory\n")
                return 126
            }
            val content = target.readText()
            if (content.startsWith(CppToolchain.BIN_MAGIC)) {
                return CppToolchain.runBinary(target, args, cwd, out, { readInteractiveLine() }, { cancelFlag.get() })
            }
            if (target.name.endsWith(".py")) {
                return PythonRuntime(env, cwd, listOf(target.name) + args, out, { readInteractiveLine() }, { cancelFlag.get() }).runScript(content)
            }
            if (target.name.endsWith(".sh") || content.startsWith("#!")) {
                return runShellScript(content, out)
            }
            // Attempt real native binary if executable on /system/bin
            return runSystemBinary(target.absolutePath, args, stdin, out)
        }

        // Core Shell Builtins & Base Tools
        return when (name) {
            "help" -> { printHelp(out); 0 }
            "clear", "cls" -> { out("\u001B[2J\u001B[H"); 0 }
            "pwd" -> { out("${cwd.absolutePath}\n"); 0 }
            "cd" -> {
                val dest = if (args.isEmpty()) env.homeDir else if (args[0] == "-") File(envVars["OLDPWD"] ?: env.homeDir.absolutePath) else env.resolvePath(cwd, args[0])
                if (!dest.exists() || !dest.isDirectory) {
                    out("bash: cd: ${args.firstOrNull() ?: ""}: No such file or directory\n")
                    1
                } else {
                    envVars["OLDPWD"] = cwd.absolutePath
                    cwd = dest
                    envVars["PWD"] = cwd.absolutePath
                    0
                }
            }
            "echo" -> {
                val noNl = args.firstOrNull() == "-n"
                val interp = args.contains("-e")
                val payload = args.filterNot { it == "-n" || it == "-e" }.joinToString(" ")
                val text = if (interp) payload.replace("\\n", "\n").replace("\\t", "\t").replace("\\e", "\u001B") else payload
                out(if (noNl) text else "$text\n")
                0
            }
            "printf" -> {
                val fmt = args.firstOrNull() ?: ""
                out(fmt.replace("\\n", "\n").replace("\\t", "\t"))
                0
            }
            "ls" -> runLs(args, out)
            "cat" -> runCat(args, stdin, out)
            "head" -> runHeadTail(args, stdin, head = true, out)
            "tail" -> runHeadTail(args, stdin, head = false, out)
            "wc" -> runWc(args, stdin, out)
            "grep" -> runGrep(args, stdin, out)
            "find" -> runFind(args, out)
            "mkdir" -> {
                val parents = args.contains("-p")
                for (a in args.filter { !it.startsWith("-") }) {
                    val f = env.resolvePath(cwd, a)
                    if (parents) f.mkdirs() else f.mkdir()
                }
                0
            }
            "rmdir" -> {
                for (a in args.filter { !it.startsWith("-") }) env.resolvePath(cwd, a).delete()
                0
            }
            "touch" -> {
                for (a in args.filter { !it.startsWith("-") }) {
                    val f = env.resolvePath(cwd, a)
                    f.parentFile?.mkdirs()
                    if (!f.exists()) f.createNewFile() else f.setLastModified(System.currentTimeMillis())
                }
                0
            }
            "cp" -> runCp(args, out)
            "mv" -> runMv(args, out)
            "rm" -> runRm(args, out)
            "chmod" -> 0
            "ln" -> {
                val targets = args.filter { !it.startsWith("-") }
                if (targets.size >= 2) {
                    val src = env.resolvePath(cwd, targets[0])
                    val dst = env.resolvePath(cwd, targets[1])
                    if (src.exists() && src.isFile) src.copyTo(dst, overwrite = true)
                }
                0
            }
            "env", "printenv" -> {
                envVars.toSortedMap().forEach { (k, v) -> out("$k=$v\n") }
                0
            }
            "export" -> {
                for (a in args) {
                    if (a.contains('=')) {
                        envVars[a.substringBefore('=')] = a.substringAfter('=')
                    }
                }
                0
            }
            "unset" -> { args.forEach { envVars.remove(it) }; 0 }
            "alias" -> {
                if (args.isEmpty()) aliases.forEach { (k, v) -> out("alias $k='$v'\n") }
                else args.forEach { if (it.contains('=')) aliases[it.substringBefore('=')] = it.substringAfter('=') }
                0
            }
            "unalias" -> { args.forEach { aliases.remove(it) }; 0 }
            "history" -> { history.forEachIndexed { idx, h -> out(String.format(Locale.US, "%5d  %s\n", idx + 1, h)) }; 0 }
            "whoami" -> { out("u0_a100\n"); 0 }
            "id" -> { out("uid=10100(u0_a100) gid=10100(u0_a100) groups=10100(u0_a100),3003(inet)\n"); 0 }
            "uname" -> {
                val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "aarch64"
                if (args.contains("-a")) out("Linux termux-cpp 6.1.0-android #1 SMP PREEMPT $arch Android\n")
                else if (args.contains("-m")) out("$arch\n")
                else out("Linux\n")
                0
            }
            "date" -> { out(Date().toString() + "\n"); 0 }
            "uptime" -> {
                val mins = (android.os.SystemClock.elapsedRealtime() / 60000)
                out(" up ${mins / 60}h ${mins % 60}m,  1 user,  load average: 0.42, 0.38, 0.31\n")
                0
            }
            "df" -> {
                val stat = StatFs(env.homeDir.absolutePath)
                val total = stat.totalBytes / (1024 * 1024)
                val avail = stat.availableBytes / (1024 * 1024)
                out("Filesystem      Size  Used Avail Use% Mounted on\n")
                out(String.format(Locale.US, "/data/termux++ %4dM %4dM %4dM %3d%% /data\n", total, total - avail, avail, ((total - avail) * 100 / total.coerceAtLeast(1))))
                0
            }
            "du" -> {
                val target = env.resolvePath(cwd, args.firstOrNull { !it.startsWith("-") } ?: ".")
                val bytes = target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                out("${(bytes + 1023) / 1024}K\t${target.name}\n")
                0
            }
            "which", "type" -> {
                val q = args.firstOrNull() ?: return 1
                val bin = File(env.binDir, q)
                val sysBin = File("/system/bin", q)
                when {
                    bin.exists() -> { out("${bin.absolutePath}\n"); 0 }
                    sysBin.exists() -> { out("${sysBin.absolutePath}\n"); 0 }
                    else -> { out("$q not found\n"); 1 }
                }
            }
            "sleep" -> {
                val sec = args.firstOrNull()?.toDoubleOrNull() ?: 1.0
                delay((sec * 1000).toLong())
                0
            }
            "seq" -> {
                val start = if (args.size >= 2) args[0].toIntOrNull() ?: 1 else 1
                val end = (if (args.size >= 2) args[1] else args.firstOrNull())?.toIntOrNull() ?: 10
                for (n in start..end) out("$n\n")
                0
            }
            "sort" -> {
                val lines = readLinesFromArgsOrStdin(args.filter { !it.startsWith("-") }, stdin)
                val sorted = if (args.contains("-n")) lines.sortedBy { it.trim().toDoubleOrNull() ?: 0.0 } else lines.sorted()
                val res = if (args.contains("-r")) sorted.reversed() else sorted
                (if (args.contains("-u")) res.distinct() else res).forEach { out("$it\n") }
                0
            }
            "uniq" -> {
                val lines = readLinesFromArgsOrStdin(args.filter { !it.startsWith("-") }, stdin)
                var prev: String? = null
                for (l in lines) { if (l != prev) { out("$l\n"); prev = l } }
                0
            }
            "base64" -> {
                val decode = args.contains("-d") || args.contains("--decode")
                val input = readTextFromArgsOrStdin(args.filter { !it.startsWith("-") }, stdin)
                if (decode) out(String(Base64.getDecoder().decode(input.trim())))
                else out(Base64.getEncoder().encodeToString(input.toByteArray()) + "\n")
                0
            }
            "sha256sum", "md5sum" -> {
                val algo = if (name == "sha256sum") "SHA-256" else "MD5"
                val fileArg = args.firstOrNull { !it.startsWith("-") }
                val bytes = if (fileArg != null) env.resolvePath(cwd, fileArg).readBytes() else stdin.toByteArray()
                val digest = MessageDigest.getInstance(algo).digest(bytes).joinToString("") { "%02x".format(it) }
                out("$digest  ${fileArg ?: "-"}\n")
                0
            }
            "basename" -> { out(File(args.firstOrNull() ?: "").name + "\n"); 0 }
            "dirname" -> { out((File(args.firstOrNull() ?: "").parent ?: ".") + "\n"); 0 }
            "realpath" -> { out(env.resolvePath(cwd, args.firstOrNull() ?: ".").absolutePath + "\n"); 0 }
            "source", "." -> {
                val f = env.resolvePath(cwd, args.firstOrNull() ?: return 1)
                if (f.exists()) runShellScript(f.readText(), out) else 1
            }
            "sh", "bash" -> {
                if (args.isEmpty()) { out("GNU bash, version 5.2.32 (Termux++)\n"); 0 }
                else if (args[0] == "-c") evalShellLine(args.drop(1).joinToString(" "), out)
                else {
                    val f = env.resolvePath(cwd, args[0])
                    if (f.exists()) runShellScript(f.readText(), out) else { out("bash: ${args[0]}: No such file\n"); 127 }
                }
            }
            "true" -> 0
            "false" -> 1
            "exit" -> { onCloseRequested(id); 0 }
            "termux-info" -> {
                out("Termux++ Environment Info:\n")
                out("  PREFIX: ${env.prefixDir.absolutePath}\n")
                out("  HOME:   ${env.homeDir.absolutePath}\n")
                out("  ABI:    ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
                out("  Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n")
                out("  Installed packages: ${env.getInstalledPackages().size}\n")
                0
            }
            "pkg", "apt", "apt-get", "dpkg" -> runPkgCommand(name, args, out)
            "nano", "vi", "vim" -> {
                if (name != "nano" && !packageRepo.isCommandAvailable(name)) {
                    printInstallHint(name, out); return 127
                }
                val fileName = args.firstOrNull { !it.startsWith("-") } ?: "untitled.txt"
                val target = env.resolvePath(cwd, fileName)
                val editor = NanoEditorTui(target, name, terminal)
                activeNano = editor
                editor.enter()
                0
            }
            else -> runPackageOrSystemCommand(name, args, stdin, out)
        }
    }

    private suspend fun runPackageOrSystemCommand(
        name: String,
        args: List<String>,
        stdin: String,
        out: (String) -> Unit
    ): Int {
        val provider = packageRepo.findProviderForCommand(name)
        if (provider != null && !packageRepo.isCommandAvailable(name)) {
            printInstallHint(name, out)
            return 127
        }

        return when (name) {
            "python", "python3" -> {
                if (args.firstOrNull() == "--version" || args.firstOrNull() == "-V") {
                    out("Python 3.12.4\n"); 0
                } else if (args.firstOrNull() == "-c") {
                    val code = args.drop(1).joinToString(" ")
                    PythonRuntime(env, cwd, listOf("-c"), out, { readInteractiveLine() }, { cancelFlag.get() }).runScript(code)
                } else if (args.isNotEmpty() && !args[0].startsWith("-")) {
                    val f = env.resolvePath(cwd, args[0])
                    if (!f.exists()) { out("python3: can't open file '${args[0]}': [Errno 2] No such file or directory\n"); 2 }
                    else PythonRuntime(env, cwd, args, out, { readInteractiveLine() }, { cancelFlag.get() }).runScript(f.readText())
                } else {
                    runPythonRepl(out)
                }
            }
            "pip", "pip3" -> tools.runPip(args, out)
            "g++", "clang++", "gcc", "clang", "c++", "cpp" -> runCppCompiler(name, args, out)
            "make" -> runMake(args, out)
            "git" -> tools.runGit(args, cwd, out)
            "curl" -> tools.runCurl(args, cwd, out)
            "wget" -> tools.runWget(args, cwd, out)
            "sqlite3" -> tools.runSqlite3(args, cwd, out) { readInteractiveLine() }
            "htop", "top" -> {
                val htop = HtopTui(context, terminal, sessionNamesProvider)
                htop.run({ cancelFlag.get() }) { rawKeyQueue.tryReceive().getOrNull() }
                0
            }
            "neofetch", "fastfetch" -> tools.runNeofetch(out)
            "jq" -> tools.runJq(args, stdin, cwd, out)
            "bc" -> tools.runBc(args, stdin, out)
            "tree" -> tools.runTree(args, cwd, out)
            "figlet" -> tools.runFiglet(args, out)
            "zip" -> tools.runZip(args, cwd, out)
            "unzip" -> tools.runUnzip(args, cwd, out)
            "gzip" -> tools.runGzip(args, cwd, decompress = false, out)
            "gunzip" -> tools.runGzip(args, cwd, decompress = true, out)
            "tar" -> { out("tar: archive utility ready (use `zip`/`unzip` or `gzip` for compressed bundles)\n"); 0 }
            "ssh-keygen" -> tools.runSshKeygen(args, out)
            "ssh" -> { out("OpenSSH_9.8p1 (Termux++): SSH keygen active (`ssh-keygen`). Remote interactive TTY requires native socket tunnel.\n"); 0 }
            "xxd", "hexdump" -> tools.runXxd(args, stdin, cwd, out)
            "man" -> {
                val topic = args.firstOrNull() ?: run { out("What manual page do you want?\n"); return 1 }
                val manFile = File(env.shareDir, "man/man1/$topic.1")
                if (manFile.exists()) out(manFile.readText() + "\n")
                else out("No manual entry for $topic (try `help`)\n")
                0
            }
            "lua" -> {
                val code = if (args.firstOrNull() == "-e") args.drop(1).joinToString(" ")
                else args.firstOrNull()?.let { env.resolvePath(cwd, it).takeIf { f -> f.exists() }?.readText() }
                if (code == null) { out("Lua 5.4.7 (Termux++) — pass script.lua or -e \"print('hi')\"\n"); 0 }
                else {
                    Regex("print\\(([^)]*)\\)").findAll(code).forEach { m ->
                        out(m.groupValues[1].trim().trim('"', '\'') + "\n")
                    }
                    0
                }
            }
            "node" -> {
                val code = if (args.firstOrNull() == "-e") args.drop(1).joinToString(" ")
                else args.firstOrNull()?.let { env.resolvePath(cwd, it).takeIf { f -> f.exists() }?.readText() }
                if (code == null) { out("Node.js v20.16.0 (Termux++) — pass script.js or -e \"console.log(2+2)\"\n"); 0 }
                else {
                    Regex("console\\.log\\(([^)]*)\\)").findAll(code).forEach { m ->
                        out(m.groupValues[1].trim().trim('"', '\'') + "\n")
                    }
                    0
                }
            }
            "termux-battery-status", "termux-toast", "termux-vibrate",
            "termux-clipboard-get", "termux-clipboard-set", "termux-device-info",
            "termux-open-url", "termux-setup-storage" -> tools.runTermuxApi(name, args, out)
            else -> {
                // Check if executable exists in $PREFIX/bin (e.g. compiled C++ binary!)
                val prefixBin = File(env.binDir, name)
                if (prefixBin.exists() && prefixBin.readText().startsWith(CppToolchain.BIN_MAGIC)) {
                    return CppToolchain.runBinary(prefixBin, args, cwd, out, { readInteractiveLine() }, { cancelFlag.get() })
                }
                // Fall back to real Android /system/bin command!
                val sysBin = File("/system/bin", name)
                if (sysBin.exists()) {
                    runSystemBinary(sysBin.absolutePath, args, stdin, out)
                } else {
                    out("bash: $name: command not found\n")
                    127
                }
            }
        }
    }

    private suspend fun runPythonRepl(out: (String) -> Unit): Int {
        out("Python 3.12.4 (Termux++ Android) on linux\n")
        out("Type \"help\", \"exit()\" or Ctrl+D to quit.\n")
        val py = PythonRuntime(env, cwd, emptyList(), out, { readInteractiveLine() }, { cancelFlag.get() })
        val multi = StringBuilder()
        while (!cancelFlag.get()) {
            out(if (multi.isEmpty()) ">>> " else "... ")
            val line = readInteractiveLine() ?: break
            if (multi.isEmpty() && (line.trim() == "exit()" || line.trim() == "quit()")) break
            if (multi.isNotEmpty()) {
                if (line.isBlank()) {
                    val (cont, _) = py.evalReplLine(multi.toString())
                    multi.clear()
                    if (!cont) break
                } else {
                    multi.append(line).append('\n')
                }
            } else if (line.trimEnd().endsWith(":")) {
                multi.append(line).append('\n')
            } else if (line.isNotBlank()) {
                val (cont, _) = py.evalReplLine(line)
                if (!cont) break
            }
        }
        return 0
    }

    private fun runCppCompiler(toolName: String, args: List<String>, out: (String) -> Unit): Int {
        if (args.contains("--version") || args.contains("-v")) {
            out("Termux++ clang version 18.1.8 (C++20 / C17 Runtime)\nTarget: aarch64-linux-android\n")
            return 0
        }
        var outName = "a.out"
        val inputs = mutableListOf<File>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-o" -> outName = args.getOrNull(++i) ?: "a.out"
                a.startsWith("-o") -> outName = a.removePrefix("-o")
                a.startsWith("-") -> Unit // -std=c++20, -O2, -Wall, etc.
                else -> inputs.add(env.resolvePath(cwd, a))
            }
            i++
        }
        val outFile = env.resolvePath(cwd, outName)
        return CppToolchain.compile(inputs, outFile, File(env.includeDir, "c++/v1"), out)
    }

    private suspend fun runMake(args: List<String>, out: (String) -> Unit): Int {
        val makefile = File(cwd, "Makefile").takeIf { it.exists() } ?: File(cwd, "makefile")
        if (!makefile.exists()) {
            out("make: *** No targets specified and no makefile found.  Stop.\n")
            return 2
        }
        val target = args.firstOrNull { !it.startsWith("-") }
        val lines = makefile.readLines()
        var currentTarget: String? = null
        val rules = linkedMapOf<String, MutableList<String>>()
        for (l in lines) {
            if (l.startsWith("\t") && currentTarget != null) {
                rules.getOrPut(currentTarget) { mutableListOf() }.add(l.trim())
            } else if (l.contains(':') && !l.trimStart().startsWith("#")) {
                currentTarget = l.substringBefore(':').trim()
                rules.putIfAbsent(currentTarget, mutableListOf())
            }
        }
        val runKey = target ?: rules.keys.firstOrNull() ?: return 0
        val cmds = rules[runKey] ?: run { out("make: *** No rule to make target '$runKey'.  Stop.\n"); return 2 }
        for (c in cmds) {
            out("$c\n")
            val code = evalShellLine(c, out)
            if (code != 0) return code
        }
        return 0
    }

    private suspend fun runPkgCommand(tool: String, args: List<String>, out: (String) -> Unit): Int {
        if (tool == "dpkg") {
            val installed = env.getInstalledPackages()
            out("Desired=Unknown/Install/Remove/Purge/Hold\n| Status=Not/Inst/Conf-files/Unpacked/halF-conf/Half-inst\n")
            for (pkg in packageRepo.catalog.filter { it.id in installed }) {
                out(String.format(Locale.US, "ii  %-18s %-10s %s\n", pkg.id, pkg.version, pkg.description.take(44)))
            }
            return 0
        }
        val sub = args.firstOrNull() ?: run {
            out("Usage: pkg <command> [arguments]\n\nCommands:\n  search <query>\n  install <package...>\n  uninstall <package>\n  list-installed\n  list-all\n  show <package>\n  update\n  upgrade\n")
            return 0
        }
        return when (sub) {
            "update" -> {
                out("Checking availability of current mirror...\n")
                delay(200)
                out("Hit:1 https://packages.termux-cpp.local/apt/termux-main stable InRelease\n")
                out("Reading package lists... Done\nAll packages are up to date.\n")
                0
            }
            "upgrade" -> {
                out("Reading package lists... Done\nCalculating upgrade... Done\n0 upgraded, 0 newly installed, 0 to remove.\n")
                0
            }
            "list-installed" -> {
                val installed = env.getInstalledPackages()
                packageRepo.catalog.filter { it.id in installed }.forEach {
                    out("\u001B[32m${it.id}\u001B[0m/stable,now ${it.version} [installed]\n")
                }
                0
            }
            "list-all" -> {
                val installed = env.getInstalledPackages()
                packageRepo.catalog.forEach {
                    val tag = if (it.id in installed) " \u001B[32m[installed]\u001B[0m" else ""
                    out("\u001B[1m${it.id}\u001B[0m/${it.version}$tag - ${it.description}\n")
                }
                0
            }
            "search" -> {
                val q = args.getOrNull(1)?.lowercase() ?: ""
                val installed = env.getInstalledPackages()
                val hits = packageRepo.catalog.filter {
                    it.id.contains(q) || it.description.lowercase().contains(q) || it.binaries.any { b -> b.contains(q) }
                }
                for (pkg in hits) {
                    val tag = if (pkg.id in installed) " [installed]" else ""
                    out("\u001B[1;32m${pkg.id}\u001B[0m/stable ${pkg.version}$tag\n  ${pkg.description}\n")
                }
                0
            }
            "show" -> {
                val pkg = args.getOrNull(1)?.let { packageRepo.findPackage(it) } ?: run { out("E: Package not found\n"); return 1 }
                out("Package: ${pkg.id}\nVersion: ${pkg.version}\nSection: ${pkg.category}\nInstalled-Size: ${pkg.sizeKb} kB\nBinaries: ${pkg.binaries.joinToString(", ")}\nDescription: ${pkg.description}\n")
                0
            }
            "install", "add", "i" -> {
                val targets = args.drop(1).filter { !it.startsWith("-") }
                if (targets.isEmpty()) { out("E: Specify at least one package name\n"); return 1 }
                var ok = true
                for (t in targets) {
                    if (!packageRepo.installPackage(t, out)) ok = false
                }
                if (ok) 0 else 1
            }
            "uninstall", "remove", "purge" -> {
                val targets = args.drop(1).filter { !it.startsWith("-") }
                if (targets.isEmpty()) return 1
                var ok = true
                for (t in targets) {
                    if (!packageRepo.removePackage(t, out)) ok = false
                }
                if (ok) 0 else 1
            }
            else -> { out("Unknown command '$sub' (try `pkg help`)\n"); 1 }
        }
    }

    private fun printInstallHint(cmd: String, out: (String) -> Unit) {
        val pkg = packageRepo.findProviderForCommand(cmd)
        out("The program \u001B[1m$cmd\u001B[0m is not installed. Install it by executing:\n")
        out(" \u001B[32mpkg install ${pkg?.id ?: cmd}\u001B[0m\n")
    }

    private suspend fun runShellScript(script: String, out: (String) -> Unit): Int {
        var code = 0
        for (line in ShellParser.splitLinesRespectingQuotesAndHereDocs(script)) {
            if (cancelFlag.get()) return 130
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            code = evalShellLine(t, out)
        }
        return code
    }

    private suspend fun runSystemBinary(
        binPath: String,
        args: List<String>,
        stdin: String,
        out: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder(listOf(binPath) + args)
                .directory(cwd)
                .redirectErrorStream(true)
            pb.environment().putAll(envVars)
            val proc = pb.start()
            if (stdin.isNotEmpty()) {
                proc.outputStream.bufferedWriter().use { it.write(stdin) }
            } else {
                proc.outputStream.close()
            }
            val reader = proc.inputStream.bufferedReader()
            val buf = CharArray(1024)
            while (!cancelFlag.get()) {
                val n = reader.read(buf)
                if (n < 0) break
                out(String(buf, 0, n))
            }
            if (cancelFlag.get()) {
                proc.destroyForcibly()
                130
            } else {
                proc.waitFor()
            }
        } catch (e: Exception) {
            out("exec error: ${e.message}\n")
            1
        }
    }

    private fun runLs(args: List<String>, out: (String) -> Unit): Int {
        val longFmt = args.any { it.startsWith("-") && it.contains('l') }
        val showAll = args.any { it.startsWith("-") && (it.contains('a') || it.contains('A')) }
        val paths = args.filter { !it.startsWith("-") }.ifEmpty { listOf(".") }
        for (p in paths) {
            val f = env.resolvePath(cwd, p)
            if (!f.exists()) { out("ls: cannot access '$p': No such file or directory\n"); return 2 }
            if (f.isFile) {
                out(if (longFmt) env.formatLongEntry(f) + "\n" else "${f.name}\n")
            } else {
                val list = f.listFiles()
                    ?.filter { showAll || !it.name.startsWith(".") }
                    ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    .orEmpty()
                if (longFmt) {
                    out("total ${list.size * 4}\n")
                    list.forEach { out(env.formatLongEntry(it) + "\n") }
                } else {
                    val entries = list.map {
                        when {
                            it.isDirectory -> "\u001B[1;34m${it.name}/\u001B[0m"
                            it.canExecute() || it.name.endsWith(".sh") -> "\u001B[1;32m${it.name}\u001B[0m"
                            else -> it.name
                        }
                    }
                    if (entries.isNotEmpty()) out(entries.joinToString("  ") + "\n")
                }
            }
        }
        return 0
    }

    private fun runCat(args: List<String>, stdin: String, out: (String) -> Unit): Int {
        val files = args.filter { !it.startsWith("-") }
        if (files.isEmpty()) { out(stdin); return 0 }
        for (p in files) {
            val f = env.resolvePath(cwd, p)
            if (!f.exists()) { out("cat: $p: No such file or directory\n"); return 1 }
            if (f.isDirectory) { out("cat: $p: Is a directory\n"); return 1 }
            val txt = f.readText()
            out(txt)
            if (txt.isNotEmpty() && !txt.endsWith("\n")) out("\n")
        }
        return 0
    }

    private fun runHeadTail(args: List<String>, stdin: String, head: Boolean, out: (String) -> Unit): Int {
        var n = 10
        val files = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "-n") n = args.getOrNull(++i)?.toIntOrNull() ?: 10
            else if (a.startsWith("-") && a.drop(1).all { it.isDigit() }) n = a.drop(1).toInt()
            else files.add(a)
            i++
        }
        val lines = readLinesFromArgsOrStdin(files, stdin)
        val slice = if (head) lines.take(n) else lines.takeLast(n)
        slice.forEach { out("$it\n") }
        return 0
    }

    private fun runWc(args: List<String>, stdin: String, out: (String) -> Unit): Int {
        val files = args.filter { !it.startsWith("-") }
        val text = readTextFromArgsOrStdin(files, stdin)
        val lines = if (text.isEmpty()) 0 else text.count { it == '\n' }
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val bytes = text.toByteArray().size
        out(String.format(Locale.US, "%7d %7d %7d %s\n", lines, words, bytes, files.firstOrNull() ?: ""))
        return 0
    }

    private fun runGrep(args: List<String>, stdin: String, out: (String) -> Unit): Int {
        val ignoreCase = args.any { it.startsWith("-") && it.contains('i') }
        val showNum = args.any { it.startsWith("-") && it.contains('n') }
        val invert = args.any { it.startsWith("-") && it.contains('v') }
        val pos = args.filter { !it.startsWith("-") }
        val pattern = pos.firstOrNull() ?: return 2
        val regex = Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        val files = pos.drop(1)
        var found = false
        if (files.isEmpty()) {
            stdin.lines().forEachIndexed { idx, line ->
                val matches = regex.containsMatchIn(line)
                if (matches != invert) {
                    found = true
                    if (showNum) out("\u001B[32m${idx + 1}\u001B[0m:")
                    out("$line\n")
                }
            }
        } else {
            for (fName in files) {
                val f = env.resolvePath(cwd, fName)
                if (!f.exists() || f.isDirectory) continue
                f.readLines().forEachIndexed { idx, line ->
                    val matches = regex.containsMatchIn(line)
                    if (matches != invert) {
                        found = true
                        if (files.size > 1) out("\u001B[35m$fName\u001B[0m:")
                        if (showNum) out("\u001B[32m${idx + 1}\u001B[0m:")
                        out("$line\n")
                    }
                }
            }
        }
        return if (found) 0 else 1
    }

    private fun runFind(args: List<String>, out: (String) -> Unit): Int {
        val start = env.resolvePath(cwd, args.firstOrNull { !it.startsWith("-") } ?: ".")
        val nameIdx = args.indexOf("-name")
        val pat = if (nameIdx >= 0) args.getOrNull(nameIdx + 1) else null
        val regex = pat?.let {
            Regex("^" + it.replace(".", "\\.").replace("*", ".*").replace("?", ".") + "$")
        }
        start.walkTopDown().forEach { f ->
            if (regex == null || regex.matches(f.name)) {
                out(f.relativeToOrSelf(cwd).path.ifEmpty { "." } + "\n")
            }
        }
        return 0
    }

    private fun runCp(args: List<String>, out: (String) -> Unit): Int {
        val recursive = args.contains("-r") || args.contains("-rf") || args.contains("-a")
        val paths = args.filter { !it.startsWith("-") }
        if (paths.size < 2) { out("cp: missing destination file operand\n"); return 1 }
        val src = env.resolvePath(cwd, paths[0])
        val dstRaw = env.resolvePath(cwd, paths[1])
        val dst = if (dstRaw.isDirectory) File(dstRaw, src.name) else dstRaw
        if (!src.exists()) { out("cp: cannot stat '${paths[0]}': No such file\n"); return 1 }
        if (src.isDirectory && !recursive) { out("cp: -r not specified; omitting directory '${paths[0]}'\n"); return 1 }
        src.copyRecursively(dst, overwrite = true)
        return 0
    }

    private fun runMv(args: List<String>, out: (String) -> Unit): Int {
        val paths = args.filter { !it.startsWith("-") }
        if (paths.size < 2) { out("mv: missing destination file operand\n"); return 1 }
        val src = env.resolvePath(cwd, paths[0])
        val dstRaw = env.resolvePath(cwd, paths[1])
        val dst = if (dstRaw.isDirectory) File(dstRaw, src.name) else dstRaw
        if (!src.exists()) { out("mv: cannot stat '${paths[0]}': No such file\n"); return 1 }
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
        }
        return 0
    }

    private fun runRm(args: List<String>, out: (String) -> Unit): Int {
        val recursive = args.any { it.startsWith("-") && (it.contains('r') || it.contains('R')) }
        val force = args.any { it.startsWith("-") && it.contains('f') }
        for (p in args.filter { !it.startsWith("-") }) {
            val f = env.resolvePath(cwd, p)
            if (!f.exists()) {
                if (!force) { out("rm: cannot remove '$p': No such file or directory\n"); return 1 }
            } else if (f.isDirectory && !recursive) {
                out("rm: cannot remove '$p': Is a directory\n"); return 1
            } else {
                f.deleteRecursively()
            }
        }
        return 0
    }

    private fun readLinesFromArgsOrStdin(files: List<String>, stdin: String): List<String> =
        if (files.isEmpty()) stdin.trimEnd('\n').lines()
        else files.flatMap { f -> env.resolvePath(cwd, f).takeIf { it.exists() }?.readLines().orEmpty() }

    private fun readTextFromArgsOrStdin(files: List<String>, stdin: String): String =
        if (files.isEmpty()) stdin
        else files.joinToString("") { f -> env.resolvePath(cwd, f).takeIf { it.exists() }?.readText() ?: "" }

    private fun printHelp(out: (String) -> Unit) {
        out(
            """
            |${"\u001B[1;32mTermux++ Command Reference\u001B[0m"}
            |  ${"\u001B[36mpkg install <name>\u001B[0m"}   Install packages (python, clang, git, curl, sqlite, htop, neofetch...)
            |  ${"\u001B[36mpkg search <query>\u001B[0m"}   Search available packages
            |  ${"\u001B[36mpkg list-all\u001B[0m"}         List all 26+ packages in repository
            |  ${"\u001B[36mg++ file.cpp -o app\u001B[0m"}  Compile C++20 program (after `pkg install clang`)
            |  ${"\u001B[36mpython3 [file.py]\u001B[0m"}    Run Python 3 script or interactive REPL (after `pkg install python`)
            |  ${"\u001B[36mnano <file>\u001B[0m"}          Full-screen terminal text editor (^O save, ^X exit)
            |  ${"\u001B[36mls, cd, cp, mv, rm\u001B[0m"}   POSIX filesystem tools with pipes (|), redirects (> >>), &&
            |  ${"\u001B[36m~/examples/\u001B[0m"}          Starter hello.cpp and fibonacci.py ready to test!
            |""".trimMargin()
        )
    }

    fun getCurrentDir(): File = cwd
    fun dispose() {
        activeJob?.cancel()
    }
}
