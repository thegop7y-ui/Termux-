package com.example.core.cpp

import java.io.File

class CppCompileException(val file: String, val line: Int, val col: Int, message: String) :
    Exception("$file:$line:$col: error: $message")

enum class CppTokType {
    IDENT, INT_LIT, FLOAT_LIT, STRING_LIT, CHAR_LIT,
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
    SEMI, COMMA, COLON, DOUBLE_COLON, DOT, ARROW, QUESTION,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    PLUS_PLUS, MINUS_MINUS,
    ASSIGN, PLUS_EQ, MINUS_EQ, STAR_EQ, SLASH_EQ, PERCENT_EQ,
    EQ, NEQ, LT, GT, LE, GE,
    AND, OR, NOT, BIT_AND, BIT_OR, BIT_XOR, SHL, SHR,
    EOF
}

data class CppToken(val type: CppTokType, val text: String, val line: Int, val col: Int)

sealed interface CppStmt {
    data class Block(val stmts: List<CppStmt>) : CppStmt
    data class VarDecl(val typeName: String, val name: String, val arraySize: CppExpr?, val init: CppExpr?) : CppStmt
    data class ExprStmt(val expr: CppExpr) : CppStmt
    data class If(val cond: CppExpr, val thenBranch: CppStmt, val elseBranch: CppStmt?) : CppStmt
    data class While(val cond: CppExpr, val body: CppStmt) : CppStmt
    data class DoWhile(val body: CppStmt, val cond: CppExpr) : CppStmt
    data class For(val init: CppStmt?, val cond: CppExpr?, val step: CppExpr?, val body: CppStmt) : CppStmt
    data class RangeFor(val varName: String, val iterable: CppExpr, val body: CppStmt) : CppStmt
    data class Switch(val expr: CppExpr, val cases: List<Pair<CppExpr?, List<CppStmt>>>) : CppStmt
    data class Return(val value: CppExpr?) : CppStmt
    data object Break : CppStmt
    data object Continue : CppStmt
    data class FunctionDef(
        val returnType: String,
        val name: String,
        val params: List<Pair<String, String>>,
        val body: Block
    ) : CppStmt
    data class StructDef(
        val name: String,
        val fields: List<VarDecl>,
        val methods: List<FunctionDef>
    ) : CppStmt
}

sealed interface CppExpr {
    data class IntLit(val value: Long) : CppExpr
    data class FloatLit(val value: Double) : CppExpr
    data class BoolLit(val value: Boolean) : CppExpr
    data class CharLit(val value: Char) : CppExpr
    data class StringLit(val value: String) : CppExpr
    data class Ident(val name: String) : CppExpr
    data class InitList(val items: List<CppExpr>) : CppExpr
    data class Unary(val op: String, val operand: CppExpr, val postfix: Boolean = false) : CppExpr
    data class Binary(val op: String, val left: CppExpr, val right: CppExpr) : CppExpr
    data class Assign(val target: CppExpr, val op: String, val value: CppExpr) : CppExpr
    data class Ternary(val cond: CppExpr, val ifTrue: CppExpr, val ifFalse: CppExpr) : CppExpr
    data class Call(val callee: CppExpr, val args: List<CppExpr>) : CppExpr
    data class Index(val target: CppExpr, val index: CppExpr) : CppExpr
    data class Member(val target: CppExpr, val member: String) : CppExpr
}

data class CppProgram(
    val functions: List<CppStmt.FunctionDef>,
    val structs: Map<String, CppStmt.StructDef>,
    val globals: List<CppStmt.VarDecl>
)

