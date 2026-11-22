package com.example.core.python

import android.database.sqlite.SQLiteDatabase
import com.example.core.fs.TermuxEnvironment
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

private class PyReturn(val value: Any?) : Throwable()
private class PyBreak : Throwable()
private class PyContinue : Throwable()
private class PyRaise(val exc: Any?) : Throwable(exc?.toString() ?: "Exception")
private class PyExit(val code: Int) : Throwable()

private data class PyTuple(val items: List<Any?>)
private data class PyFunction(
    val name: String,
    val params: List<Pair<String, PyExpr?>>,
    val varArg: String?,
    val kwArg: String?,
    val body: List<PyStmt>,
    val closure: MutableMap<String, Any?>
)
private data class PyLambda(val params: List<String>, val body: PyExpr, val closure: MutableMap<String, Any?>)
private data class PyClass(val name: String, val base: PyClass?, val methods: MutableMap<String, PyFunction>)
private data class PyInstance(val cls: PyClass, val attrs: MutableMap<String, Any?> = mutableMapOf())
private data class PyBoundMethod(val self: PyInstance, val fn: PyFunction)
private data class PyModule(val name: String, val members: MutableMap<String, Any?>)
private class PyBuiltinFn(val name: String, val fn: suspend (List<Any?>, Map<String, Any?>) -> Any?)
private class PyFileHandle(val file: File, val mode: String) {
    var closed = false
}

