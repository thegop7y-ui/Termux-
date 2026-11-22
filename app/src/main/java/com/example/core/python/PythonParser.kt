package com.example.core.python

class PySyntaxException(val line: Int, message: String) : Exception("SyntaxError: $message (line $line)")

enum class PyTokType {
    INDENT, DEDENT, NEWLINE, EOF,
    IDENT, INT, FLOAT, STRING, FSTRING,
    LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE,
    COLON, COMMA, DOT, ASSIGN,
    PLUS, MINUS, STAR, DOUBLE_STAR, SLASH, DOUBLE_SLASH, PERCENT,
    PLUS_EQ, MINUS_EQ, STAR_EQ, SLASH_EQ,
    EQ, NEQ, LT, GT, LE, GE,
    AND, OR, NOT, IN, IS,
    DEF, CLASS, IF, ELIF, ELSE, FOR, WHILE, BREAK, CONTINUE, RETURN, PASS,
    IMPORT, FROM, AS, TRY, EXCEPT, FINALLY, RAISE, WITH, LAMBDA, TRUE, FALSE, NONE, ASSERT, GLOBAL
}

data class PyToken(val type: PyTokType, val text: String, val line: Int)

sealed interface PyStmt {
    data class ExprStmt(val expr: PyExpr, val line: Int) : PyStmt
    data class Assign(val target: PyExpr, val op: String, val value: PyExpr, val line: Int) : PyStmt
    data class If(val branches: List<Pair<PyExpr, List<PyStmt>>>, val elseBranch: List<PyStmt>, val line: Int) : PyStmt
    data class While(val cond: PyExpr, val body: List<PyStmt>, val line: Int) : PyStmt
    data class For(val targets: List<String>, val iter: PyExpr, val body: List<PyStmt>, val line: Int) : PyStmt
    data class FuncDef(
        val name: String,
        val params: List<Pair<String, PyExpr?>>,
        val varArg: String?,
        val kwArg: String?,
        val body: List<PyStmt>,
        val line: Int
    ) : PyStmt
    data class ClassDef(val name: String, val baseName: String?, val body: List<PyStmt>, val line: Int) : PyStmt
    data class Return(val value: PyExpr?, val line: Int) : PyStmt
    data class Import(val modules: List<Pair<String, String?>>, val line: Int) : PyStmt
    data class FromImport(val module: String, val names: List<Pair<String, String?>>, val line: Int) : PyStmt
    data class TryExcept(val tryBody: List<PyStmt>, val exceptVar: String?, val exceptBody: List<PyStmt>, val finallyBody: List<PyStmt>, val line: Int) : PyStmt
    data class With(val ctxExpr: PyExpr, val alias: String?, val body: List<PyStmt>, val line: Int) : PyStmt
    data class Raise(val expr: PyExpr?, val line: Int) : PyStmt
    data class Assert(val cond: PyExpr, val msg: PyExpr?, val line: Int) : PyStmt
    data object Break : PyStmt
    data object Continue : PyStmt
    data object Pass : PyStmt
}

sealed interface PyExpr {
    data class IntLit(val value: Long) : PyExpr
    data class FloatLit(val value: Double) : PyExpr
    data class BoolLit(val value: Boolean) : PyExpr
    data class StrLit(val value: String) : PyExpr
    data class FStrLit(val raw: String) : PyExpr
    data object NoneLit : PyExpr
    data class Var(val name: String) : PyExpr
    data class ListLit(val items: List<PyExpr>) : PyExpr
    data class TupleLit(val items: List<PyExpr>) : PyExpr
    data class DictLit(val entries: List<Pair<PyExpr, PyExpr>>) : PyExpr
    data class SetLit(val items: List<PyExpr>) : PyExpr
    data class ListComp(val elem: PyExpr, val varNames: List<String>, val iter: PyExpr, val cond: PyExpr?) : PyExpr
    data class DictComp(val key: PyExpr, val value: PyExpr, val varNames: List<String>, val iter: PyExpr, val cond: PyExpr?) : PyExpr
    data class Unary(val op: String, val operand: PyExpr) : PyExpr
    data class Binary(val op: String, val left: PyExpr, val right: PyExpr) : PyExpr
    data class Ternary(val ifTrue: PyExpr, val cond: PyExpr, val ifFalse: PyExpr) : PyExpr
    data class Lambda(val params: List<String>, val body: PyExpr) : PyExpr
    data class Call(val callee: PyExpr, val posArgs: List<PyExpr>, val kwArgs: List<Pair<String, PyExpr>>) : PyExpr
    data class Attr(val target: PyExpr, val name: String) : PyExpr
    data class Index(val target: PyExpr, val index: PyExpr) : PyExpr
    data class Slice(val target: PyExpr, val start: PyExpr?, val stop: PyExpr?, val step: PyExpr?) : PyExpr
}