object CppPreprocessor {
    fun preprocess(source: String, baseDir: File, includeDir: File, visited: MutableSet<String> = mutableSetOf()): String {
        val defines = mutableMapOf<String, String>(
            "M_PI" to "3.14159265358979323846",
            "M_E" to "2.71828182845904523536",
            "INT_MAX" to "2147483647",
            "INT_MIN" to "-2147483648",
            "NULL" to "0",
            "nullptr" to "0",
            "true" to "true",
            "false" to "false"
        )
        val out = StringBuilder()
        val ifStack = ArrayDeque<Boolean>()

        fun isActive(): Boolean = ifStack.all { it }

        for (rawLine in source.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("#")) {
                val directive = trimmed.substring(1).trim()
                when {
                    directive.startsWith("ifdef ") -> {
                        val macro = directive.removePrefix("ifdef ").trim()
                        ifStack.addLast(defines.containsKey(macro))
                        out.append('\n')
                    }
                    directive.startsWith("ifndef ") -> {
                        val macro = directive.removePrefix("ifndef ").trim()
                        ifStack.addLast(!defines.containsKey(macro))
                        out.append('\n')
                    }
                    directive == "else" -> {
                        if (ifStack.isNotEmpty()) {
                            val top = ifStack.removeLast()
                            ifStack.addLast(!top)
                        }
                        out.append('\n')
                    }
                    directive == "endif" -> {
                        if (ifStack.isNotEmpty()) ifStack.removeLast()
                        out.append('\n')
                    }
                    !isActive() -> out.append('\n')
                    directive.startsWith("define ") -> {
                        val rest = directive.removePrefix("define ").trim()
                        val spaceIdx = rest.indexOfFirst { it.isWhitespace() }
                        if (spaceIdx > 0) {
                            val k = rest.substring(0, spaceIdx).trim()
                            val v = rest.substring(spaceIdx + 1).trim()
                            defines[k] = v
                        } else if (rest.isNotEmpty()) {
                            defines[rest] = "1"
                        }
                        out.append('\n')
                    }
                    directive.startsWith("include ") -> {
                        val arg = directive.removePrefix("include ").trim()
                        if (arg.startsWith("\"") && arg.endsWith("\"")) {
                            val rel = arg.substring(1, arg.length - 1)
                            val f = File(baseDir, rel).takeIf { it.exists() } ?: File(includeDir, rel)
                            if (f.exists() && f.isFile && visited.add(f.canonicalPath)) {
                                out.append(preprocess(f.readText(), f.parentFile ?: baseDir, includeDir, visited)).append('\n')
                            } else {
                                out.append('\n')
                            }
                        } else {
                            out.append('\n')
                        }
                    }
                    else -> out.append('\n')
                }
            } else if (isActive()) {
                var lineOut = rawLine
                for ((k, v) in defines) {
                    if (k != "true" && k != "false" && lineOut.contains(k)) {
                        lineOut = lineOut.replace(Regex("\\b${Regex.escape(k)}\\b"), v)
                    }
                }
                out.append(lineOut).append('\n')
            } else {
                out.append('\n')
            }
        }
        return out.toString()
    }
}

class CppLexer(private val src: String, private val fileName: String) {
    private var idx = 0
    private var line = 1
    private var col = 1

    private fun peek(offset: Int = 0): Char? = src.getOrNull(idx + offset)
    private fun advance(): Char {
        val ch = src[idx++]
        if (ch == '\n') { line++; col = 1 } else { col++ }
        return ch
    }

    fun tokenize(): List<CppToken> {
        val tokens = mutableListOf<CppToken>()
        while (idx < src.length) {
            val ch = peek()!!
            when {
                ch.isWhitespace() -> advance()
                ch == '/' && peek(1) == '/' -> {
                    while (idx < src.length && peek() != '\n') advance()
                }
                ch == '/' && peek(1) == '*' -> {
                    advance(); advance()
                    while (idx < src.length && !(peek() == '*' && peek(1) == '/')) advance()
                    if (idx < src.length) { advance(); advance() }
                }
                ch == '"' -> tokens.add(readString())
                ch == '\'' -> tokens.add(readChar())
                ch.isDigit() -> tokens.add(readNumber())
                ch.isLetter() || ch == '_' -> tokens.add(readIdent())
                else -> tokens.add(readSymbol())
            }
        }
        tokens.add(CppToken(CppTokType.EOF, "", line, col))
        return tokens
    }