class PythonRuntime(
    private val env: TermuxEnvironment,
    private val cwd: File,
    private val scriptArgs: List<String>,
    private val onPrint: (String) -> Unit,
    private val onReadLine: suspend () -> String?,
    private val isCancelled: () -> Boolean
) {
    private val globals = mutableMapOf<String, Any?>()
    private val scopes = ArrayDeque<MutableMap<String, Any?>>().apply { addLast(globals) }
    private val rng = Random(System.currentTimeMillis())
    private val http = OkHttpClient()

    init {
        registerBuiltins()
    }

    suspend fun runScript(source: String): Int {
        return try {
            val tokens = PythonLexer(source).tokenize()
            val stmts = PythonParser(tokens).parseProgram()
            for (s in stmts) execStmt(s)
            0
        } catch (e: PyExit) {
            e.code
        } catch (e: PySyntaxException) {
            onPrint("\u001B[1;31m${e.message}\u001B[0m\n")
            1
        } catch (e: PyRaise) {
            onPrint("\u001B[1;31mTraceback (most recent call last):\n  ${e.exc}\u001B[0m\n")
            1
        } catch (e: Exception) {
            onPrint("\u001B[1;31mError: ${e.message}\u001B[0m\n")
            1
        }
    }

    suspend fun evalReplLine(source: String): Pair<Boolean, String?> {
        return try {
            val tokens = PythonLexer(source).tokenize()
            val stmts = PythonParser(tokens).parseProgram()
            var lastVal: Any? = null
            for (s in stmts) {
                if (s is PyStmt.ExprStmt) {
                    lastVal = evalExpr(s.expr)
                    if (lastVal != null) onPrint(pyRepr(lastVal) + "\n")
                } else {
                    execStmt(s)
                }
            }
            true to null
        } catch (_: PyExit) {
            false to null
        } catch (e: Exception) {
            onPrint("\u001B[31m${e.message}\u001B[0m\n")
            true to e.message
        }
    }

    private fun lookup(name: String): Any? {
        for (i in scopes.indices.reversed()) {
            if (scopes[i].containsKey(name)) return scopes[i][name]
        }
        return globals[name]
    }

    private fun assignVar(name: String, value: Any?) {
        scopes.last()[name] = value
    }

    private suspend fun execStmt(stmt: PyStmt) {
        if (isCancelled()) throw PyExit(130)
        when (stmt) {
            is PyStmt.Pass -> Unit
            is PyStmt.Break -> throw PyBreak()
            is PyStmt.Continue -> throw PyContinue()
            is PyStmt.Return -> throw PyReturn(stmt.value?.let { evalExpr(it) })
            is PyStmt.Raise -> throw PyRaise(stmt.expr?.let { evalExpr(it) } ?: "RuntimeError")
            is PyStmt.Assert -> {
                if (!toBool(evalExpr(stmt.cond))) {
                    val m = stmt.msg?.let { pyStr(evalExpr(it)) } ?: "AssertionError"
                    throw PyRaise("AssertionError: $m")
                }
            }
            is PyStmt.ExprStmt -> evalExpr(stmt.expr)
            is PyStmt.Assign -> execAssign(stmt)
            is PyStmt.If -> {
                var done = false
                for ((cond, body) in stmt.branches) {
                    if (toBool(evalExpr(cond))) {
                        for (s in body) execStmt(s)
                        done = true
                        break
                    }
                }
                if (!done) for (s in stmt.elseBranch) execStmt(s)
            }
            is PyStmt.While -> {
                while (!isCancelled() && toBool(evalExpr(stmt.cond))) {
                    try {
                        for (s in stmt.body) execStmt(s)
                    } catch (_: PyContinue) {
                        continue
                    } catch (_: PyBreak) {
                        break
                    }
                }
            }
            is PyStmt.For -> {
                val items = toList(evalExpr(stmt.iter))
                for (item in items) {
                    if (isCancelled()) break
                    if (stmt.targets.size == 1) {
                        assignVar(stmt.targets[0], item)
                    } else {
                        val sub = toList(item)
                        for ((idx, t) in stmt.targets.withIndex()) {
                            assignVar(t, sub.getOrNull(idx))
                        }
                    }
                    try {
                        for (s in stmt.body) execStmt(s)
                    } catch (_: PyContinue) {
                        continue
                    } catch (_: PyBreak) {
                        break
                    }
                }
            }
            is PyStmt.FuncDef -> {
                val fn = PyFunction(stmt.name, stmt.params, stmt.varArg, stmt.kwArg, stmt.body, scopes.last())
                assignVar(stmt.name, fn)
            }
            is PyStmt.ClassDef -> {
                val base = stmt.baseName?.let { lookup(it) as? PyClass }
                val classScope = mutableMapOf<String, Any?>()
                scopes.addLast(classScope)
                try {
                    for (s in stmt.body) execStmt(s)
                } finally {
                    scopes.removeLast()
                }
                val methods = mutableMapOf<String, PyFunction>()
                for ((k, v) in classScope) if (v is PyFunction) methods[k] = v
                assignVar(stmt.name, PyClass(stmt.name, base, methods))
            }
            is PyStmt.Import -> {
                for ((modName, alias) in stmt.modules) {
                    val mod = loadModule(modName)
                    assignVar(alias ?: modName.substringBefore('.'), mod)
                }
            }
            is PyStmt.FromImport -> {
                val mod = loadModule(stmt.module)
                for ((name, alias) in stmt.names) {
                    if (name == "*") {
                        for ((k, v) in mod.members) assignVar(k, v)
                    } else {
                        assignVar(alias ?: name, mod.members[name])
                    }
                }
            }
            is PyStmt.TryExcept -> {
                try {
                    for (s in stmt.tryBody) execStmt(s)
                } catch (e: Throwable) {
                    if (e is PyReturn || e is PyBreak || e is PyContinue || e is PyExit) throw e
                    if (stmt.exceptVar != null) assignVar(stmt.exceptVar, e.message ?: e.toString())
                    for (s in stmt.exceptBody) execStmt(s)
                } finally {
                    for (s in stmt.finallyBody) execStmt(s)
                }
            }
            is PyStmt.With -> {
                val ctx = evalExpr(stmt.ctxExpr)
                if (stmt.alias != null) assignVar(stmt.alias, ctx)
                try {
                    for (s in stmt.body) execStmt(s)
                } finally {
                    if (ctx is PyFileHandle) ctx.closed = true
                }
            }
        }
    }

    private suspend fun execAssign(stmt: PyStmt.Assign) {
        val rhs = evalExpr(stmt.value)
        when (val t = stmt.target) {
            is PyExpr.Var -> {
                val v = if (stmt.op == "=") rhs else applyAugAssign(lookup(t.name), stmt.op, rhs)
                assignVar(t.name, v)
            }
            is PyExpr.TupleLit -> {
                val items = toList(rhs)
                for ((i, itemExpr) in t.items.withIndex()) {
                    if (itemExpr is PyExpr.Var) assignVar(itemExpr.name, items.getOrNull(i))
                }
            }
            is PyExpr.Attr -> {
                val obj = evalExpr(t.target)
                if (obj is PyInstance) obj.attrs[t.name] = rhs
            }
            is PyExpr.Index -> {
                val col = evalExpr(t.target)
                val idx = evalExpr(t.index)
                if (col is MutableList<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val list = col as MutableList<Any?>
                    var i = toLong(idx).toInt()
                    if (i < 0) i += list.size
                    if (i in list.indices) {
                        list[i] = if (stmt.op == "=") rhs else applyAugAssign(list[i], stmt.op, rhs)
                    }
                } else if (col is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val map = col as MutableMap<Any?, Any?>
                    map[idx] = if (stmt.op == "=") rhs else applyAugAssign(map[idx], stmt.op, rhs)
                }
            }
            else -> Unit
        }
    }

    private fun applyAugAssign(cur: Any?, op: String, rhs: Any?): Any? = when (op) {
        "+=" -> addPy(cur, rhs)
        "-=" -> subPy(cur, rhs)
        "*=" -> mulPy(cur, rhs)
        "/=" -> toDouble(cur) / toDouble(rhs)
        else -> rhs
    }

    private suspend fun evalExpr(expr: PyExpr): Any? {
        return when (expr) {
            is PyExpr.IntLit -> expr.value
            is PyExpr.FloatLit -> expr.value
            is PyExpr.BoolLit -> expr.value
            is PyExpr.StrLit -> expr.value
            is PyExpr.NoneLit -> null
            is PyExpr.FStrLit -> evalFString(expr.raw)
            is PyExpr.Var -> lookup(expr.name)
            is PyExpr.ListLit -> expr.items.map { evalExpr(it) }.toMutableList()
            is PyExpr.TupleLit -> PyTuple(expr.items.map { evalExpr(it) })
            is PyExpr.SetLit -> expr.items.map { evalExpr(it) }.toMutableSet()
            is PyExpr.DictLit -> {
                val m = linkedMapOf<Any?, Any?>()
                for ((k, v) in expr.entries) m[evalExpr(k)] = evalExpr(v)
                m
            }
            is PyExpr.ListComp -> {
                val out = mutableListOf<Any?>()
                for (item in toList(evalExpr(expr.iter))) {
                    val compScope = mutableMapOf<String, Any?>()
                    if (expr.varNames.size == 1) compScope[expr.varNames[0]] = item
                    else {
                        val sub = toList(item)
                        expr.varNames.forEachIndexed { i, n -> compScope[n] = sub.getOrNull(i) }
                    }
                    scopes.addLast(compScope)
                    try {
                        if (expr.cond == null || toBool(evalExpr(expr.cond))) out.add(evalExpr(expr.elem))
                    } finally {
                        scopes.removeLast()
                    }
                }
                out
            }
            is PyExpr.DictComp -> {
                val out = linkedMapOf<Any?, Any?>()
                for (item in toList(evalExpr(expr.iter))) {
                    val compScope = mutableMapOf<String, Any?>()
                    if (expr.varNames.size == 1) compScope[expr.varNames[0]] = item
                    else {
                        val sub = toList(item)
                        expr.varNames.forEachIndexed { i, n -> compScope[n] = sub.getOrNull(i) }
                    }
                    scopes.addLast(compScope)
                    try {
                        if (expr.cond == null || toBool(evalExpr(expr.cond))) {
                            out[evalExpr(expr.key)] = evalExpr(expr.value)
                        }
                    } finally {
                        scopes.removeLast()
                    }
                }
                out
            }
            is PyExpr.Ternary -> if (toBool(evalExpr(expr.cond))) evalExpr(expr.ifTrue) else evalExpr(expr.ifFalse)
            is PyExpr.Lambda -> PyLambda(expr.params, expr.body, scopes.last())
            is PyExpr.Unary -> {
                val v = evalExpr(expr.operand)
                when (expr.op) {
                    "not" -> !toBool(v)
                    "-" -> if (v is Double) -v else -toLong(v)
                    "+" -> v
                    else -> v
                }
            }
            is PyExpr.Binary -> evalBinary(expr)
            is PyExpr.Attr -> evalAttr(evalExpr(expr.target), expr.name)
            is PyExpr.Index -> evalIndex(evalExpr(expr.target), evalExpr(expr.index))
            is PyExpr.Slice -> evalSlice(expr)
            is PyExpr.Call -> {
                val callee = evalExpr(expr.callee)
                val pos = expr.posArgs.map { evalExpr(it) }
                val kw = linkedMapOf<String, Any?>()
                for ((k, v) in expr.kwArgs) kw[k] = evalExpr(v)
                invokeCallable(callee, pos, kw)
            }
        }
    }

    private suspend fun evalFString(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '{') {
                if (i + 1 < raw.length && raw[i + 1] == '{') { sb.append('{'); i += 2; continue }
                val end = raw.indexOf('}', i + 1)
                if (end > i) {
                    val inside = raw.substring(i + 1, end)
                    val colonIdx = inside.indexOf(':')
                    val exprStr = if (colonIdx > 0) inside.substring(0, colonIdx) else inside
                    val fmtSpec = if (colonIdx > 0) inside.substring(colonIdx + 1) else null
                    val exprAst = PythonParser(PythonLexer(exprStr).tokenize()).parseProgram().firstOrNull()
                    val value = if (exprAst is PyStmt.ExprStmt) evalExpr(exprAst.expr) else null
                    if (fmtSpec != null && fmtSpec.endsWith("f") && fmtSpec.startsWith(".")) {
                        val prec = fmtSpec.drop(1).dropLast(1).toIntOrNull() ?: 2
                        sb.append(String.format(Locale.US, "%.${prec}f", toDouble(value)))
                    } else {
                        sb.append(pyStr(value))
                    }
                    i = end + 1
                    continue
                }
            } else if (raw[i] == '}' && i + 1 < raw.length && raw[i + 1] == '}') {
                sb.append('}'); i += 2; continue
            }
            sb.append(raw[i++])
        }
        return sb.toString()
    }

    private suspend fun evalBinary(expr: PyExpr.Binary): Any? {
        if (expr.op == "and") {
            val l = evalExpr(expr.left)
            return if (!toBool(l)) l else evalExpr(expr.right)
        }
        if (expr.op == "or") {
            val l = evalExpr(expr.left)
            return if (toBool(l)) l else evalExpr(expr.right)
        }
        val l = evalExpr(expr.left)
        val r = evalExpr(expr.right)
        return when (expr.op) {
            "+" -> addPy(l, r)
            "-" -> subPy(l, r)
            "*" -> mulPy(l, r)
            "/" -> toDouble(l) / toDouble(r)
            "//" -> floorDiv(toLong(l), toLong(r))
            "%" -> {
                if (l is String) {
                    val args = if (r is PyTuple) r.items else listOf(r)
                    var idx = 0
                    Regex("%[sdf]").replace(l) { pyStr(args.getOrNull(idx++)) }
                } else {
                    val b = toLong(r)
                    ((toLong(l) % b) + b) % b
                }
            }
            "**" -> if (l is Double || r is Double) toDouble(l).pow(toDouble(r)) else toDouble(l).pow(toDouble(r)).toLong()
            "==" -> l == r || (l is Number && r is Number && l.toDouble() == r.toDouble())
            "!=" -> !(l == r || (l is Number && r is Number && l.toDouble() == r.toDouble()))
            "<" -> comparePy(l, r) < 0
            ">" -> comparePy(l, r) > 0
            "<=" -> comparePy(l, r) <= 0
            ">=" -> comparePy(l, r) >= 0
            "is" -> l === r
            "is not" -> l !== r
            "in" -> containsPy(r, l)
            "not in" -> !containsPy(r, l)
            else -> null
        }
    }

    private fun floorDiv(a: Long, b: Long): Long {
        if (b == 0L) throw PyRaise("ZeroDivisionError: integer division or modulo by zero")
        var q = a / b
        if ((a xor b) < 0 && q * b != a) q--
        return q
    }

    private fun addPy(a: Any?, b: Any?): Any = when {
        a is String && b is String -> a + b
        a is List<*> && b is List<*> -> (a + b).toMutableList()
        a is Double || b is Double -> toDouble(a) + toDouble(b)
        else -> toLong(a) + toLong(b)
    }

    private fun subPy(a: Any?, b: Any?): Any =
        if (a is Double || b is Double) toDouble(a) - toDouble(b) else toLong(a) - toLong(b)

    private fun mulPy(a: Any?, b: Any?): Any = when {
        a is String && b is Long -> a.repeat(b.toInt().coerceAtLeast(0))
        b is String && a is Long -> b.repeat(a.toInt().coerceAtLeast(0))
        a is List<*> && b is Long -> {
            val res = mutableListOf<Any?>()
            repeat(b.toInt().coerceAtLeast(0)) { res.addAll(a) }
            res
        }
        a is Double || b is Double -> toDouble(a) * toDouble(b)
        else -> toLong(a) * toLong(b)
    }

    private fun containsPy(container: Any?, item: Any?): Boolean = when (container) {
        is String -> container.contains(item?.toString() ?: "")
        is Collection<*> -> container.contains(item)
        is Map<*, *> -> container.containsKey(item)
        is PyTuple -> container.items.contains(item)
        else -> false
    }

    private fun evalIndex(target: Any?, idx: Any?): Any? = when (target) {
        is List<*> -> {
            var i = toLong(idx).toInt()
            if (i < 0) i += target.size
            target.getOrNull(i)
        }
        is PyTuple -> {
            var i = toLong(idx).toInt()
            if (i < 0) i += target.items.size
            target.items.getOrNull(i)
        }
        is String -> {
            var i = toLong(idx).toInt()
            if (i < 0) i += target.length
            target.getOrNull(i)?.toString() ?: ""
        }
        is Map<*, *> -> target[idx]
        else -> null
    }

    private suspend fun evalSlice(slice: PyExpr.Slice): Any? {
        val target = evalExpr(slice.target)
        val size = when (target) {
            is List<*> -> target.size
            is String -> target.length
            is PyTuple -> target.items.size
            else -> return null
        }
        val step = slice.step?.let { toLong(evalExpr(it)).toInt() } ?: 1
        val rawStart = slice.start?.let { toLong(evalExpr(it)).toInt() } ?: if (step > 0) 0 else size - 1
        val rawStop = slice.stop?.let { toLong(evalExpr(it)).toInt() } ?: if (step > 0) size else -size - 1
        val start = (if (rawStart < 0) size + rawStart else rawStart).coerceIn(0, size)
        val stop = (if (rawStop < 0) size + rawStop else rawStop).coerceIn(-1, size)
        val indices = mutableListOf<Int>()
        if (step > 0) {
            var i = start
            while (i < stop) { indices.add(i); i += step }
        } else if (step < 0) {
            var i = start.coerceAtMost(size - 1)
            while (i > stop && i >= 0) { indices.add(i); i += step }
        }
        return when (target) {
            is String -> indices.map { target[it] }.joinToString("")
            is List<*> -> indices.map { target[it] }.toMutableList()
            is PyTuple -> PyTuple(indices.map { target.items[it] })
            else -> null
        }
    }

    private fun evalAttr(target: Any?, name: String): Any? {
        return when (target) {
            is PyModule -> target.members[name]
            is PyInstance -> {
                if (target.attrs.containsKey(name)) target.attrs[name]
                else findMethod(target.cls, name)?.let { PyBoundMethod(target, it) }
            }
            is String -> PyBuiltinFn("str.$name") { args, _ ->
                when (name) {
                    "upper" -> target.uppercase()
                    "lower" -> target.lowercase()
                    "strip" -> target.trim()
                    "lstrip" -> target.trimStart()
                    "rstrip" -> target.trimEnd()
                    "split" -> if (args.isEmpty()) target.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
                    else target.split(pyStr(args[0])).toMutableList()
                    "join" -> toList(args.firstOrNull()).joinToString(target) { pyStr(it) }
                    "replace" -> target.replace(pyStr(args.getOrNull(0)), pyStr(args.getOrNull(1)))
                    "startswith" -> target.startsWith(pyStr(args.firstOrNull()))
                    "endswith" -> target.endsWith(pyStr(args.firstOrNull()))
                    "find" -> target.indexOf(pyStr(args.firstOrNull())).toLong()
                    "isdigit" -> target.isNotEmpty() && target.all { it.isDigit() }
                    "isalpha" -> target.isNotEmpty() && target.all { it.isLetter() }
                    "format" -> {
                        var res: String = target
                        args.forEachIndexed { idx, v -> res = res.replace("{$idx}", pyStr(v)).replaceFirst("{}", pyStr(v)) }
                        res
                    }
                    else -> target
                }
            }
            is MutableList<*> -> PyBuiltinFn("list.$name") { args, _ ->
                @Suppress("UNCHECKED_CAST")
                val list = target as MutableList<Any?>
                when (name) {
                    "append" -> { list.add(args.firstOrNull()); null }
                    "extend" -> { list.addAll(toList(args.firstOrNull())); null }
                    "pop" -> if (list.isNotEmpty()) list.removeAt(if (args.isEmpty()) list.lastIndex else toLong(args[0]).toInt()) else null
                    "clear" -> { list.clear(); null }
                    "sort" -> { list.sortWith { a, b -> comparePy(a, b) }; null }
                    "reverse" -> { list.reverse(); null }
                    "count" -> list.count { it == args.firstOrNull() }.toLong()
                    "index" -> list.indexOf(args.firstOrNull()).toLong()
                    else -> null
                }
            }
            is MutableMap<*, *> -> PyBuiltinFn("dict.$name") { args, _ ->
                @Suppress("UNCHECKED_CAST")
                val map = target as MutableMap<Any?, Any?>
                when (name) {
                    "get" -> map[args.getOrNull(0)] ?: args.getOrNull(1)
                    "keys" -> map.keys.toMutableList()
                    "values" -> map.values.toMutableList()
                    "items" -> map.entries.map { PyTuple(listOf(it.key, it.value)) }.toMutableList()
                    "pop" -> map.remove(args.getOrNull(0)) ?: args.getOrNull(1)
                    "clear" -> { map.clear(); null }
                    "update" -> {
                        val other = args.firstOrNull() as? Map<*, *>
                        other?.forEach { (k, v) -> map[k] = v }
                        null
                    }
                    else -> null
                }
            }
            is PyFileHandle -> PyBuiltinFn("file.$name") { args, _ ->
                when (name) {
                    "read" -> if (target.file.exists()) target.file.readText() else ""
                    "readlines" -> if (target.file.exists()) target.file.readLines().toMutableList() else mutableListOf<String>()
                    "write" -> {
                        val text = pyStr(args.firstOrNull())
                        if (target.mode.contains("a")) target.file.appendText(text) else target.file.writeText(text)
                        text.length.toLong()
                    }
                    "close" -> { target.closed = true; null }
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun findMethod(cls: PyClass?, name: String): PyFunction? {
        var cur = cls
        while (cur != null) {
            cur.methods[name]?.let { return it }
            cur = cur.base
        }
        return null
    }

    private suspend fun invokeCallable(callee: Any?, pos: List<Any?>, kw: Map<String, Any?>): Any? {
        return when (callee) {
            is PyBuiltinFn -> callee.fn(pos, kw)
            is PyFunction -> callPyFunction(callee, pos, kw)
            is PyBoundMethod -> callPyFunction(callee.fn, listOf(callee.self) + pos, kw)
            is PyLambda -> {
                val local = HashMap(callee.closure)
                callee.params.forEachIndexed { i, p -> local[p] = pos.getOrNull(i) }
                scopes.addLast(local)
                try { evalExpr(callee.body) } finally { scopes.removeLast() }
            }
            is PyClass -> {
                val inst = PyInstance(callee)
                findMethod(callee, "__init__")?.let { initFn ->
                    callPyFunction(initFn, listOf(inst) + pos, kw)
                }
                inst
            }
            else -> throw PyRaise("TypeError: '${callee?.let { it::class.simpleName } ?: "NoneType"}' object is not callable")
        }
    }

    private suspend fun callPyFunction(fn: PyFunction, pos: List<Any?>, kw: Map<String, Any?>): Any? {
        val local = HashMap(fn.closure)
        fn.params.forEachIndexed { i, (pName, defExpr) ->
            local[pName] = when {
                i < pos.size -> pos[i]
                kw.containsKey(pName) -> kw[pName]
                defExpr != null -> evalExpr(defExpr)
                else -> null
            }
        }
        if (fn.varArg != null) {
            local[fn.varArg] = PyTuple(if (pos.size > fn.params.size) pos.drop(fn.params.size) else emptyList())
        }
        if (fn.kwArg != null) {
            local[fn.kwArg] = kw.toMutableMap()
        }
        scopes.addLast(local)
        return try {
            for (s in fn.body) execStmt(s)
            null
        } catch (r: PyReturn) {
            r.value
        } finally {
            scopes.removeLast()
        }
    }

    private fun registerBuiltins() {
        globals["print"] = PyBuiltinFn("print") { pos, kw ->
            val sep = kw["sep"]?.let { pyStr(it) } ?: " "
            val end = kw["end"]?.let { pyStr(it) } ?: "\n"
            onPrint(pos.joinToString(sep) { pyStr(it) } + end)
            null
        }
        globals["input"] = PyBuiltinFn("input") { pos, _ ->
            if (pos.isNotEmpty()) onPrint(pyStr(pos[0]))
            onReadLine() ?: ""
        }
        globals["len"] = PyBuiltinFn("len") { pos, _ ->
            when (val x = pos.firstOrNull()) {
                is String -> x.length.toLong()
                is Collection<*> -> x.size.toLong()
                is Map<*, *> -> x.size.toLong()
                is PyTuple -> x.items.size.toLong()
                else -> 0L
            }
        }
        globals["range"] = PyBuiltinFn("range") { pos, _ ->
            val start = if (pos.size >= 2) toLong(pos[0]) else 0L
            val stop = if (pos.size >= 2) toLong(pos[1]) else toLong(pos.getOrNull(0))
            val step = if (pos.size >= 3) toLong(pos[2]) else 1L
            val res = mutableListOf<Any?>()
            var i = start
            if (step > 0) while (i < stop && res.size < 100_000) { res.add(i); i += step }
            else if (step < 0) while (i > stop && res.size < 100_000) { res.add(i); i += step }
            res
        }
        globals["int"] = PyBuiltinFn("int") { pos, _ ->
            val v = pos.firstOrNull()
            when (v) {
                is String -> v.trim().toLongOrNull(if (pos.size >= 2) toLong(pos[1]).toInt() else 10) ?: 0L
                else -> toLong(v)
            }
        }
        globals["float"] = PyBuiltinFn("float") { pos, _ -> toDouble(pos.firstOrNull()) }
        globals["str"] = PyBuiltinFn("str") { pos, _ -> pyStr(pos.firstOrNull()) }
        globals["bool"] = PyBuiltinFn("bool") { pos, _ -> toBool(pos.firstOrNull()) }
        globals["list"] = PyBuiltinFn("list") { pos, _ -> toList(pos.firstOrNull()).toMutableList() }
        globals["tuple"] = PyBuiltinFn("tuple") { pos, _ -> PyTuple(toList(pos.firstOrNull())) }
        globals["set"] = PyBuiltinFn("set") { pos, _ -> toList(pos.firstOrNull()).toMutableSet() }
        globals["dict"] = PyBuiltinFn("dict") { _, _ -> linkedMapOf<Any?, Any?>() }
        globals["abs"] = PyBuiltinFn("abs") { pos, _ ->
            val v = pos.firstOrNull()
            if (v is Double) abs(v) else abs(toLong(v))
        }
        globals["round"] = PyBuiltinFn("round") { pos, _ ->
            val n = toDouble(pos.firstOrNull())
            val d = if (pos.size >= 2) toLong(pos[1]).toInt() else 0
            if (d == 0) round(n).toLong()
            else {
                val factor = 10.0.pow(d)
                round(n * factor) / factor
            }
        }
        globals["pow"] = PyBuiltinFn("pow") { pos, _ -> toDouble(pos.getOrNull(0)).pow(toDouble(pos.getOrNull(1))) }
        globals["sum"] = PyBuiltinFn("sum") { pos, _ ->
            val items = toList(pos.firstOrNull())
            if (items.any { it is Double }) items.sumOf { toDouble(it) } else items.sumOf { toLong(it) }
        }
        globals["min"] = PyBuiltinFn("min") { pos, _ ->
            val items = if (pos.size == 1) toList(pos[0]) else pos
            items.minWithOrNull { a, b -> comparePy(a, b) }
        }
        globals["max"] = PyBuiltinFn("max") { pos, _ ->
            val items = if (pos.size == 1) toList(pos[0]) else pos
            items.maxWithOrNull { a, b -> comparePy(a, b) }
        }
        globals["sorted"] = PyBuiltinFn("sorted") { pos, kw ->
            val rev = toBool(kw["reverse"])
            val list = toList(pos.firstOrNull()).sortedWith { a, b -> comparePy(a, b) }
            if (rev) list.reversed().toMutableList() else list.toMutableList()
        }
        globals["reversed"] = PyBuiltinFn("reversed") { pos, _ -> toList(pos.firstOrNull()).reversed().toMutableList() }
        globals["enumerate"] = PyBuiltinFn("enumerate") { pos, _ ->
            val start = if (pos.size >= 2) toLong(pos[1]) else 0L
            toList(pos.firstOrNull()).mapIndexed { idx, v -> PyTuple(listOf(start + idx, v)) }.toMutableList()
        }
        globals["zip"] = PyBuiltinFn("zip") { pos, _ ->
            val lists = pos.map { toList(it) }
            val minSize = lists.minOfOrNull { it.size } ?: 0
            (0 until minSize).map { i -> PyTuple(lists.map { it[i] }) }.toMutableList()
        }
        globals["map"] = PyBuiltinFn("map") { pos, _ ->
            val fn = pos.getOrNull(0)
            toList(pos.getOrNull(1)).map { invokeCallable(fn, listOf(it), emptyMap()) }.toMutableList()
        }
        globals["filter"] = PyBuiltinFn("filter") { pos, _ ->
            val fn = pos.getOrNull(0)
            val res = mutableListOf<Any?>()
            for (item in toList(pos.getOrNull(1))) {
                if (toBool(invokeCallable(fn, listOf(item), emptyMap()))) res.add(item)
            }
            res
        }
        globals["any"] = PyBuiltinFn("any") { pos, _ -> toList(pos.firstOrNull()).any { toBool(it) } }
        globals["all"] = PyBuiltinFn("all") { pos, _ -> toList(pos.firstOrNull()).all { toBool(it) } }
        globals["ord"] = PyBuiltinFn("ord") { pos, _ -> pyStr(pos.firstOrNull()).firstOrNull()?.code?.toLong() ?: 0L }
        globals["chr"] = PyBuiltinFn("chr") { pos, _ -> toLong(pos.firstOrNull()).toInt().toChar().toString() }
        globals["hex"] = PyBuiltinFn("hex") { pos, _ -> "0x" + toLong(pos.firstOrNull()).toString(16) }
        globals["bin"] = PyBuiltinFn("bin") { pos, _ -> "0b" + toLong(pos.firstOrNull()).toString(2) }
        globals["type"] = PyBuiltinFn("type") { pos, _ ->
            when (val x = pos.firstOrNull()) {
                null -> "<class 'NoneType'>"
                is Long -> "<class 'int'>"
                is Double -> "<class 'float'>"
                is Boolean -> "<class 'bool'>"
                is String -> "<class 'str'>"
                is List<*> -> "<class 'list'>"
                is Map<*, *> -> "<class 'dict'>"
                is PyTuple -> "<class 'tuple'>"
                is PyInstance -> "<class '${x.cls.name}'>"
                else -> "<class 'object'>"
            }
        }
        globals["open"] = PyBuiltinFn("open") { pos, _ ->
            val path = pyStr(pos.getOrNull(0))
            val mode = pos.getOrNull(1)?.let { pyStr(it) } ?: "r"
            val f = env.resolvePath(cwd, path)
            if (mode.contains("w")) {
                f.parentFile?.mkdirs()
                f.writeText("")
            }
            PyFileHandle(f, mode)
        }
        globals["quit"] = PyBuiltinFn("quit") { _, _ -> throw PyExit(0) }
        globals["exit"] = PyBuiltinFn("exit") { pos, _ -> throw PyExit(pos.firstOrNull()?.let { toLong(it).toInt() } ?: 0) }
    }

    private suspend fun loadModule(name: String): PyModule {
        val base = name.substringBefore('.')
        return when (base) {
            "math" -> PyModule("math", mutableMapOf(
                "pi" to Math.PI, "e" to Math.E, "tau" to 2 * Math.PI,
                "sqrt" to PyBuiltinFn("sqrt") { p, _ -> sqrt(toDouble(p.firstOrNull())) },
                "sin" to PyBuiltinFn("sin") { p, _ -> sin(toDouble(p.firstOrNull())) },
                "cos" to PyBuiltinFn("cos") { p, _ -> cos(toDouble(p.firstOrNull())) },
                "tan" to PyBuiltinFn("tan") { p, _ -> tan(toDouble(p.firstOrNull())) },
                "floor" to PyBuiltinFn("floor") { p, _ -> floor(toDouble(p.firstOrNull())).toLong() },
                "ceil" to PyBuiltinFn("ceil") { p, _ -> ceil(toDouble(p.firstOrNull())).toLong() },
                "log" to PyBuiltinFn("log") { p, _ -> ln(toDouble(p.firstOrNull())) },
                "log10" to PyBuiltinFn("log10") { p, _ -> log10(toDouble(p.firstOrNull())) },
                "exp" to PyBuiltinFn("exp") { p, _ -> exp(toDouble(p.firstOrNull())) },
                "factorial" to PyBuiltinFn("factorial") { p, _ ->
                    var r = 1L
                    for (k in 2..toLong(p.firstOrNull()).toInt()) r *= k
                    r
                },
                "gcd" to PyBuiltinFn("gcd") { p, _ ->
                    var a = abs(toLong(p.getOrNull(0))); var b = abs(toLong(p.getOrNull(1)))
                    while (b != 0L) { val t = b; b = a % b; a = t }
                    a
                }
            ))
            "sys" -> PyModule("sys", mutableMapOf(
                "version" to "3.12.4 (Termux++ Android)",
                "platform" to "linux-aarch64-termux",
                "argv" to scriptArgs.toMutableList(),
                "exit" to PyBuiltinFn("sys.exit") { p, _ -> throw PyExit(p.firstOrNull()?.let { toLong(it).toInt() } ?: 0) }
            ))
            "os" -> PyModule("os", mutableMapOf(
                "getcwd" to PyBuiltinFn("os.getcwd") { _, _ -> cwd.absolutePath },
                "listdir" to PyBuiltinFn("os.listdir") { p, _ ->
                    val target = if (p.isEmpty()) cwd else env.resolvePath(cwd, pyStr(p[0]))
                    (target.list()?.toList() ?: emptyList()).toMutableList()
                },
                "makedirs" to PyBuiltinFn("os.makedirs") { p, _ -> env.resolvePath(cwd, pyStr(p[0])).mkdirs(); null },
                "remove" to PyBuiltinFn("os.remove") { p, _ -> env.resolvePath(cwd, pyStr(p[0])).delete(); null },
                "environ" to env.baseEnvMap().toMutableMap()
            ))
            "random" -> PyModule("random", mutableMapOf(
                "random" to PyBuiltinFn("random") { _, _ -> rng.nextDouble() },
                "randint" to PyBuiltinFn("randint") { p, _ ->
                    val a = toLong(p.getOrNull(0)); val b = toLong(p.getOrNull(1))
                    rng.nextLong(a, b + 1)
                },
                "choice" to PyBuiltinFn("choice") { p, _ ->
                    val l = toList(p.firstOrNull())
                    if (l.isEmpty()) null else l[rng.nextInt(l.size)]
                },
                "shuffle" to PyBuiltinFn("shuffle") { p, _ ->
                    @Suppress("UNCHECKED_CAST")
                    (p.firstOrNull() as? MutableList<Any?>)?.shuffle(rng)
                    null
                }
            ))
            "time" -> PyModule("time", mutableMapOf(
                "time" to PyBuiltinFn("time.time") { _, _ -> System.currentTimeMillis() / 1000.0 },
                "sleep" to PyBuiltinFn("time.sleep") { p, _ ->
                    kotlinx.coroutines.delay((toDouble(p.firstOrNull()) * 1000).toLong())
                    null
                },
                "ctime" to PyBuiltinFn("time.ctime") { _, _ -> Date().toString() }
            ))
            "datetime" -> PyModule("datetime", mutableMapOf(
                "now" to PyBuiltinFn("datetime.now") { _, _ ->
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                }
            ))
            "json" -> PyModule("json", mutableMapOf(
                "dumps" to PyBuiltinFn("json.dumps") { p, kw ->
                    val indent = kw["indent"]?.let { toLong(it).toInt() }
                    val j = toJsonAny(p.firstOrNull())
                    when (j) {
                        is JSONObject -> if (indent != null) j.toString(indent) else j.toString()
                        is JSONArray -> if (indent != null) j.toString(indent) else j.toString()
                        else -> j.toString()
                    }
                },
                "loads" to PyBuiltinFn("json.loads") { p, _ ->
                    val s = pyStr(p.firstOrNull()).trim()
                    when {
                        s.startsWith("{") -> fromJsonObj(JSONObject(s))
                        s.startsWith("[") -> fromJsonArr(JSONArray(s))
                        else -> s
                    }
                }
            ))
            "hashlib" -> PyModule("hashlib", mutableMapOf(
                "sha256" to PyBuiltinFn("sha256") { p, _ ->
                    val bytes = MessageDigest.getInstance("SHA-256").digest(pyStr(p.firstOrNull()).toByteArray())
                    PyModule("hash", mutableMapOf("hexdigest" to PyBuiltinFn("hexdigest") { _, _ ->
                        bytes.joinToString("") { "%02x".format(it) }
                    }))
                },
                "md5" to PyBuiltinFn("md5") { p, _ ->
                    val bytes = MessageDigest.getInstance("MD5").digest(pyStr(p.firstOrNull()).toByteArray())
                    PyModule("hash", mutableMapOf("hexdigest" to PyBuiltinFn("hexdigest") { _, _ ->
                        bytes.joinToString("") { "%02x".format(it) }
                    }))
                }
            ))
            "base64" -> PyModule("base64", mutableMapOf(
                "b64encode" to PyBuiltinFn("b64encode") { p, _ ->
                    Base64.getEncoder().encodeToString(pyStr(p.firstOrNull()).toByteArray())
                },
                "b64decode" to PyBuiltinFn("b64decode") { p, _ ->
                    String(Base64.getDecoder().decode(pyStr(p.firstOrNull())))
                }
            ))
            "urllib" -> PyModule("urllib", mutableMapOf(
                "request" to PyModule("urllib.request", mutableMapOf(
                    "urlopen" to PyBuiltinFn("urlopen") { p, _ ->
                        val url = pyStr(p.firstOrNull())
                        val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() ?: "" }
                        }
                        PyModule("response", mutableMapOf("read" to PyBuiltinFn("read") { _, _ -> body }))
                    }
                ))
            ))
            "sqlite3" -> PyModule("sqlite3", mutableMapOf(
                "connect" to PyBuiltinFn("connect") { p, _ ->
                    val dbPath = env.resolvePath(cwd, pyStr(p.firstOrNull())).absolutePath
                    val db = SQLiteDatabase.openOrCreateDatabase(dbPath, null)
                    PyModule("conn", mutableMapOf(
                        "execute" to PyBuiltinFn("execute") { q, _ ->
                            val sql = pyStr(q.firstOrNull()).trim()
                            if (sql.uppercase().startsWith("SELECT") || sql.uppercase().startsWith("PRAGMA")) {
                                val c = db.rawQuery(sql, null)
                                val rows = mutableListOf<Any?>()
                                c.use {
                                    while (it.moveToNext()) {
                                        val row = (0 until it.columnCount).map { col -> it.getString(col) }
                                        rows.add(PyTuple(row))
                                    }
                                }
                                PyModule("cursor", mutableMapOf("fetchall" to PyBuiltinFn("fetchall") { _, _ -> rows }))
                            } else {
                                db.execSQL(sql)
                                PyModule("cursor", mutableMapOf("fetchall" to PyBuiltinFn("fetchall") { _, _ -> mutableListOf<Any?>() }))
                            }
                        },
                        "close" to PyBuiltinFn("close") { _, _ -> db.close(); null }
                    ))
                }
            ))
            "re" -> PyModule("re", mutableMapOf(
                "findall" to PyBuiltinFn("findall") { p, _ ->
                    Regex(pyStr(p.getOrNull(0))).findAll(pyStr(p.getOrNull(1))).map { it.value }.toMutableList()
                },
                "sub" to PyBuiltinFn("sub") { p, _ ->
                    Regex(pyStr(p.getOrNull(0))).replace(pyStr(p.getOrNull(2)), pyStr(p.getOrNull(1)))
                }
            ))
            "statistics" -> PyModule("statistics", mutableMapOf(
                "mean" to PyBuiltinFn("mean") { p, _ ->
                    val l = toList(p.firstOrNull()).map { toDouble(it) }
                    if (l.isEmpty()) 0.0 else l.average()
                }
            ))
            else -> {
                // Check local .py file in cwd or site-packages
                val pyFile = listOf(File(cwd, "$base.py"), File(env.pythonSitePackagesDir, "$base.py"))
                    .firstOrNull { it.exists() && it.isFile }
                if (pyFile != null) {
                    val modRuntime = PythonRuntime(env, cwd, emptyList(), onPrint, onReadLine, isCancelled)
                    modRuntime.runScript(pyFile.readText())
                    PyModule(base, modRuntime.globals)
                } else {
                    throw PyRaise("ModuleNotFoundError: No module named '$name'")
                }
            }
        }
    }

    private fun toJsonAny(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { v.forEach { (k, valItem) -> put(k.toString(), toJsonAny(valItem)) } }
        is List<*> -> JSONArray().apply { v.forEach { put(toJsonAny(it)) } }
        is PyTuple -> JSONArray().apply { v.items.forEach { put(toJsonAny(it)) } }
        else -> v
    }

    private fun fromJsonObj(obj: JSONObject): MutableMap<Any?, Any?> {
        val m = linkedMapOf<Any?, Any?>()
        for (k in obj.keys()) {
            m[k] = when (val v = obj.get(k)) {
                is JSONObject -> fromJsonObj(v)
                is JSONArray -> fromJsonArr(v)
                JSONObject.NULL -> null
                is Int -> v.toLong()
                else -> v
            }
        }
        return m
    }

    private fun fromJsonArr(arr: JSONArray): MutableList<Any?> {
        val l = mutableListOf<Any?>()
        for (i in 0 until arr.length()) {
            l.add(
                when (val v = arr.get(i)) {
                    is JSONObject -> fromJsonObj(v)
                    is JSONArray -> fromJsonArr(v)
                    JSONObject.NULL -> null
                    is Int -> v.toLong()
                    else -> v
                }
            )
        }
        return l
    }

    private fun toLong(v: Any?): Long = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is Boolean -> if (v) 1L else 0L
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun toDouble(v: Any?): Double = when (v) {
        is Double -> v
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun toBool(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Long -> v != 0L
        is Double -> v != 0.0
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        is PyTuple -> v.items.isNotEmpty()
        else -> true
    }

    private fun toList(v: Any?): List<Any?> = when (v) {
        is List<*> -> v
        is PyTuple -> v.items
        is Set<*> -> v.toList()
        is Map<*, *> -> v.keys.toList()
        is String -> v.map { it.toString() }
        else -> emptyList()
    }

    private fun comparePy(a: Any?, b: Any?): Int = when {
        a is String && b is String -> a.compareTo(b)
        a is Number && b is Number -> toDouble(a).compareTo(toDouble(b))
        else -> pyStr(a).compareTo(pyStr(b))
    }

    private fun pyStr(v: Any?): String = when (v) {
        null -> "None"
        is Boolean -> if (v) "True" else "False"
        is PyTuple -> if (v.items.size == 1) "(${pyRepr(v.items[0])},)" else "(${v.items.joinToString(", ") { pyRepr(it) }})"
        is List<*> -> "[${v.joinToString(", ") { pyRepr(it) }}]"
        is Map<*, *> -> "{${v.entries.joinToString(", ") { "${pyRepr(it.key)}: ${pyRepr(it.value)}" }}}"
        is Set<*> -> "{${v.joinToString(", ") { pyRepr(it) }}}"
        else -> v.toString()
    }

    private fun pyRepr(v: Any?): String = when (v) {
        is String -> "'$v'"
        else -> pyStr(v)
    }
}
