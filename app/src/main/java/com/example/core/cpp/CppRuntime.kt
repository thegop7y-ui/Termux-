package com.example.core.cpp

import java.io.File
import kotlin.math.*
import kotlin.random.Random

private class ReturnSignal(val value: Any?) : Throwable()
private class BreakSignal : Throwable()
private class ContinueSignal : Throwable()
private class ExitSignal(val code: Int) : Throwable()

class CppRuntime(
    private val cwd: File,
    private val onPrint: (String) -> Unit,
    private val onReadLine: suspend () -> String?,
    private val isCancelled: () -> Boolean
) {
    private val scopes = ArrayDeque<MutableMap<String, Any?>>()
    private val structs = mutableMapOf<String, CppStmt.StructDef>()
    private val functions = mutableMapOf<String, CppStmt.FunctionDef>()
    private var rng = Random(System.currentTimeMillis())
    private var floatPrecision = 6
    private var useFixed = false

    private object CoutStream
    private object CinStream
    private object EndlToken
    private object FixedToken

    private data class SetPrecisionToken(val n: Int)
    private data class StructInstance(val typeName: String, val fields: MutableMap<String, Any?>)
    private data class FileStream(val file: File, val isWrite: Boolean, var lines: List<String> = emptyList(), var cursor: Int = 0)

    suspend fun execute(program: CppProgram, args: List<String>): Int {
        structs.clear()
        structs.putAll(program.structs)
        functions.clear()
        for (fn in program.functions) {
            if (fn.body.stmts.isNotEmpty() || !functions.containsKey(fn.name)) {
                functions[fn.name] = fn
            }
        }
        scopes.clear()
        scopes.addLast(mutableMapOf())
        for (g in program.globals) {
            execVarDecl(g)
        }
        val mainFn = functions["main"] ?: throw IllegalStateException("undefined reference to `main'")
        return try {
            val mainArgs = mutableListOf<Any?>()
            if (mainFn.params.size >= 1) mainArgs.add(args.size.toLong())
            if (mainFn.params.size >= 2) mainArgs.add(args.toMutableList())
            val res = callFunction(mainFn, mainArgs)
            toLong(res).toInt()
        } catch (e: ExitSignal) {
            e.code
        }
    }

    private fun currentScope(): MutableMap<String, Any?> = scopes.last()

    private fun findScopeWith(name: String): MutableMap<String, Any?>? {
        for (i in scopes.indices.reversed()) {
            if (scopes[i].containsKey(name)) return scopes[i]
        }
        return null
    }

    private fun getVar(name: String): Any? {
        return when (name) {
            "cout", "cerr" -> CoutStream
            "cin" -> CinStream
            "endl" -> EndlToken
            "fixed" -> FixedToken
            else -> findScopeWith(name)?.get(name) ?: 0L
        }
    }

    private fun setVar(name: String, value: Any?) {
        val sc = findScopeWith(name)
        if (sc != null) sc[name] = value else currentScope()[name] = value
    }

    private suspend fun execStmt(stmt: CppStmt) {
        if (isCancelled()) throw ExitSignal(130)
        when (stmt) {
            is CppStmt.Block -> {
                scopes.addLast(mutableMapOf())
                try {
                    for (s in stmt.stmts) execStmt(s)
                } finally {
                    scopes.removeLast()
                }
            }
            is CppStmt.VarDecl -> execVarDecl(stmt)
            is CppStmt.ExprStmt -> evalExpr(stmt.expr)
            is CppStmt.If -> {
                if (toBool(evalExpr(stmt.cond))) execStmt(stmt.thenBranch)
                else if (stmt.elseBranch != null) execStmt(stmt.elseBranch)
            }
            is CppStmt.While -> {
                while (!isCancelled() && toBool(evalExpr(stmt.cond))) {
                    try {
                        execStmt(stmt.body)
                    } catch (_: ContinueSignal) {
                        continue
                    } catch (_: BreakSignal) {
                        break
                    }
                }
            }
            is CppStmt.DoWhile -> {
                do {
                    try {
                        execStmt(stmt.body)
                    } catch (_: ContinueSignal) {
                        // evaluate condition
                    } catch (_: BreakSignal) {
                        break
                    }
                } while (!isCancelled() && toBool(evalExpr(stmt.cond)))
            }
            is CppStmt.For -> {
                scopes.addLast(mutableMapOf())
                try {
                    if (stmt.init != null) execStmt(stmt.init)
                    while (!isCancelled() && (stmt.cond == null || toBool(evalExpr(stmt.cond)))) {
                        try {
                            execStmt(stmt.body)
                        } catch (_: ContinueSignal) {
                            // fall through to step
                        } catch (_: BreakSignal) {
                            break
                        }
                        if (stmt.step != null) evalExpr(stmt.step)
                    }
                } finally {
                    scopes.removeLast()
                }
            }
            is CppStmt.RangeFor -> {
                val iterable = evalExpr(stmt.iterable)
                val items: List<Any?> = when (iterable) {
                    is List<*> -> iterable.toList()
                    is String -> iterable.map { it }
                    else -> emptyList()
                }
                for (item in items) {
                    if (isCancelled()) break
                    scopes.addLast(mutableMapOf(stmt.varName to item))
                    try {
                        execStmt(stmt.body)
                    } catch (_: ContinueSignal) {
                        continue
                    } catch (_: BreakSignal) {
                        break
                    } finally {
                        scopes.removeLast()
                    }
                }
            }
            is CppStmt.Switch -> {
                val target = evalExpr(stmt.expr)
                var matched = false
                try {
                    for ((caseExpr, body) in stmt.cases) {
                        if (!matched) {
                            if (caseExpr == null || evalExpr(caseExpr) == target) {
                                matched = true
                            }
                        }
                        if (matched) {
                            for (s in body) execStmt(s)
                        }
                    }
                } catch (_: BreakSignal) {
                    // normal switch break
                }
            }
            is CppStmt.Return -> {
                val v = if (stmt.value != null) evalExpr(stmt.value) else null
                throw ReturnSignal(v)
            }
            is CppStmt.Break -> throw BreakSignal()
            is CppStmt.Continue -> throw ContinueSignal()
            is CppStmt.FunctionDef, is CppStmt.StructDef -> Unit
        }
    }

    private suspend fun execVarDecl(decl: CppStmt.VarDecl) {
        val baseType = decl.typeName.substringBefore('<').trim()
        val value: Any? = when {
            decl.init != null -> {
                val raw = evalExpr(decl.init)
                when {
                    baseType == "double" || baseType == "float" -> toDouble(raw)
                    baseType == "int" || baseType == "long" || baseType == "long long" || baseType == "size_t" -> toLong(raw)
                    baseType == "bool" -> toBool(raw)
                    baseType == "string" && raw is MutableList<*> -> ""
                    baseType == "ifstream" -> {
                        val f = File(cwd, raw.toString())
                        val lines = if (f.exists()) f.readLines() else emptyList()
                        FileStream(f, isWrite = false, lines = lines)
                    }
                    baseType == "ofstream" -> {
                        val f = File(cwd, raw.toString())
                        f.parentFile?.mkdirs()
                        f.writeText("")
                        FileStream(f, isWrite = true)
                    }
                    else -> raw
                }
            }
            decl.arraySize != null -> {
                val n = toLong(evalExpr(decl.arraySize)).toInt().coerceAtLeast(0)
                MutableList<Any?>(n) { 0L }
            }
            baseType == "vector" -> mutableListOf<Any?>()
            baseType == "map" -> mutableMapOf<Any?, Any?>()
            baseType == "string" -> ""
            baseType == "double" || baseType == "float" -> 0.0
            baseType == "bool" -> false
            baseType == "char" -> '\u0000'
            baseType == "pair" -> mutableListOf<Any?>(0L, 0L)
            structs.containsKey(baseType) -> instantiateStruct(baseType, emptyList())
            else -> 0L
        }
        currentScope()[decl.name] = value
    }

    private suspend fun instantiateStruct(typeName: String, args: List<Any?>): StructInstance {
        val def = structs[typeName]!!
        val fields = mutableMapOf<String, Any?>()
        for (f in def.fields) {
            fields[f.name] = if (f.init != null) evalExpr(f.init) else 0L
        }
        val inst = StructInstance(typeName, fields)
        val ctor = def.methods.find { it.name == typeName }
        if (ctor != null) {
            scopes.addLast(fields)
            try {
                for ((i, param) in ctor.params.withIndex()) {
                    fields[param.second] = args.getOrNull(i)
                }
                execStmt(ctor.body)
            } catch (_: ReturnSignal) {
            } finally {
                scopes.removeLast()
            }
        }
        return inst
    }

    private suspend fun callFunction(fn: CppStmt.FunctionDef, args: List<Any?>): Any? {
        val local = mutableMapOf<String, Any?>()
        for ((i, param) in fn.params.withIndex()) {
            local[param.second] = args.getOrNull(i)
        }
        scopes.addLast(local)
        return try {
            execStmt(fn.body)
            0L
        } catch (r: ReturnSignal) {
            r.value
        } finally {
            scopes.removeLast()
        }
    }

    private val cinTokens = ArrayDeque<String>()

    private suspend fun nextCinToken(): String? {
        while (cinTokens.isEmpty()) {
            val line = onReadLine() ?: return null
            val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            cinTokens.addAll(parts)
            if (cinTokens.isEmpty()) return ""
        }
        return cinTokens.removeFirst()
    }

    private suspend fun evalExpr(expr: CppExpr): Any? {
        return when (expr) {
            is CppExpr.IntLit -> expr.value
            is CppExpr.FloatLit -> expr.value
            is CppExpr.BoolLit -> expr.value
            is CppExpr.CharLit -> expr.value
            is CppExpr.StringLit -> expr.value
            is CppExpr.Ident -> getVar(expr.name)
            is CppExpr.InitList -> expr.items.map { evalExpr(it) }.toMutableList()
            is CppExpr.Ternary -> if (toBool(evalExpr(expr.cond))) evalExpr(expr.ifTrue) else evalExpr(expr.ifFalse)
            is CppExpr.Index -> {
                val target = evalExpr(expr.target)
                val idx = evalExpr(expr.index)
                when (target) {
                    is MutableList<*> -> target.getOrNull(toLong(idx).toInt())
                    is MutableMap<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val map = target as MutableMap<Any?, Any?>
                        map.getOrPut(idx) { 0L }
                    }
                    is String -> target.getOrNull(toLong(idx).toInt()) ?: '\u0000'
                    else -> 0L
                }
            }
            is CppExpr.Member -> {
                val t = evalExpr(expr.target)
                when (t) {
                    is StructInstance -> t.fields[expr.member]
                    else -> expr
                }
            }
            is CppExpr.Unary -> evalUnary(expr)
            is CppExpr.Binary -> evalBinary(expr)
            is CppExpr.Assign -> evalAssign(expr)
            is CppExpr.Call -> evalCall(expr)
        }
    }

    private suspend fun evalUnary(expr: CppExpr.Unary): Any? {
        if (expr.op == "++" || expr.op == "--") {
            val delta = if (expr.op == "++") 1L else -1L
            if (expr.operand is CppExpr.Ident) {
                val old = getVar(expr.operand.name)
                val newVal = if (old is Double) old + delta else toLong(old) + delta
                setVar(expr.operand.name, newVal)
                return if (expr.postfix) old else newVal
            }
        }
        val v = evalExpr(expr.operand)
        return when (expr.op) {
            "!" -> !toBool(v)
            "-" -> if (v is Double) -v else -toLong(v)
            "+" -> v
            else -> v
        }
    }

    private suspend fun evalAssign(expr: CppExpr.Assign): Any? {
        val rhs = evalExpr(expr.value)
        fun applyOp(cur: Any?): Any? = when (expr.op) {
            "=" -> rhs
            "+=" -> addValues(cur, rhs)
            "-=" -> if (cur is Double || rhs is Double) toDouble(cur) - toDouble(rhs) else toLong(cur) - toLong(rhs)
            "*=" -> if (cur is Double || rhs is Double) toDouble(cur) * toDouble(rhs) else toLong(cur) * toLong(rhs)
            "/=" -> if (cur is Double || rhs is Double) toDouble(cur) / toDouble(rhs) else toLong(cur) / toLong(rhs).coerceAtLeast(1L)
            "%=" -> toLong(cur) % toLong(rhs)
            else -> rhs
        }
        when (val target = expr.target) {
            is CppExpr.Ident -> {
                val res = applyOp(getVar(target.name))
                setVar(target.name, res)
                return res
            }
            is CppExpr.Index -> {
                val col = evalExpr(target.target)
                val idx = evalExpr(target.index)
                if (col is MutableList<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val list = col as MutableList<Any?>
                    val i = toLong(idx).toInt()
                    if (i in list.indices) {
                        val res = applyOp(list[i])
                        list[i] = res
                        return res
                    }
                } else if (col is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val map = col as MutableMap<Any?, Any?>
                    val res = applyOp(map[idx])
                    map[idx] = res
                    return res
                }
            }
            is CppExpr.Member -> {
                val obj = evalExpr(target.target)
                if (obj is StructInstance) {
                    val res = applyOp(obj.fields[target.member])
                    obj.fields[target.member] = res
                    return res
                }
            }
            else -> Unit
        }
        return rhs
    }

    private suspend fun evalBinary(expr: CppExpr.Binary): Any? {
        if (expr.op == "<<") {
            val left = evalExpr(expr.left)
            if (left === CoutStream) {
                val right = evalExpr(expr.right)
                emitToStream(right)
                return CoutStream
            }
            if (left is FileStream && left.isWrite) {
                val right = evalExpr(expr.right)
                left.file.appendText(formatValue(right))
                return left
            }
        }
        if (expr.op == ">>") {
            val left = evalExpr(expr.left)
            if (left === CinStream && expr.right is CppExpr.Ident) {
                val tok = nextCinToken() ?: return CinStream
                val cur = getVar(expr.right.name)
                val parsed: Any? = when (cur) {
                    is Double -> tok.toDoubleOrNull() ?: 0.0
                    is Long -> tok.toLongOrNull() ?: 0L
                    is Boolean -> tok == "1" || tok.equals("true", true)
                    else -> tok
                }
                setVar(expr.right.name, parsed)
                return CinStream
            }
        }
        if (expr.op == "&&") return toBool(evalExpr(expr.left)) && toBool(evalExpr(expr.right))
        if (expr.op == "||") return toBool(evalExpr(expr.left)) || toBool(evalExpr(expr.right))

        val l = evalExpr(expr.left)
        val r = evalExpr(expr.right)
        return when (expr.op) {
            "+" -> addValues(l, r)
            "-" -> if (l is Double || r is Double) toDouble(l) - toDouble(r) else toLong(l) - toLong(r)
            "*" -> if (l is Double || r is Double) toDouble(l) * toDouble(r) else toLong(l) * toLong(r)
            "/" -> if (l is Double || r is Double) toDouble(l) / toDouble(r) else {
                val den = toLong(r)
                if (den == 0L) throw ArithmeticException("division by zero") else toLong(l) / den
            }
            "%" -> toLong(l) % toLong(r)
            "<<" -> toLong(l) shl toLong(r).toInt()
            ">>" -> toLong(l) shr toLong(r).toInt()
            "==" -> compareEq(l, r)
            "!=" -> !compareEq(l, r)
            "<" -> compareNum(l, r) < 0
            ">" -> compareNum(l, r) > 0
            "<=" -> compareNum(l, r) <= 0
            ">=" -> compareNum(l, r) >= 0
            else -> 0L
        }
    }

    private fun emitToStream(v: Any?) {
        when (v) {
            EndlToken -> onPrint("\n")
            FixedToken -> useFixed = true
            is SetPrecisionToken -> floatPrecision = v.n
            else -> onPrint(formatValue(v))
        }
    }

    private fun formatValue(v: Any?): String = when (v) {
        null -> ""
        EndlToken -> "\n"
        is Boolean -> if (v) "1" else "0"
        is Double -> if (useFixed) String.format(java.util.Locale.US, "%.${floatPrecision}f", v)
        else {
            val s = v.toString()
            if (s.endsWith(".0")) s.dropLast(2) else s
        }
        else -> v.toString()
    }

    private suspend fun evalCall(call: CppExpr.Call): Any? {
        if (call.callee is CppExpr.Member) {
            val recv = evalExpr(call.callee.target)
            val m = call.callee.member
            val args = call.args.map { evalExpr(it) }
            when (recv) {
                is MutableList<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = recv as MutableList<Any?>
                    return when (m) {
                        "push_back", "emplace_back" -> { list.add(args.firstOrNull()); null }
                        "pop_back" -> { if (list.isNotEmpty()) list.removeAt(list.lastIndex); null }
                        "size" -> list.size.toLong()
                        "empty" -> list.isEmpty()
                        "clear" -> { list.clear(); null }
                        "front" -> list.firstOrNull()
                        "back" -> list.lastOrNull()
                        "begin" -> list
                        "end" -> list
                        else -> null
                    }
                }
                is String -> return when (m) {
                    "length", "size" -> recv.length.toLong()
                    "empty" -> recv.isEmpty()
                    "substr" -> {
                        val start = toLong(args.getOrNull(0)).toInt().coerceIn(0, recv.length)
                        val len = args.getOrNull(1)?.let { toLong(it).toInt() } ?: (recv.length - start)
                        recv.substring(start, (start + len).coerceAtMost(recv.length))
                    }
                    "find" -> recv.indexOf(args.firstOrNull()?.toString() ?: "").toLong()
                    else -> recv
                }
                is MutableMap<*, *> -> return when (m) {
                    "size" -> recv.size.toLong()
                    "empty" -> recv.isEmpty()
                    "count" -> if (recv.containsKey(args.firstOrNull())) 1L else 0L
                    else -> null
                }
                is FileStream -> return when (m) {
                    "is_open" -> recv.file.exists()
                    "close" -> Unit
                    else -> null
                }
                is StructInstance -> {
                    val fn = structs[recv.typeName]?.methods?.find { it.name == m }
                    if (fn != null) {
                        scopes.addLast(recv.fields)
                        return try {
                            callFunction(fn, args)
                        } finally {
                            scopes.removeLast()
                        }
                    }
                }
            }
            return null
        }

        val name = (call.callee as? CppExpr.Ident)?.name ?: return null
        if (name == "getline") {
            val line = onReadLine() ?: ""
            if (call.args.size >= 2 && call.args[1] is CppExpr.Ident) {
                setVar((call.args[1] as CppExpr.Ident).name, line)
            }
            return true
        }
        val args = call.args.map { evalExpr(it) }
        if (name.startsWith("__cast_")) {
            val t = name.removePrefix("__cast_")
            return when (t) {
                "double", "float" -> toDouble(args.firstOrNull())
                "int", "long" -> toLong(args.firstOrNull())
                "char" -> toLong(args.firstOrNull()).toInt().toChar()
                else -> args.firstOrNull()
            }
        }
        if (structs.containsKey(name)) return instantiateStruct(name, args)
        functions[name]?.let { return callFunction(it, args) }

        return when (name) {
            "printf" -> {
                val fmt = args.firstOrNull()?.toString() ?: ""
                onPrint(simplePrintf(fmt, args.drop(1)))
                0L
            }
            "puts" -> { onPrint((args.firstOrNull()?.toString() ?: "") + "\n"); 0L }
            "putchar" -> { onPrint(toLong(args.firstOrNull()).toInt().toChar().toString()); 0L }
            "setprecision" -> SetPrecisionToken(toLong(args.firstOrNull()).toInt())
            "sqrt" -> sqrt(toDouble(args.firstOrNull()))
            "pow" -> toDouble(args.getOrNull(0)).pow(toDouble(args.getOrNull(1)))
            "abs", "fabs" -> {
                val v = args.firstOrNull()
                if (v is Double) abs(v) else abs(toLong(v))
            }
            "sin" -> sin(toDouble(args.firstOrNull()))
            "cos" -> cos(toDouble(args.firstOrNull()))
            "tan" -> tan(toDouble(args.firstOrNull()))
            "floor" -> floor(toDouble(args.firstOrNull()))
            "ceil" -> ceil(toDouble(args.firstOrNull()))
            "round" -> round(toDouble(args.firstOrNull()))
            "log" -> ln(toDouble(args.firstOrNull()))
            "exp" -> exp(toDouble(args.firstOrNull()))
            "min" -> if (args.size >= 2) if (compareNum(args[0], args[1]) <= 0) args[0] else args[1] else 0L
            "max" -> if (args.size >= 2) if (compareNum(args[0], args[1]) >= 0) args[0] else args[1] else 0L
            "sort" -> {
                val list = args.firstOrNull() as? MutableList<Any?>
                list?.sortWith { a, b -> compareNum(a, b) }
                null
            }
            "reverse" -> {
                (args.firstOrNull() as? MutableList<Any?>)?.reverse()
                null
            }
            "to_string" -> formatValue(args.firstOrNull())
            "stoi", "atoi" -> args.firstOrNull()?.toString()?.trim()?.toLongOrNull() ?: 0L
            "stod", "atof" -> args.firstOrNull()?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
            "rand" -> rng.nextInt(0, Int.MAX_VALUE).toLong()
            "srand" -> { rng = Random(toLong(args.firstOrNull())); null }
            "time" -> System.currentTimeMillis() / 1000L
            "exit" -> throw ExitSignal(toLong(args.firstOrNull()).toInt())
            "make_pair" -> mutableListOf(args.getOrNull(0), args.getOrNull(1))
            else -> throw IllegalStateException("undefined reference to `$name'")
        }
    }

    private fun simplePrintf(fmt: String, args: List<Any?>): String {
        val sb = StringBuilder()
        var i = 0
        var argIdx = 0
        while (i < fmt.length) {
            if (fmt[i] == '%' && i + 1 < fmt.length) {
                var j = i + 1
                val spec = StringBuilder("%")
                while (j < fmt.length && (fmt[j].isDigit() || fmt[j] in listOf('.', '-', '+', 'l'))) {
                    if (fmt[j] != 'l') spec.append(fmt[j])
                    j++
                }
                if (j < fmt.length) {
                    val code = fmt[j]
                    if (code == '%') {
                        sb.append('%')
                    } else {
                        val v = args.getOrNull(argIdx++)
                        when (code) {
                            'd', 'i', 'u' -> sb.append(toLong(v))
                            'f' -> {
                                spec.append('f')
                                sb.append(String.format(java.util.Locale.US, spec.toString(), toDouble(v)))
                            }
                            's' -> sb.append(v?.toString() ?: "")
                            'c' -> sb.append(if (v is Char) v else toLong(v).toInt().toChar())
                            'x' -> sb.append(toLong(v).toString(16))
                            else -> sb.append(v?.toString() ?: "")
                        }
                    }
                    i = j + 1
                    continue
                }
            }
            sb.append(fmt[i])
            i++
        }
        return sb.toString()
    }

    private fun addValues(a: Any?, b: Any?): Any = when {
        a is String || b is String -> (a?.toString() ?: "") + (b?.toString() ?: "")
        a is Double || b is Double -> toDouble(a) + toDouble(b)
        else -> toLong(a) + toLong(b)
    }

    private fun toLong(v: Any?): Long = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is Boolean -> if (v) 1L else 0L
        is Char -> v.code.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun toDouble(v: Any?): Double = when (v) {
        is Double -> v
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is Char -> v.code.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun toBool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Long -> v != 0L
        is Double -> v != 0.0
        is Char -> v != '\u0000'
        is String -> v.isNotEmpty()
        null -> false
        else -> true
    }

    private fun compareEq(a: Any?, b: Any?): Boolean = when {
        a is String && b is String -> a == b
        a is Double || b is Double -> toDouble(a) == toDouble(b)
        else -> toLong(a) == toLong(b)
    }

    private fun compareNum(a: Any?, b: Any?): Int = when {
        a is String && b is String -> a.compareTo(b)
        a is Double || b is Double -> toDouble(a).compareTo(toDouble(b))
        else -> toLong(a).compareTo(toLong(b))
    }
}