    private fun readString(): CppToken {
        val startLine = line; val startCol = col
        advance()
        val sb = StringBuilder()
        while (idx < src.length && peek() != '"') {
            val c = advance()
            if (c == '\\' && idx < src.length) {
                when (val esc = advance()) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    '0' -> sb.append('\u0000')
                    else -> sb.append(esc)
                }
            } else sb.append(c)
        }
        if (idx >= src.length) throw CppCompileException(fileName, startLine, startCol, "unterminated string literal")
        advance()
        return CppToken(CppTokType.STRING_LIT, sb.toString(), startLine, startCol)
    }

    private fun readChar(): CppToken {
        val startLine = line; val startCol = col
        advance()
        val c = if (peek() == '\\') {
            advance()
            when (val esc = advance()) {
                'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '0' -> '\u0000'; else -> esc
            }
        } else advance()
        if (peek() == '\'') advance()
        return CppToken(CppTokType.CHAR_LIT, c.toString(), startLine, startCol)
    }

    private fun readNumber(): CppToken {
        val startLine = line; val startCol = col
        val sb = StringBuilder()
        var isFloat = false
        if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            sb.append(advance()).append(advance())
            while (peek()?.let { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } == true) sb.append(advance())
            return CppToken(CppTokType.INT_LIT, sb.toString(), startLine, startCol)
        }
        while (peek()?.isDigit() == true) sb.append(advance())
        if (peek() == '.' && peek(1)?.isDigit() == true) {
            isFloat = true
            sb.append(advance())
            while (peek()?.isDigit() == true) sb.append(advance())
        }
        if (peek() == 'e' || peek() == 'E') {
            isFloat = true
            sb.append(advance())
            if (peek() == '+' || peek() == '-') sb.append(advance())
            while (peek()?.isDigit() == true) sb.append(advance())
        }
        while (peek()?.lowercaseChar() in listOf('l', 'u', 'f')) {
            if (peek()?.lowercaseChar() == 'f') isFloat = true
            advance()
        }
        return CppToken(if (isFloat) CppTokType.FLOAT_LIT else CppTokType.INT_LIT, sb.toString(), startLine, startCol)
    }

    private fun readIdent(): CppToken {
        val startLine = line; val startCol = col
        val sb = StringBuilder()
        while (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) sb.append(advance())
        return CppToken(CppTokType.IDENT, sb.toString(), startLine, startCol)
    }

    private fun readSymbol(): CppToken {
        val sl = line; val sc = col
        val c = advance()
        val n = peek()
        return when {
            c == ':' && n == ':' -> { advance(); CppToken(CppTokType.DOUBLE_COLON, "::", sl, sc) }
            c == '-' && n == '>' -> { advance(); CppToken(CppTokType.ARROW, "->", sl, sc) }
            c == '+' && n == '+' -> { advance(); CppToken(CppTokType.PLUS_PLUS, "++", sl, sc) }
            c == '-' && n == '-' -> { advance(); CppToken(CppTokType.MINUS_MINUS, "--", sl, sc) }
            c == '+' && n == '=' -> { advance(); CppToken(CppTokType.PLUS_EQ, "+=", sl, sc) }
            c == '-' && n == '=' -> { advance(); CppToken(CppTokType.MINUS_EQ, "-=", sl, sc) }
            c == '*' && n == '=' -> { advance(); CppToken(CppTokType.STAR_EQ, "*=", sl, sc) }
            c == '/' && n == '=' -> { advance(); CppToken(CppTokType.SLASH_EQ, "/=", sl, sc) }
            c == '%' && n == '=' -> { advance(); CppToken(CppTokType.PERCENT_EQ, "%=", sl, sc) }
            c == '=' && n == '=' -> { advance(); CppToken(CppTokType.EQ, "==", sl, sc) }
            c == '!' && n == '=' -> { advance(); CppToken(CppTokType.NEQ, "!=", sl, sc) }
            c == '<' && n == '=' -> { advance(); CppToken(CppTokType.LE, "<=", sl, sc) }
            c == '>' && n == '=' -> { advance(); CppToken(CppTokType.GE, ">=", sl, sc) }
            c == '<' && n == '<' -> { advance(); CppToken(CppTokType.SHL, "<<", sl, sc) }
            c == '>' && n == '>' -> { advance(); CppToken(CppTokType.SHR, ">>", sl, sc) }
            c == '&' && n == '&' -> { advance(); CppToken(CppTokType.AND, "&&", sl, sc) }
            c == '|' && n == '|' -> { advance(); CppToken(CppTokType.OR, "||", sl, sc) }
            c == '(' -> CppToken(CppTokType.LPAREN, "(", sl, sc)
            c == ')' -> CppToken(CppTokType.RPAREN, ")", sl, sc)
            c == '{' -> CppToken(CppTokType.LBRACE, "{", sl, sc)
            c == '}' -> CppToken(CppTokType.RBRACE, "}", sl, sc)
            c == '[' -> CppToken(CppTokType.LBRACKET, "[", sl, sc)
            c == ']' -> CppToken(CppTokType.RBRACKET, "]", sl, sc)
            c == ';' -> CppToken(CppTokType.SEMI, ";", sl, sc)
            c == ',' -> CppToken(CppTokType.COMMA, ",", sl, sc)
            c == ':' -> CppToken(CppTokType.COLON, ":", sl, sc)
            c == '.' -> CppToken(CppTokType.DOT, ".", sl, sc)
            c == '?' -> CppToken(CppTokType.QUESTION, "?", sl, sc)
            c == '+' -> CppToken(CppTokType.PLUS, "+", sl, sc)
            c == '-' -> CppToken(CppTokType.MINUS, "-", sl, sc)
            c == '*' -> CppToken(CppTokType.STAR, "*", sl, sc)
            c == '/' -> CppToken(CppTokType.SLASH, "/", sl, sc)
            c == '%' -> CppToken(CppTokType.PERCENT, "%", sl, sc)
            c == '=' -> CppToken(CppTokType.ASSIGN, "=", sl, sc)
            c == '<' -> CppToken(CppTokType.LT, "<", sl, sc)
            c == '>' -> CppToken(CppTokType.GT, ">", sl, sc)
            c == '!' -> CppToken(CppTokType.NOT, "!", sl, sc)
            c == '&' -> CppToken(CppTokType.BIT_AND, "&", sl, sc)
            c == '|' -> CppToken(CppTokType.BIT_OR, "|", sl, sc)
            c == '^' -> CppToken(CppTokType.BIT_XOR, "^", sl, sc)
            else -> throw CppCompileException(fileName, sl, sc, "unexpected character '$c'")
        }
    }
}

