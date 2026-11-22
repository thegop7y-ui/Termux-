package com.example.core.shell

data class Redirection(
    val type: String, // ">", ">>", "<", "2>", "2>&1"
    val target: String
)

data class SimpleCommand(
    val rawWords: List<String>,
    val redirections: List<Redirection> = emptyList()
)

data class Pipeline(
    val commands: List<SimpleCommand>,
    val background: Boolean = false
)

data class ChainedPipeline(
    val connector: String, // "", ";", "&&", "||"
    val pipeline: Pipeline
)

object ShellParser {
    fun splitLinesRespectingQuotesAndHereDocs(script: String): List<String> {
        val result = mutableListOf<String>()
        val lines = script.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val hereDocMatch = Regex("<<\\s*'?([A-Za-z_][A-Za-z0-9_]*)'?").find(line)
            if (hereDocMatch != null) {
                val delimiter = hereDocMatch.groupValues[1]
                val body = StringBuilder()
                i++
                while (i < lines.size && lines[i].trim() != delimiter) {
                    body.append(lines[i]).append('\n')
                    i++
                }
                val encoded = body.toString().replace("\n", "\\n")
                result.add("$line __HEREDOC__${encoded}")
            } else {
                result.add(line)
            }
            i++
        }
        return result
    }

    fun parseChain(line: String): List<ChainedPipeline> {
        val tokens = tokenizeShellLine(line)
        if (tokens.isEmpty()) return emptyList()

        val chain = mutableListOf<ChainedPipeline>()
        var currentConnector = ""
        var i = 0

        while (i < tokens.size) {
            val pipelineCmds = mutableListOf<SimpleCommand>()
            var isBg = false

            while (i < tokens.size) {
                val words = mutableListOf<String>()
                val redirs = mutableListOf<Redirection>()

                while (i < tokens.size) {
                    val tk = tokens[i]
                    if (tk in listOf("|", ";", "&&", "||", "&")) break
                    if (tk in listOf(">", ">>", "<", "2>", "2>&1")) {
                        if (tk == "2>&1") {
                            redirs.add(Redirection("2>&1", ""))
                            i++
                        } else {
                            val target = tokens.getOrNull(i + 1) ?: ""
                            redirs.add(Redirection(tk, target))
                            i += 2
                        }
                    } else {
                        words.add(tk)
                        i++
                    }
                }
                if (words.isNotEmpty() || redirs.isNotEmpty()) {
                    pipelineCmds.add(SimpleCommand(words, redirs))
                }
                if (i < tokens.size && tokens[i] == "|") {
                    i++
                    continue
                }
                break
            }

            if (i < tokens.size && tokens[i] == "&") {
                isBg = true
                i++
            }
            if (pipelineCmds.isNotEmpty()) {
                chain.add(ChainedPipeline(currentConnector, Pipeline(pipelineCmds, isBg)))
            }
            if (i < tokens.size && tokens[i] in listOf(";", "&&", "||")) {
                currentConnector = tokens[i]
                i++
            } else {
                currentConnector = ";"
            }
        }
        return chain
    }

    fun tokenizeShellLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var hasToken = false
        var i = 0
        var inSingle = false
        var inDouble = false

        fun flush() {
            if (hasToken || cur.isNotEmpty()) {
                out.add(cur.toString())
                cur.clear()
                hasToken = false
            }
        }

        while (i < line.length) {
            val ch = line[i]
            when {
                inSingle -> {
                    if (ch == '\'') inSingle = false else cur.append(ch)
                    hasToken = true
                    i++
                }
                inDouble -> {
                    when (ch) {
                        '"' -> inDouble = false
                        '\\' -> {
                            val next = line.getOrNull(i + 1)
                            if (next != null) { cur.append(next); i++ }
                        }
                        else -> cur.append(ch)
                    }
                    hasToken = true
                    i++
                }
                ch == '#' && !hasToken && cur.isEmpty() -> break
                ch.isWhitespace() -> {
                    flush()
                    i++
                }
                ch == '\'' -> {
                    inSingle = true
                    hasToken = true
                    cur.append('\u0001') // marker to keep literal vs glob
                    i++
                }
                ch == '"' -> {
                    inDouble = true
                    hasToken = true
                    i++
                }
                ch == '\\' -> {
                    val next = line.getOrNull(i + 1)
                    if (next != null) { cur.append(next); hasToken = true; i += 2 } else i++
                }
                ch == '$' && line.getOrNull(i + 1) == '(' -> {
                    var depth = 0
                    val start = i
                    while (i < line.length) {
                        if (line[i] == '(') depth++
                        else if (line[i] == ')') {
                            depth--
                            if (depth == 0) { i++; break }
                        }
                        i++
                    }
                    cur.append(line.substring(start, i))
                    hasToken = true
                }
                line.startsWith("2>&1", i) -> {
                    flush(); out.add("2>&1"); i += 4
                }
                line.startsWith("2>", i) -> {
                    flush(); out.add("2>"); i += 2
                }
                line.startsWith(">>", i) -> {
                    flush(); out.add(">>"); i += 2
                }
                line.startsWith("&&", i) -> {
                    flush(); out.add("&&"); i += 2
                }
                line.startsWith("||", i) -> {
                    flush(); out.add("||"); i += 2
                }
                ch in listOf('|', ';', '&', '>', '<') -> {
                    flush(); out.add(ch.toString()); i++
                }
                else -> {
                    cur.append(ch)
                    hasToken = true
                    i++
                }
            }
        }
        flush()
        return out
    }
}