class PythonLexer(private val source: String) {
    private val keywords = mapOf(
        "def" to PyTokType.DEF, "class" to PyTokType.CLASS,
        "if" to PyTokType.IF, "elif" to PyTokType.ELIF, "else" to PyTokType.ELSE,
        "for" to PyTokType.FOR, "while" to PyTokType.WHILE,
        "break" to PyTokType.BREAK, "continue" to PyTokType.CONTINUE,
        "return" to PyTokType.RETURN, "pass" to PyTokType.PASS,
        "import" to PyTokType.IMPORT, "from" to PyTokType.FROM, "as" to PyTokType.AS,
        "try" to PyTokType.TRY, "except" to PyTokType.EXCEPT, "finally" to PyTokType.FINALLY,
        "raise" to PyTokType.RAISE, "with" to PyTokType.WITH, "lambda" to PyTokType.LAMBDA,
        "True" to PyTokType.TRUE, "False" to PyTokType.FALSE, "None" to PyTokType.NONE,
        "and" to PyTokType.AND, "or" to PyTokType.OR, "not" to PyTokType.NOT,
        "in" to PyTokType.IN, "is" to PyTokType.IS, "assert" to PyTokType.ASSERT,
        "global" to PyTokType.GLOBAL
    )

    fun tokenize(): List<PyToken> {
        val tokens = mutableListOf<PyToken>()
        val indentStack = ArrayDeque<Int>().apply { addLast(0) }
        var i = 0
        var line = 1
        var parenDepth = 0
        var atLineStart = true

        while (i < source.length) {
            if (atLineStart && parenDepth == 0) {
                var spaces = 0
                while (i < source.length && (source[i] == ' ' || source[i] == '\t')) {
                    spaces += if (source[i] == '\t') 4 else 1
                    i++
                }
                if (i >= source.length) break
                if (source[i] == '\r') { i++; continue }
                if (source[i] == '\n') { i++; line++; continue }
                if (source[i] == '#') {
                    while (i < source.length && source[i] != '\n') i++
                    continue
                }
                val currentIndent = indentStack.last()
                if (spaces > currentIndent) {
                    indentStack.addLast(spaces)
                    tokens.add(PyToken(PyTokType.INDENT, "", line))
                } else if (spaces < currentIndent) {
                    while (indentStack.size > 1 && indentStack.last() > spaces) {
                        indentStack.removeLast()
                        tokens.add(PyToken(PyTokType.DEDENT, "", line))
                    }
                    if (indentStack.last() != spaces) {
                        throw PySyntaxException(line, "unindent does not match any outer indentation level")
                    }
                }
                atLineStart = false
            }

            if (i >= source.length) break
            val ch = source[i]
            when {
                ch == ' ' || ch == '\t' || ch == '\r' -> i++
                ch == '\\' && i + 1 < source.length && source[i + 1] == '\n' -> { i += 2; line++ }
                ch == '#' -> while (i < source.length && source[i] != '\n') i++
                ch == '\n' -> {
                    if (parenDepth == 0 && tokens.lastOrNull()?.type != PyTokType.NEWLINE && tokens.isNotEmpty()) {
                        tokens.add(PyToken(PyTokType.NEWLINE, "\n", line))
                    }
                    i++; line++
                    atLineStart = (parenDepth == 0)
                }
                (ch == 'f' || ch == 'F' || ch == 'r' || ch == 'R') && (i + 1 < source.length && (source[i + 1] == '"' || source[i + 1] == '\'')) -> {
                    val isF = ch == 'f' || ch == 'F'
                    val isRaw = ch == 'r' || ch == 'R'
                    i++
                    val (strVal, nextIdx, newLines) = readString(i, line, isRaw)
                    i = nextIdx; line += newLines
                    tokens.add(PyToken(if (isF) PyTokType.FSTRING else PyTokType.STRING, strVal, line))
                }
                ch == '"' || ch == '\'' -> {
                    val (strVal, nextIdx, newLines) = readString(i, line, false)
                    i = nextIdx; line += newLines
                    tokens.add(PyToken(PyTokType.STRING, strVal, line))
                }
                ch.isDigit() -> {
                    val start = i
                    var isFloat = false
                    if (ch == '0' && i + 1 < source.length && (source[i + 1] == 'x' || source[i + 1] == 'X')) {
                        i += 2
                        while (i < source.length && (source[i].isDigit() || source[i] in 'a'..'f' || source[i] in 'A'..'F')) i++
                        tokens.add(PyToken(PyTokType.INT, source.substring(start, i), line))
                    } else {
                        while (i < source.length && (source[i].isDigit() || source[i] == '_')) i++
                        if (i < source.length && source[i] == '.' && i + 1 < source.length && source[i + 1].isDigit()) {
                            isFloat = true; i++
                            while (i < source.length && (source[i].isDigit() || source[i] == '_')) i++
                        }
                        if (i < source.length && (source[i] == 'e' || source[i] == 'E')) {
                            isFloat = true; i++
                            if (i < source.length && (source[i] == '+' || source[i] == '-')) i++
                            while (i < source.length && source[i].isDigit()) i++
                        }
                        val numStr = source.substring(start, i).replace("_", "")
                        tokens.add(PyToken(if (isFloat) PyTokType.FLOAT else PyTokType.INT, numStr, line))
                    }
                }
                ch.isLetter() || ch == '_' -> {
                    val start = i
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    val word = source.substring(start, i)
                    tokens.add(PyToken(keywords[word] ?: PyTokType.IDENT, word, line))
                }
                else -> {
                    val next = source.getOrNull(i + 1)
                    val tok = when {
                        ch == '*' && next == '*' -> { i += 2; PyToken(PyTokType.DOUBLE_STAR, "**", line) }
                        ch == '/' && next == '/' -> { i += 2; PyToken(PyTokType.DOUBLE_SLASH, "//", line) }
                        ch == '+' && next == '=' -> { i += 2; PyToken(PyTokType.PLUS_EQ, "+=", line) }
                        ch == '-' && next == '=' -> { i += 2; PyToken(PyTokType.MINUS_EQ, "-=", line) }
                        ch == '*' && next == '=' -> { i += 2; PyToken(PyTokType.STAR_EQ, "*=", line) }
                        ch == '/' && next == '=' -> { i += 2; PyToken(PyTokType.SLASH_EQ, "/=", line) }
                        ch == '=' && next == '=' -> { i += 2; PyToken(PyTokType.EQ, "==", line) }
                        ch == '!' && next == '=' -> { i += 2; PyToken(PyTokType.NEQ, "!=", line) }
                        ch == '<' && next == '=' -> { i += 2; PyToken(PyTokType.LE, "<=", line) }
                        ch == '>' && next == '=' -> { i += 2; PyToken(PyTokType.GE, ">=", line) }
                        ch == '(' -> { i++; parenDepth++; PyToken(PyTokType.LPAREN, "(", line) }
                        ch == ')' -> { i++; parenDepth = (parenDepth - 1).coerceAtLeast(0); PyToken(PyTokType.RPAREN, ")", line) }
                        ch == '[' -> { i++; parenDepth++; PyToken(PyTokType.LBRACKET, "[", line) }
                        ch == ']' -> { i++; parenDepth = (parenDepth - 1).coerceAtLeast(0); PyToken(PyTokType.RBRACKET, "]", line) }
                        ch == '{' -> { i++; parenDepth++; PyToken(PyTokType.LBRACE, "{", line) }
                        ch == '}' -> { i++; parenDepth = (parenDepth - 1).coerceAtLeast(0); PyToken(PyTokType.RBRACE, "}", line) }
                        ch == ':' -> { i++; PyToken(PyTokType.COLON, ":", line) }
                        ch == ',' -> { i++; PyToken(PyTokType.COMMA, ",", line) }
                        ch == '.' -> { i++; PyToken(PyTokType.DOT, ".", line) }
                        ch == '=' -> { i++; PyToken(PyTokType.ASSIGN, "=", line) }
                        ch == '+' -> { i++; PyToken(PyTokType.PLUS, "+", line) }
                        ch == '-' -> { i++; PyToken(PyTokType.MINUS, "-", line) }
                        ch == '*' -> { i++; PyToken(PyTokType.STAR, "*", line) }
                        ch == '/' -> { i++; PyToken(PyTokType.SLASH, "/", line) }
                        ch == '%' -> { i++; PyToken(PyTokType.PERCENT, "%", line) }
                        ch == '<' -> { i++; PyToken(PyTokType.LT, "<", line) }
                        ch == '>' -> { i++; PyToken(PyTokType.GT, ">", line) }
                        ch == ';' -> { i++; PyToken(PyTokType.NEWLINE, ";", line) }
                        else -> throw PySyntaxException(line, "invalid character '$ch'")
                    }
                    tokens.add(tok)
                }
            }
        }
        if (tokens.isNotEmpty() && tokens.last().type != PyTokType.NEWLINE) {
            tokens.add(PyToken(PyTokType.NEWLINE, "\n", line))
        }
        while (indentStack.size > 1) {
            indentStack.removeLast()
            tokens.add(PyToken(PyTokType.DEDENT, "", line))
        }
        tokens.add(PyToken(PyTokType.EOF, "", line))
        return tokens
    }