class CppParser(private val tokens: List<CppToken>, private val fileName: String) {
    private var pos = 0
    private val customTypes = mutableSetOf<String>()

    private fun cur(): CppToken = tokens.getOrElse(pos) { tokens.last() }
    private fun peek(offset: Int = 1): CppToken = tokens.getOrElse(pos + offset) { tokens.last() }
    private fun advance(): CppToken = tokens[pos++].also { }
    private fun check(type: CppTokType, text: String? = null): Boolean =
        cur().type == type && (text == null || cur().text == text)
    private fun match(type: CppTokType, text: String? = null): Boolean {
        if (check(type, text)) { advance(); return true }
        return false
    }
    private fun expect(type: CppTokType, msg: String): CppToken {
        if (cur().type == type) return advance()
        throw CppCompileException(fileName, cur().line, cur().col, msg)
    }

    private val baseTypeKeywords = setOf(
        "void", "int", "long", "short", "char", "float", "double", "bool",
        "unsigned", "signed", "auto", "const", "static", "size_t", "string",
        "vector", "map", "pair", "ifstream", "ofstream", "stringstream"
    )

    private fun isTypeStart(): Boolean {
        if (cur().type != CppTokType.IDENT) return false
        val t = cur().text
        if (t in baseTypeKeywords || t in customTypes) return true
        if (t == "std" && peek().type == CppTokType.DOUBLE_COLON) return true
        return false
    }

    private fun parseTypeName(): String {
        val sb = StringBuilder()
        while (cur().type == CppTokType.IDENT && cur().text in setOf("const", "static", "unsigned", "signed", "long", "short")) {
            sb.append(advance().text).append(' ')
            if (cur().type != CppTokType.IDENT) break
        }
        if (cur().type == CppTokType.IDENT && cur().text == "std" && peek().type == CppTokType.DOUBLE_COLON) {
            advance(); advance()
        }
        if (cur().type == CppTokType.IDENT) {
            sb.append(advance().text)
        }
        if (match(CppTokType.LT)) {
            sb.append('<')
            var depth = 1
            while (depth > 0 && cur().type != CppTokType.EOF) {
                val tk = advance()
                if (tk.type == CppTokType.LT) depth++
                else if (tk.type == CppTokType.GT) depth--
                else if (tk.type == CppTokType.SHR) depth -= 2
                sb.append(tk.text)
            }
        }
        while (match(CppTokType.STAR) || match(CppTokType.BIT_AND)) {
            // reference/pointer modifier
        }
        return sb.toString().trim()
    }