object CppToolchain {
    const val BIN_MAGIC = "#!termux-cpp-bin\n"

    fun compile(
        sourceFiles: List<File>,
        outputFile: File,
        includeDir: File,
        onPrint: (String) -> Unit
    ): Int {
        if (sourceFiles.isEmpty()) {
            onPrint("\u001B[1;31mclang++: error:\u001B[0m no input files\n")
            return 1
        }
        val mergedSource = StringBuilder()
        for (srcFile in sourceFiles) {
            if (!srcFile.exists()) {
                onPrint("\u001B[1;31mclang++: error:\u001B[0m no such file or directory: '${srcFile.name}'\n")
                return 1
            }
            try {
                val preprocessed = CppPreprocessor.preprocess(
                    srcFile.readText(),
                    srcFile.parentFile ?: outputFile.parentFile ?: includeDir,
                    includeDir
                )
                val tokens = CppLexer(preprocessed, srcFile.name).tokenize()
                CppParser(tokens, srcFile.name).parseProgram()
                mergedSource.append(preprocessed).append("\n")
            } catch (e: CppCompileException) {
                onPrint("\u001B[1m${e.file}:${e.line}:${e.col}: \u001B[31merror:\u001B[0m ${e.message?.substringAfter("error: ")}\n")
                return 1
            } catch (e: Exception) {
                onPrint("\u001B[1;31merror:\u001B[0m ${e.message}\n")
                return 1
            }
        }
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(BIN_MAGIC + mergedSource.toString())
        outputFile.setExecutable(true)
        return 0
    }

    suspend fun runBinary(
        binaryFile: File,
        args: List<String>,
        cwd: File,
        onPrint: (String) -> Unit,
        onReadLine: suspend () -> String?,
        isCancelled: () -> Boolean
    ): Int {
        val raw = binaryFile.readText()
        val code = raw.removePrefix(BIN_MAGIC)
        return try {
            val tokens = CppLexer(code, binaryFile.name).tokenize()
            val program = CppParser(tokens, binaryFile.name).parseProgram()
            CppRuntime(cwd, onPrint, onReadLine, isCancelled).execute(program, args)
        } catch (e: Exception) {
            onPrint("\u001B[1;31mruntime error:\u001B[0m ${e.message}\n")
            1
        }
    }
}