    private fun readString(startIdx: Int, startLine: Int, isRaw: Boolean): Triple<String, Int, Int> {
        var i = startIdx
        val quote = source[i]
        val isTriple = i + 2 < source.length && source[i + 1] == quote && source[i + 2] == quote
        i += if (isTriple) 3 else 1
        val sb = StringBuilder()
        var newLines = 0
        while (i < source.length) {
            if (isTriple) {
                if (i + 2 < source.length && source[i] == quote && source[i + 1] == quote && source[i + 2] == quote) {
                    return Triple(sb.toString(), i + 3, newLines)
                }
            } else if (source[i] == quote) {
                return Triple(sb.toString(), i + 1, newLines)
            }
            val c = source[i++]
            if (c == '\n') newLines++
            if (c == '\\' && !isRaw && i < source.length) {
                val esc = source[i++]
                when (esc) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    '\n' -> newLines++
                    else -> { sb.append('\\'); sb.append(esc) }
                }
            } else {
                sb.append(c)
            }
        }
        throw PySyntaxException(startLine, "unterminated string literal")
    }
}

class PythonParser(private val tokens: List<PyToken>) {
    private var pos = 0

    private fun cur(): PyToken = tokens.getOrElse(pos) { tokens.last() }
    private fun peek(offset: Int = 1): PyToken = tokens.getOrElse(pos + offset) { tokens.last() }
    private fun advance(): PyToken = tokens[pos++]
    private fun check(t: PyTokType): Boolean = cur().type == t
    private fun match(t: PyTokType): Boolean { if (check(t)) { advance(); return true }; return false }
    private fun expect(t: PyTokType, msg: String): PyToken {
        if (check(t)) return advance()
        throw PySyntaxException(cur().line, msg)
    }