    fun parseProgram(): CppProgram {
        val functions = mutableListOf<CppStmt.FunctionDef>()
        val structs = mutableMapOf<String, CppStmt.StructDef>()
        val globals = mutableListOf<CppStmt.VarDecl>()

        while (cur().type != CppTokType.EOF) {
            if (check(CppTokType.IDENT, "using")) {
                while (cur().type != CppTokType.SEMI && cur().type != CppTokType.EOF) advance()
                match(CppTokType.SEMI)
                continue
            }
            if (check(CppTokType.IDENT, "struct") || check(CppTokType.IDENT, "class")) {
                val st = parseStruct()
                structs[st.name] = st
                continue
            }
            if (isTypeStart()) {
                val saved = pos
                val typeName = parseTypeName()
                val name = expect(CppTokType.IDENT, "expected identifier").text
                if (check(CppTokType.LPAREN)) {
                    functions.add(parseFunctionRest(typeName, name))
                } else {
                    pos = saved
                    globals.addAll(parseVarDecls())
                }
            } else {
                throw CppCompileException(fileName, cur().line, cur().col, "unexpected token '${cur().text}'")
            }
        }
        return CppProgram(functions, structs, globals)
    }

    private fun parseStruct(): CppStmt.StructDef {
        advance() // struct/class
        val name = expect(CppTokType.IDENT, "expected struct name").text
        customTypes.add(name)
        expect(CppTokType.LBRACE, "expected '{'")
        val fields = mutableListOf<CppStmt.VarDecl>()
        val methods = mutableListOf<CppStmt.FunctionDef>()
        while (!check(CppTokType.RBRACE) && cur().type != CppTokType.EOF) {
            if (cur().type == CppTokType.IDENT && cur().text in setOf("public", "private", "protected")) {
                advance(); match(CppTokType.COLON); continue
            }
            if (cur().type == CppTokType.IDENT && cur().text == name && peek().type == CppTokType.LPAREN) {
                advance()
                methods.add(parseFunctionRest("void", name))
                continue
            }
            val saved = pos
            val typeName = parseTypeName()
            val memberName = expect(CppTokType.IDENT, "expected member name").text
            if (check(CppTokType.LPAREN)) {
                methods.add(parseFunctionRest(typeName, memberName))
            } else {
                pos = saved
                fields.addAll(parseVarDecls())
            }
        }
        expect(CppTokType.RBRACE, "expected '}'")
        match(CppTokType.SEMI)
        return CppStmt.StructDef(name, fields, methods)
    }

    private fun parseFunctionRest(returnType: String, name: String): CppStmt.FunctionDef {
        expect(CppTokType.LPAREN, "expected '('")
        val params = mutableListOf<Pair<String, String>>()
        if (!check(CppTokType.RPAREN)) {
            do {
                if (check(CppTokType.IDENT, "void") && peek().type == CppTokType.RPAREN) {
                    advance(); break
                }
                val pType = parseTypeName()
                val pName = if (cur().type == CppTokType.IDENT) advance().text else "_arg${params.size}"
                if (match(CppTokType.LBRACKET)) {
                    while (!check(CppTokType.RBRACKET) && cur().type != CppTokType.EOF) advance()
                    match(CppTokType.RBRACKET)
                }
                params.add(pType to pName)
            } while (match(CppTokType.COMMA))
        }
        expect(CppTokType.RPAREN, "expected ')'")
        if (match(CppTokType.SEMI)) {
            // forward declaration
            return CppStmt.FunctionDef(returnType, name, params, CppStmt.Block(emptyList()))
        }
        val body = parseBlock()
        return CppStmt.FunctionDef(returnType, name, params, body)
    }

    private fun parseBlock(): CppStmt.Block {
        expect(CppTokType.LBRACE, "expected '{'")
        val stmts = mutableListOf<CppStmt>()
        while (!check(CppTokType.RBRACE) && cur().type != CppTokType.EOF) {
            stmts.add(parseStmt())
        }
        expect(CppTokType.RBRACE, "expected '}'")
        return CppStmt.Block(stmts)
    }

    private fun parseStmt(): CppStmt {
        if (check(CppTokType.LBRACE)) return parseBlock()
        if (match(CppTokType.SEMI)) return CppStmt.Block(emptyList())
        if (check(CppTokType.IDENT, "if")) {
            advance()
            expect(CppTokType.LPAREN, "expected '(' after if")
            val cond = parseExpr()
            expect(CppTokType.RPAREN, "expected ')'")
            val thenBranch = parseStmt()
            val elseBranch = if (check(CppTokType.IDENT, "else")) { advance(); parseStmt() } else null
            return CppStmt.If(cond, thenBranch, elseBranch)
        }
        if (check(CppTokType.IDENT, "while")) {
            advance()
            expect(CppTokType.LPAREN, "expected '('")
            val cond = parseExpr()
            expect(CppTokType.RPAREN, "expected ')'")
            return CppStmt.While(cond, parseStmt())
        }
        if (check(CppTokType.IDENT, "do")) {
            advance()
            val body = parseStmt()
            if (!check(CppTokType.IDENT, "while")) throw CppCompileException(fileName, cur().line, cur().col, "expected 'while'")
            advance()
            expect(CppTokType.LPAREN, "expected '('")
            val cond = parseExpr()
            expect(CppTokType.RPAREN, "expected ')'")
            expect(CppTokType.SEMI, "expected ';'")
            return CppStmt.DoWhile(body, cond)
        }
        if (check(CppTokType.IDENT, "for")) {
            advance()
            expect(CppTokType.LPAREN, "expected '('")
            // Check range-based for: for (type var : iterable)
            val saved = pos
            if (isTypeStart()) {
                parseTypeName()
                if (cur().type == CppTokType.IDENT && peek().type == CppTokType.COLON) {
                    val vName = advance().text
                    advance() // :
                    val iter = parseExpr()
                    expect(CppTokType.RPAREN, "expected ')'")
                    return CppStmt.RangeFor(vName, iter, parseStmt())
                }
            }
            pos = saved
            val init: CppStmt? = when {
                match(CppTokType.SEMI) -> null
                isTypeStart() -> {
                    val decls = parseVarDecls()
                    if (decls.size == 1) decls.first() else CppStmt.Block(decls)
                }
                else -> {
                    val e = parseExpr()
                    expect(CppTokType.SEMI, "expected ';'")
                    CppStmt.ExprStmt(e)
                }
            }
            val cond = if (check(CppTokType.SEMI)) null else parseExpr()
            expect(CppTokType.SEMI, "expected ';'")
            val step = if (check(CppTokType.RPAREN)) null else parseExpr()
            expect(CppTokType.RPAREN, "expected ')'")
            return CppStmt.For(init, cond, step, parseStmt())
        }
        if (check(CppTokType.IDENT, "switch")) {
            advance()
            expect(CppTokType.LPAREN, "expected '('")
            val expr = parseExpr()
            expect(CppTokType.RPAREN, "expected ')'")
            expect(CppTokType.LBRACE, "expected '{'")
            val cases = mutableListOf<Pair<CppExpr?, List<CppStmt>>>()
            while (!check(CppTokType.RBRACE) && cur().type != CppTokType.EOF) {
                val label = when {
                    check(CppTokType.IDENT, "case") -> { advance(); val e = parseExpr(); expect(CppTokType.COLON, "expected ':'"); e }
                    check(CppTokType.IDENT, "default") -> { advance(); expect(CppTokType.COLON, "expected ':'"); null }
                    else -> throw CppCompileException(fileName, cur().line, cur().col, "expected 'case' or 'default'")
                }
                val body = mutableListOf<CppStmt>()
                while (!check(CppTokType.RBRACE) && !check(CppTokType.IDENT, "case") && !check(CppTokType.IDENT, "default") && cur().type != CppTokType.EOF) {
                    body.add(parseStmt())
                }
                cases.add(label to body)
            }
            expect(CppTokType.RBRACE, "expected '}'")
            return CppStmt.Switch(expr, cases)
        }
        if (check(CppTokType.IDENT, "return")) {
            advance()
            val v = if (check(CppTokType.SEMI)) null else parseExpr()
            expect(CppTokType.SEMI, "expected ';'")
            return CppStmt.Return(v)
        }
        if (check(CppTokType.IDENT, "break")) { advance(); expect(CppTokType.SEMI, "expected ';'"); return CppStmt.Break }
        if (check(CppTokType.IDENT, "continue")) { advance(); expect(CppTokType.SEMI, "expected ';'"); return CppStmt.Continue }
        if (isTypeStart()) {
            val decls = parseVarDecls()
            return if (decls.size == 1) decls.first() else CppStmt.Block(decls)
        }
        val expr = parseExpr()
        expect(CppTokType.SEMI, "expected ';'")
        return CppStmt.ExprStmt(expr)
    }