    fun parseProgram(): List<PyStmt> {
        val stmts = mutableListOf<PyStmt>()
        while (!check(PyTokType.EOF)) {
            if (match(PyTokType.NEWLINE)) continue
            stmts.add(parseStmt())
        }
        return stmts
    }

    private fun parseBlock(): List<PyStmt> {
        expect(PyTokType.COLON, "expected ':'")
        if (!match(PyTokType.NEWLINE)) {
            val single = parseSimpleStmt()
            match(PyTokType.NEWLINE)
            return listOf(single)
        }
        while (match(PyTokType.NEWLINE)) {}
        expect(PyTokType.INDENT, "expected an indented block")
        val stmts = mutableListOf<PyStmt>()
        while (!check(PyTokType.DEDENT) && !check(PyTokType.EOF)) {
            if (match(PyTokType.NEWLINE)) continue
            stmts.add(parseStmt())
        }
        match(PyTokType.DEDENT)
        return stmts
    }

    private fun parseStmt(): PyStmt {
        val ln = cur().line
        return when (cur().type) {
            PyTokType.IF -> {
                advance()
                val branches = mutableListOf<Pair<PyExpr, List<PyStmt>>>()
                val cond = parseExpr()
                branches.add(cond to parseBlock())
                while (match(PyTokType.ELIF)) {
                    val ec = parseExpr()
                    branches.add(ec to parseBlock())
                }
                val elseBranch = if (match(PyTokType.ELSE)) parseBlock() else emptyList()
                PyStmt.If(branches, elseBranch, ln)
            }
            PyTokType.WHILE -> {
                advance()
                val cond = parseExpr()
                PyStmt.While(cond, parseBlock(), ln)
            }
            PyTokType.FOR -> {
                advance()
                val targets = mutableListOf<String>()
                do {
                    targets.add(expect(PyTokType.IDENT, "expected loop variable").text)
                } while (match(PyTokType.COMMA))
                expect(PyTokType.IN, "expected 'in'")
                val iter = parseExprOrTuple()
                PyStmt.For(targets, iter, parseBlock(), ln)
            }
            PyTokType.DEF -> {
                advance()
                val name = expect(PyTokType.IDENT, "expected function name").text
                expect(PyTokType.LPAREN, "expected '('")
                val params = mutableListOf<Pair<String, PyExpr?>>()
                var varArg: String? = null
                var kwArg: String? = null
                if (!check(PyTokType.RPAREN)) {
                    do {
                        if (check(PyTokType.RPAREN)) break
                        when {
                            match(PyTokType.DOUBLE_STAR) -> kwArg = expect(PyTokType.IDENT, "expected param name").text
                            match(PyTokType.STAR) -> varArg = expect(PyTokType.IDENT, "expected param name").text
                            else -> {
                                val pName = expect(PyTokType.IDENT, "expected param name").text
                                val defVal = if (match(PyTokType.ASSIGN)) parseExpr() else null
                                params.add(pName to defVal)
                            }
                        }
                    } while (match(PyTokType.COMMA))
                }
                expect(PyTokType.RPAREN, "expected ')'")
                PyStmt.FuncDef(name, params, varArg, kwArg, parseBlock(), ln)
            }
            PyTokType.CLASS -> {
                advance()
                val name = expect(PyTokType.IDENT, "expected class name").text
                var base: String? = null
                if (match(PyTokType.LPAREN)) {
                    if (!check(PyTokType.RPAREN)) base = expect(PyTokType.IDENT, "expected base class").text
                    expect(PyTokType.RPAREN, "expected ')'")
                }
                PyStmt.ClassDef(name, base, parseBlock(), ln)
            }
            PyTokType.TRY -> {
                advance()
                val tryBody = parseBlock()
                var exVar: String? = null
                var exBody = emptyList<PyStmt>()
                if (match(PyTokType.EXCEPT)) {
                    if (!check(PyTokType.COLON)) {
                        parseExpr()
                        if (match(PyTokType.AS)) exVar = expect(PyTokType.IDENT, "expected alias").text
                    }
                    exBody = parseBlock()
                }
                val finBody = if (match(PyTokType.FINALLY)) parseBlock() else emptyList()
                PyStmt.TryExcept(tryBody, exVar, exBody, finBody, ln)
            }
            PyTokType.WITH -> {
                advance()
                val ctx = parseExpr()
                val alias = if (match(PyTokType.AS)) expect(PyTokType.IDENT, "expected alias").text else null
                PyStmt.With(ctx, alias, parseBlock(), ln)
            }
            else -> {
                val s = parseSimpleStmt()
                match(PyTokType.NEWLINE)
                s
            }
        }
    }

    private fun parseSimpleStmt(): PyStmt {
        val ln = cur().line
        return when (cur().type) {
            PyTokType.RETURN -> {
                advance()
                val v = if (check(PyTokType.NEWLINE) || check(PyTokType.EOF)) null else parseExprOrTuple()
                PyStmt.Return(v, ln)
            }
            PyTokType.BREAK -> { advance(); PyStmt.Break }
            PyTokType.CONTINUE -> { advance(); PyStmt.Continue }
            PyTokType.PASS -> { advance(); PyStmt.Pass }
            PyTokType.GLOBAL -> {
                advance()
                while (!check(PyTokType.NEWLINE) && !check(PyTokType.EOF)) advance()
                PyStmt.Pass
            }
            PyTokType.RAISE -> {
                advance()
                val e = if (check(PyTokType.NEWLINE) || check(PyTokType.EOF)) null else parseExpr()
                PyStmt.Raise(e, ln)
            }
            PyTokType.ASSERT -> {
                advance()
                val cond = parseExpr()
                val msg = if (match(PyTokType.COMMA)) parseExpr() else null
                PyStmt.Assert(cond, msg, ln)
            }
            PyTokType.IMPORT -> {
                advance()
                val mods = mutableListOf<Pair<String, String?>>()
                do {
                    val sb = StringBuilder(expect(PyTokType.IDENT, "expected module name").text)
                    while (match(PyTokType.DOT)) sb.append('.').append(expect(PyTokType.IDENT, "expected submodule").text)
                    val alias = if (match(PyTokType.AS)) expect(PyTokType.IDENT, "expected alias").text else null
                    mods.add(sb.toString() to alias)
                } while (match(PyTokType.COMMA))
                PyStmt.Import(mods, ln)
            }
            PyTokType.FROM -> {
                advance()
                val sb = StringBuilder(expect(PyTokType.IDENT, "expected module name").text)
                while (match(PyTokType.DOT)) sb.append('.').append(expect(PyTokType.IDENT, "expected submodule").text)
                expect(PyTokType.IMPORT, "expected 'import'")
                val names = mutableListOf<Pair<String, String?>>()
                if (match(PyTokType.STAR)) {
                    names.add("*" to null)
                } else {
                    do {
                        val n = expect(PyTokType.IDENT, "expected symbol name").text
                        val a = if (match(PyTokType.AS)) expect(PyTokType.IDENT, "expected alias").text else null
                        names.add(n to a)
                    } while (match(PyTokType.COMMA))
                }
                PyStmt.FromImport(sb.toString(), names, ln)
            }
            else -> {
                val target = parseExprOrTuple()
                val op = when (cur().type) {
                    PyTokType.ASSIGN -> "="
                    PyTokType.PLUS_EQ -> "+="
                    PyTokType.MINUS_EQ -> "-="
                    PyTokType.STAR_EQ -> "*="
                    PyTokType.SLASH_EQ -> "/="
                    else -> null
                }
                if (op != null) {
                    advance()
                    val rhs = parseExprOrTuple()
                    PyStmt.Assign(target, op, rhs, ln)
                } else {
                    PyStmt.ExprStmt(target, ln)
                }
            }
        }
    }