    private fun parseVarDecls(): List<CppStmt.VarDecl> {
        val typeName = parseTypeName()
        val decls = mutableListOf<CppStmt.VarDecl>()
        do {
            val name = expect(CppTokType.IDENT, "expected variable name").text
            var arraySize: CppExpr? = null
            if (match(CppTokType.LBRACKET)) {
                arraySize = if (check(CppTokType.RBRACKET)) CppExpr.IntLit(0) else parseExpr()
                expect(CppTokType.RBRACKET, "expected ']'")
            }
            val init: CppExpr? = when {
                match(CppTokType.ASSIGN) -> parseInitializerOrExpr()
                match(CppTokType.LPAREN) -> {
                    val args = mutableListOf<CppExpr>()
                    if (!check(CppTokType.RPAREN)) do { args.add(parseExpr()) } while (match(CppTokType.COMMA))
                    expect(CppTokType.RPAREN, "expected ')'")
                    if (args.size == 1) args.first() else CppExpr.InitList(args)
                }
                else -> null
            }
            decls.add(CppStmt.VarDecl(typeName, name, arraySize, init))
        } while (match(CppTokType.COMMA))
        expect(CppTokType.SEMI, "expected ';'")
        return decls
    }

    private fun parseInitializerOrExpr(): CppExpr {
        if (match(CppTokType.LBRACE)) {
            val items = mutableListOf<CppExpr>()
            if (!check(CppTokType.RBRACE)) {
                do { items.add(parseInitializerOrExpr()) } while (match(CppTokType.COMMA) && !check(CppTokType.RBRACE))
            }
            expect(CppTokType.RBRACE, "expected '}'")
            return CppExpr.InitList(items)
        }
        return parseExpr()
    }

    private fun parseExpr(): CppExpr = parseAssign()

    private fun parseAssign(): CppExpr {
        val left = parseTernary()
        val op = when (cur().type) {
            CppTokType.ASSIGN -> "="
            CppTokType.PLUS_EQ -> "+="
            CppTokType.MINUS_EQ -> "-="
            CppTokType.STAR_EQ -> "*="
            CppTokType.SLASH_EQ -> "/="
            CppTokType.PERCENT_EQ -> "%="
            else -> null
        }
        if (op != null) {
            advance()
            return CppExpr.Assign(left, op, parseAssign())
        }
        return left
    }

    private fun parseTernary(): CppExpr {
        val cond = parseOr()
        if (match(CppTokType.QUESTION)) {
            val t = parseExpr()
            expect(CppTokType.COLON, "expected ':'")
            val f = parseTernary()
            return CppExpr.Ternary(cond, t, f)
        }
        return cond
    }

    private fun parseOr(): CppExpr {
        var left = parseAnd()
        while (match(CppTokType.OR)) left = CppExpr.Binary("||", left, parseAnd())
        return left
    }

    private fun parseAnd(): CppExpr {
        var left = parseEquality()
        while (match(CppTokType.AND)) left = CppExpr.Binary("&&", left, parseEquality())
        return left
    }

    private fun parseEquality(): CppExpr {
        var left = parseRel()
        while (cur().type == CppTokType.EQ || cur().type == CppTokType.NEQ) {
            val op = advance().text
            left = CppExpr.Binary(op, left, parseRel())
        }
        return left
    }

    private fun parseRel(): CppExpr {
        var left = parseShift()
        while (cur().type in setOf(CppTokType.LT, CppTokType.GT, CppTokType.LE, CppTokType.GE)) {
            val op = advance().text
            left = CppExpr.Binary(op, left, parseShift())
        }
        return left
    }

    private fun parseShift(): CppExpr {
        var left = parseAdd()
        while (cur().type == CppTokType.SHL || cur().type == CppTokType.SHR) {
            val op = advance().text
            left = CppExpr.Binary(op, left, parseAdd())
        }
        return left
    }