    private fun parseExprOrTuple(): PyExpr {
        val first = parseExpr()
        if (!check(PyTokType.COMMA)) return first
        val items = mutableListOf(first)
        while (match(PyTokType.COMMA)) {
            if (check(PyTokType.NEWLINE) || check(PyTokType.ASSIGN) || check(PyTokType.COLON) || check(PyTokType.EOF)) break
            items.add(parseExpr())
        }
        return PyExpr.TupleLit(items)
    }

    private fun parseExpr(): PyExpr {
        if (match(PyTokType.LAMBDA)) {
            val params = mutableListOf<String>()
            if (!check(PyTokType.COLON)) {
                do { params.add(expect(PyTokType.IDENT, "expected lambda param").text) } while (match(PyTokType.COMMA))
            }
            expect(PyTokType.COLON, "expected ':'")
            return PyExpr.Lambda(params, parseExpr())
        }
        val expr = parseOr()
        if (match(PyTokType.IF)) {
            val cond = parseOr()
            expect(PyTokType.ELSE, "expected 'else'")
            val falseExpr = parseExpr()
            return PyExpr.Ternary(expr, cond, falseExpr)
        }
        return expr
    }

    private fun parseOr(): PyExpr {
        var left = parseAnd()
        while (match(PyTokType.OR)) left = PyExpr.Binary("or", left, parseAnd())
        return left
    }

    private fun parseAnd(): PyExpr {
        var left = parseNot()
        while (match(PyTokType.AND)) left = PyExpr.Binary("and", left, parseNot())
        return left
    }

    private fun parseNot(): PyExpr = if (match(PyTokType.NOT)) PyExpr.Unary("not", parseNot()) else parseCompare()

    private fun parseCompare(): PyExpr {
        var left = parseAdd()
        while (true) {
            val op = when {
                match(PyTokType.EQ) -> "=="
                match(PyTokType.NEQ) -> "!="
                match(PyTokType.LT) -> "<"
                match(PyTokType.GT) -> ">"
                match(PyTokType.LE) -> "<="
                match(PyTokType.GE) -> ">="
                match(PyTokType.IN) -> "in"
                check(PyTokType.NOT) && peek().type == PyTokType.IN -> { advance(); advance(); "not in" }
                check(PyTokType.IS) && peek().type == PyTokType.NOT -> { advance(); advance(); "is not" }
                match(PyTokType.IS) -> "is"
                else -> null
            } ?: break
            left = PyExpr.Binary(op, left, parseAdd())
        }
        return left
    }

    private fun parseAdd(): PyExpr {
        var left = parseMul()
        while (check(PyTokType.PLUS) || check(PyTokType.MINUS)) {
            val op = advance().text
            left = PyExpr.Binary(op, left, parseMul())
        }
        return left
    }

    private fun parseMul(): PyExpr {
        var left = parseUnary()
        while (cur().type in setOf(PyTokType.STAR, PyTokType.SLASH, PyTokType.DOUBLE_SLASH, PyTokType.PERCENT)) {
            val op = advance().text
            left = PyExpr.Binary(op, left, parseUnary())
        }
        return left
    }

    private fun parseUnary(): PyExpr {
        if (check(PyTokType.MINUS) || check(PyTokType.PLUS)) {
            return PyExpr.Unary(advance().text, parseUnary())
        }
        return parsePower()
    }

    private fun parsePower(): PyExpr {
        val base = parsePostfix()
        if (match(PyTokType.DOUBLE_STAR)) {
            return PyExpr.Binary("**", base, parseUnary())
        }
        return base
    }

    private fun parsePostfix(): PyExpr {
        var expr = parsePrimary()
        while (true) {
            when {
                match(PyTokType.LPAREN) -> {
                    val posArgs = mutableListOf<PyExpr>()
                    val kwArgs = mutableListOf<Pair<String, PyExpr>>()
                    if (!check(PyTokType.RPAREN)) {
                        do {
                            if (check(PyTokType.RPAREN)) break
                            if (check(PyTokType.IDENT) && peek().type == PyTokType.ASSIGN) {
                                val k = advance().text
                                advance()
                                kwArgs.add(k to parseExpr())
                            } else {
                                posArgs.add(parseExpr())
                            }
                        } while (match(PyTokType.COMMA))
                    }
                    expect(PyTokType.RPAREN, "expected ')'")
                    expr = PyExpr.Call(expr, posArgs, kwArgs)
                }
                match(PyTokType.DOT) -> {
                    val name = expect(PyTokType.IDENT, "expected attribute name").text
                    expr = PyExpr.Attr(expr, name)
                }
                match(PyTokType.LBRACKET) -> {
                    if (check(PyTokType.COLON)) {
                        expr = parseSliceRest(expr, null)
                    } else {
                        val first = parseExpr()
                        if (check(PyTokType.COLON)) {
                            expr = parseSliceRest(expr, first)
                        } else {
                            expect(PyTokType.RBRACKET, "expected ']'")
                            expr = PyExpr.Index(expr, first)
                        }
                    }
                }
                else -> break
            }
        }
        return expr
    }

    private fun parseSliceRest(target: PyExpr, start: PyExpr?): PyExpr {
        advance() // ':'
        val stop = if (check(PyTokType.COLON) || check(PyTokType.RBRACKET)) null else parseExpr()
        val step = if (match(PyTokType.COLON)) {
            if (check(PyTokType.RBRACKET)) null else parseExpr()
        } else null
        expect(PyTokType.RBRACKET, "expected ']'")
        return PyExpr.Slice(target, start, stop, step)
    }