    private fun parseAdd(): CppExpr {
        var left = parseMul()
        while (cur().type == CppTokType.PLUS || cur().type == CppTokType.MINUS) {
            val op = advance().text
            left = CppExpr.Binary(op, left, parseMul())
        }
        return left
    }

    private fun parseMul(): CppExpr {
        var left = parseUnary()
        while (cur().type in setOf(CppTokType.STAR, CppTokType.SLASH, CppTokType.PERCENT)) {
            val op = advance().text
            left = CppExpr.Binary(op, left, parseUnary())
        }
        return left
    }

    private fun parseUnary(): CppExpr {
        // C-style cast e.g. (double)x
        if (check(CppTokType.LPAREN) && peek().type == CppTokType.IDENT && peek().text in baseTypeKeywords && peek(2).type == CppTokType.RPAREN) {
            advance()
            val castType = advance().text
            advance()
            return CppExpr.Call(CppExpr.Ident("__cast_$castType"), listOf(parseUnary()))
        }
        if (cur().type in setOf(CppTokType.NOT, CppTokType.MINUS, CppTokType.PLUS, CppTokType.PLUS_PLUS, CppTokType.MINUS_MINUS, CppTokType.STAR, CppTokType.BIT_AND)) {
            val op = advance().text
            return CppExpr.Unary(op, parseUnary(), postfix = false)
        }
        return parsePostfix()
    }

    private fun parsePostfix(): CppExpr {
        var expr = parsePrimary()
        while (true) {
            when {
                match(CppTokType.LPAREN) -> {
                    val args = mutableListOf<CppExpr>()
                    if (!check(CppTokType.RPAREN)) {
                        do { args.add(parseExpr()) } while (match(CppTokType.COMMA))
                    }
                    expect(CppTokType.RPAREN, "expected ')'")
                    expr = CppExpr.Call(expr, args)
                }
                match(CppTokType.LBRACKET) -> {
                    val idx = parseExpr()
                    expect(CppTokType.RBRACKET, "expected ']'")
                    expr = CppExpr.Index(expr, idx)
                }
                match(CppTokType.DOT) || match(CppTokType.ARROW) -> {
                    val mem = expect(CppTokType.IDENT, "expected member name").text
                    expr = CppExpr.Member(expr, mem)
                }
                cur().type == CppTokType.PLUS_PLUS || cur().type == CppTokType.MINUS_MINUS -> {
                    expr = CppExpr.Unary(advance().text, expr, postfix = true)
                }
                else -> break
            }
        }
        return expr
    }

    private fun parsePrimary(): CppExpr {
        val tk = cur()
        return when (tk.type) {
            CppTokType.INT_LIT -> {
                advance()
                val v = if (tk.text.startsWith("0x", ignoreCase = true)) tk.text.substring(2).toLong(16) else tk.text.toLong()
                CppExpr.IntLit(v)
            }
            CppTokType.FLOAT_LIT -> { advance(); CppExpr.FloatLit(tk.text.toDouble()) }
            CppTokType.STRING_LIT -> {
                val sb = StringBuilder(advance().text)
                while (cur().type == CppTokType.STRING_LIT) sb.append(advance().text)
                CppExpr.StringLit(sb.toString())
            }
            CppTokType.CHAR_LIT -> { advance(); CppExpr.CharLit(tk.text.first()) }
            CppTokType.LPAREN -> {
                advance()
                val e = parseExpr()
                expect(CppTokType.RPAREN, "expected ')'")
                e
            }
            CppTokType.IDENT -> {
                advance()
                when (tk.text) {
                    "true" -> CppExpr.BoolLit(true)
                    "false" -> CppExpr.BoolLit(false)
                    "std" -> {
                        if (match(CppTokType.DOUBLE_COLON)) {
                            val member = expect(CppTokType.IDENT, "expected identifier after std::").text
                            CppExpr.Ident(member)
                        } else CppExpr.Ident("std")
                    }
                    else -> {
                        if (match(CppTokType.DOUBLE_COLON)) {
                            val sub = expect(CppTokType.IDENT, "expected identifier after ::").text
                            CppExpr.Ident("${tk.text}::$sub")
                        } else CppExpr.Ident(tk.text)
                    }
                }
            }
            else -> throw CppCompileException(fileName, tk.line, tk.col, "unexpected token '${tk.text}'")
        }
    }
}