    private fun parsePrimary(): PyExpr {
        val tk = cur()
        return when (tk.type) {
            PyTokType.INT -> {
                advance()
                val v = if (tk.text.startsWith("0x", true)) tk.text.substring(2).toLong(16) else tk.text.toLong()
                PyExpr.IntLit(v)
            }
            PyTokType.FLOAT -> { advance(); PyExpr.FloatLit(tk.text.toDouble()) }
            PyTokType.TRUE -> { advance(); PyExpr.BoolLit(true) }
            PyTokType.FALSE -> { advance(); PyExpr.BoolLit(false) }
            PyTokType.NONE -> { advance(); PyExpr.NoneLit }
            PyTokType.STRING -> {
                val sb = StringBuilder(advance().text)
                while (check(PyTokType.STRING)) sb.append(advance().text)
                PyExpr.StrLit(sb.toString())
            }
            PyTokType.FSTRING -> { advance(); PyExpr.FStrLit(tk.text) }
            PyTokType.IDENT -> { advance(); PyExpr.Var(tk.text) }
            PyTokType.LPAREN -> {
                advance()
                if (match(PyTokType.RPAREN)) return PyExpr.TupleLit(emptyList())
                val first = parseExpr()
                if (match(PyTokType.COMMA)) {
                    val items = mutableListOf(first)
                    while (!check(PyTokType.RPAREN) && !check(PyTokType.EOF)) {
                        items.add(parseExpr())
                        if (!match(PyTokType.COMMA)) break
                    }
                    expect(PyTokType.RPAREN, "expected ')'")
                    PyExpr.TupleLit(items)
                } else {
                    expect(PyTokType.RPAREN, "expected ')'")
                    first
                }
            }
            PyTokType.LBRACKET -> {
                advance()
                if (match(PyTokType.RBRACKET)) return PyExpr.ListLit(emptyList())
                val first = parseExpr()
                if (match(PyTokType.FOR)) {
                    val vars = mutableListOf<String>()
                    do { vars.add(expect(PyTokType.IDENT, "expected variable").text) } while (match(PyTokType.COMMA))
                    expect(PyTokType.IN, "expected 'in'")
                    val iter = parseOr()
                    val cond = if (match(PyTokType.IF)) parseOr() else null
                    expect(PyTokType.RBRACKET, "expected ']'")
                    PyExpr.ListComp(first, vars, iter, cond)
                } else {
                    val items = mutableListOf(first)
                    while (match(PyTokType.COMMA) && !check(PyTokType.RBRACKET)) {
                        items.add(parseExpr())
                    }
                    expect(PyTokType.RBRACKET, "expected ']'")
                    PyExpr.ListLit(items)
                }
            }
            PyTokType.LBRACE -> {
                advance()
                if (match(PyTokType.RBRACE)) return PyExpr.DictLit(emptyList())
                val first = parseExpr()
                if (match(PyTokType.COLON)) {
                    val firstVal = parseExpr()
                    if (match(PyTokType.FOR)) {
                        val vars = mutableListOf<String>()
                        do { vars.add(expect(PyTokType.IDENT, "expected variable").text) } while (match(PyTokType.COMMA))
                        expect(PyTokType.IN, "expected 'in'")
                        val iter = parseOr()
                        val cond = if (match(PyTokType.IF)) parseOr() else null
                        expect(PyTokType.RBRACE, "expected '}'")
                        PyExpr.DictComp(first, firstVal, vars, iter, cond)
                    } else {
                        val entries = mutableListOf(first to firstVal)
                        while (match(PyTokType.COMMA) && !check(PyTokType.RBRACE)) {
                            val k = parseExpr()
                            expect(PyTokType.COLON, "expected ':'")
                            val v = parseExpr()
                            entries.add(k to v)
                        }
                        expect(PyTokType.RBRACE, "expected '}'")
                        PyExpr.DictLit(entries)
                    }
                } else {
                    val items = mutableListOf(first)
                    while (match(PyTokType.COMMA) && !check(PyTokType.RBRACE)) items.add(parseExpr())
                    expect(PyTokType.RBRACE, "expected '}'")
                    PyExpr.SetLit(items)
                }
            }
            else -> throw PySyntaxException(tk.line, "unexpected token '${tk.text}'")
        }
    }
}
