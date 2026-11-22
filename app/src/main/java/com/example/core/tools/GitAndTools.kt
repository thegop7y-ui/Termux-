package com.example.core.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.core.fs.TermuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

interface AndroidBridge {
    fun showToast(message: String)
    fun getClipboard(): String
    fun setClipboard(text: String)
    fun openUrl(url: String)
    fun openStorageSetup()
}

class ToolsExecutor(
    private val context: Context,
    private val env: TermuxEnvironment,
    private val bridge: AndroidBridge
) {
    private val http = OkHttpClient()

    suspend fun runCurl(args: List<String>, cwd: File, onPrint: (String) -> Unit): Int {
        var method = "GET"
        var headOnly = false
        var outputFile: String? = null
        var data: String? = null
        var silent = false
        val headers = mutableListOf<Pair<String, String>>()
        var url: String? = null

        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-I", "--head" -> headOnly = true
                "-s", "--silent" -> silent = true
                "-L", "--location" -> Unit
                "-X", "--request" -> { method = args.getOrNull(++i)?.uppercase() ?: "GET" }
                "-o", "--output" -> { outputFile = args.getOrNull(++i) }
                "-O" -> { /* derived from url */ }
                "-d", "--data" -> { data = args.getOrNull(++i); if (method == "GET") method = "POST" }
                "-H", "--header" -> {
                    val h = args.getOrNull(++i) ?: ""
                    val idx = h.indexOf(':')
                    if (idx > 0) headers.add(h.substring(0, idx).trim() to h.substring(idx + 1).trim())
                }
                else -> if (!a.startsWith("-")) url = a
            }
            i++
        }
        val targetUrl = url ?: run {
            onPrint("curl: try 'curl --help' for more information (missing URL)\n")
            return 2
        }
        val fullUrl = if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) targetUrl else "https://$targetUrl"

        return withContext(Dispatchers.IO) {
            try {
                val rb = Request.Builder().url(fullUrl).header("User-Agent", "curl/8.9.1 Termux++")
                headers.forEach { (k, v) -> rb.addHeader(k, v) }
                if (headOnly) {
                    rb.head()
                } else if (data != null) {
                    rb.method(method, data.toRequestBody("application/json".toMediaTypeOrNull()))
                } else {
                    rb.method(method, null)
                }
                http.newCall(rb.build()).execute().use { resp ->
                    if (headOnly) {
                        onPrint("HTTP/${resp.protocol} ${resp.code} ${resp.message}\n")
                        for ((k, v) in resp.headers) onPrint("$k: $v\n")
                    } else {
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        if (outputFile != null || "-O" in args) {
                            val fname = outputFile ?: Uri.parse(fullUrl).lastPathSegment?.ifEmpty { "index.html" } ?: "index.html"
                            val outF = env.resolvePath(cwd, fname)
                            outF.parentFile?.mkdirs()
                            outF.writeBytes(bytes)
                            if (!silent) onPrint("Saved ${bytes.size} bytes to ${outF.name}\n")
                        } else {
                            val text = bytes.toString(Charsets.UTF_8)
                            onPrint(text)
                            if (!text.endsWith("\n")) onPrint("\n")
                        }
                    }
                    0
                }
            } catch (e: Exception) {
                onPrint("curl: (${e.javaClass.simpleName}) ${e.message}\n")
                1
            }
        }
    }

    suspend fun runWget(args: List<String>, cwd: File, onPrint: (String) -> Unit): Int {
        var outPath: String? = null
        var url: String? = null
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-O" -> outPath = args.getOrNull(++i)
                else -> if (!a.startsWith("-")) url = a
            }
            i++
        }
        val u = url ?: run { onPrint("wget: missing URL\n"); return 1 }
        val fname = outPath ?: Uri.parse(u).lastPathSegment?.ifEmpty { "index.html" } ?: "index.html"
        onPrint("--${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}--  $u\n")
        return runCurl(listOf("-L", "-o", fname, u), cwd, onPrint)
    }

    suspend fun runGit(args: List<String>, cwd: File, onPrint: (String) -> Unit): Int {
        val sub = args.firstOrNull() ?: run {
            onPrint("usage: git <command> [<args>]\n\nCommands: init, status, add, commit, log, branch, checkout, diff, clone\n")
            return 0
        }
        fun findGitRoot(start: File): File? {
            var cur: File? = start
            while (cur != null) {
                if (File(cur, ".git").isDirectory) return cur
                cur = cur.parentFile
            }
            return null
        }

        return when (sub) {
            "init" -> {
                val target = if (args.size > 1) env.resolvePath(cwd, args[1]) else cwd
                val gitDir = File(target, ".git")
                gitDir.mkdirs()
                File(gitDir, "HEAD").writeText("ref: refs/heads/main\n")
                File(gitDir, "refs/heads").mkdirs()
                File(gitDir, "objects").mkdirs()
                File(gitDir, "index.json").writeText("{}")
                File(gitDir, "commits.json").writeText("[]")
                onPrint("Initialized empty Git repository in ${gitDir.absolutePath}/\n")
                0
            }
            "status" -> {
                val root = findGitRoot(cwd) ?: run { onPrint("fatal: not a git repository\n"); return 128 }
                val gitDir = File(root, ".git")
                val head = File(gitDir, "HEAD").readText().trim().substringAfterLast('/')
                val staged = JSONObject(File(gitDir, "index.json").takeIf { it.exists() }?.readText() ?: "{}")
                val commits = JSONArray(File(gitDir, "commits.json").takeIf { it.exists() }?.readText() ?: "[]")
                val lastSnap = if (commits.length() > 0) commits.getJSONObject(commits.length() - 1).getJSONObject("files") else JSONObject()

                onPrint("On branch \u001B[32m$head\u001B[0m\n")
                if (commits.length() == 0) onPrint("No commits yet\n")

                val allFiles = root.walkTopDown().filter { it.isFile && !it.absolutePath.contains("/.git") }.toList()
                val modified = mutableListOf<String>()
                val untracked = mutableListOf<String>()
                for (f in allFiles) {
                    val rel = f.relativeTo(root).path
                    val h = sha1(f.readBytes())
                    if (lastSnap.has(rel)) {
                        if (lastSnap.getString(rel) != h && staged.optString(rel) != h) modified.add(rel)
                    } else if (!staged.has(rel)) {
                        untracked.add(rel)
                    }
                }
                if (staged.length() > 0) {
                    onPrint("\nChanges to be committed:\n")
                    for (k in staged.keys()) onPrint("  \u001B[32mstaged:   $k\u001B[0m\n")
                }
                if (modified.isNotEmpty()) {
                    onPrint("\nChanges not staged for commit:\n")
                    modified.forEach { onPrint("  \u001B[31mmodified: $it\u001B[0m\n") }
                }
                if (untracked.isNotEmpty()) {
                    onPrint("\nUntracked files:\n")
                    untracked.forEach { onPrint("  \u001B[31m$it\u001B[0m\n") }
                }
                if (staged.length() == 0 && modified.isEmpty() && untracked.isEmpty()) {
                    onPrint("nothing to commit, working tree clean\n")
                }
                0
            }
            "add" -> {
                val root = findGitRoot(cwd) ?: run { onPrint("fatal: not a git repository\n"); return 128 }
                val gitDir = File(root, ".git")
                val staged = JSONObject(File(gitDir, "index.json").takeIf { it.exists() }?.readText() ?: "{}")
                val specs = args.drop(1).ifEmpty { listOf(".") }
                for (spec in specs) {
                    val target = env.resolvePath(cwd, spec)
                    if (!target.exists()) { onPrint("fatal: pathspec '$spec' did not match any files\n"); return 128 }
                    val files = if (target.isDirectory) target.walkTopDown().filter { it.isFile && !it.absolutePath.contains("/.git") }.toList() else listOf(target)
                    for (f in files) {
                        val rel = f.relativeTo(root).path
                        val bytes = f.readBytes()
                        val h = sha1(bytes)
                        File(gitDir, "objects/$h").writeBytes(bytes)
                        staged.put(rel, h)
                    }
                }
                File(gitDir, "index.json").writeText(staged.toString())
                0
            }
            "commit" -> {
                val root = findGitRoot(cwd) ?: run { onPrint("fatal: not a git repository\n"); return 128 }
                val mIdx = args.indexOf("-m")
                val msg = if (mIdx >= 0) args.getOrNull(mIdx + 1) else null
                if (msg.isNullOrBlank()) { onPrint("error: switch `m' requires a value\n"); return 1 }
                val gitDir = File(root, ".git")
                val staged = JSONObject(File(gitDir, "index.json").takeIf { it.exists() }?.readText() ?: "{}")
                val commits = JSONArray(File(gitDir, "commits.json").takeIf { it.exists() }?.readText() ?: "[]")
                val merged = if (commits.length() > 0) JSONObject(commits.getJSONObject(commits.length() - 1).getJSONObject("files").toString()) else JSONObject()
                for (k in staged.keys()) merged.put(k, staged.getString(k))
                val hash = sha1("$msg${System.currentTimeMillis()}".toByteArray()).take(7)
                val commitObj = JSONObject().apply {
                    put("hash", hash)
                    put("message", msg)
                    put("date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    put("files", merged)
                }
                commits.put(commitObj)
                File(gitDir, "commits.json").writeText(commits.toString())
                File(gitDir, "index.json").writeText("{}")
                onPrint("[main $hash] $msg\n ${staged.length()} file(s) changed\n")
                0
            }
            "log" -> {
                val root = findGitRoot(cwd) ?: run { onPrint("fatal: not a git repository\n"); return 128 }
                val commits = JSONArray(File(root, ".git/commits.json").takeIf { it.exists() }?.readText() ?: "[]")
                if (commits.length() == 0) { onPrint("fatal: your current branch does not have any commits yet\n"); return 128 }
                for (idx in (commits.length() - 1) downTo 0) {
                    val c = commits.getJSONObject(idx)
                    onPrint("\u001B[33mcommit ${c.getString("hash")}\u001B[0m\n")
                    onPrint("Date:   ${c.getString("date")}\n\n    ${c.getString("message")}\n\n")
                }
                0
            }
            "branch" -> {
                val root = findGitRoot(cwd) ?: run { onPrint("fatal: not a git repository\n"); return 128 }
                val head = File(root, ".git/HEAD").readText().trim().substringAfterLast('/')
                onPrint("* \u001B[32m$head\u001B[0m\n")
                0
            }
            "clone" -> {
                val url = args.getOrNull(1) ?: run { onPrint("usage: git clone <url>\n"); return 1 }
                val clean = url.removeSuffix(".git").removeSuffix("/")
                val repoName = clean.substringAfterLast('/')
                val destDir = env.resolvePath(cwd, args.getOrNull(2) ?: repoName)
                val zipUrl = if (clean.contains("github.com")) "$clean/archive/refs/heads/main.zip" else url
                onPrint("Cloning into '${destDir.name}'...\n")
                withContext(Dispatchers.IO) {
                    try {
                        http.newCall(Request.Builder().url(zipUrl).build()).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                onPrint("fatal: repository '$url' not reachable (HTTP ${resp.code})\n")
                                return@withContext 128
                            }
                            destDir.mkdirs()
                            ZipInputStream(resp.body!!.byteStream()).use { zis ->
                                var entry: ZipEntry? = zis.nextEntry
                                while (entry != null) {
                                    val rel = entry.name.substringAfter('/')
                                    if (rel.isNotEmpty()) {
                                        val out = File(destDir, rel)
                                        if (entry.isDirectory) out.mkdirs()
                                        else {
                                            out.parentFile?.mkdirs()
                                            FileOutputStream(out).use { zis.copyTo(it) }
                                        }
                                    }
                                    entry = zis.nextEntry
                                }
                            }
                            runGit(listOf("init"), destDir) {}
                            onPrint("done.\n")
                            0
                        }
                    } catch (e: Exception) {
                        onPrint("fatal: ${e.message}\n")
                        128
                    }
                }
            }
            else -> { onPrint("git: '$sub' is not a git command.\n"); 1 }
        }
    }

    suspend fun runPip(args: List<String>, onPrint: (String) -> Unit): Int {
        val sub = args.firstOrNull() ?: run {
            onPrint("Usage: pip <command> [options]\n\nCommands:\n  install    Install packages\n  uninstall  Uninstall packages\n  list       List installed packages\n  show       Show information about installed packages\n")
            return 0
        }
        val siteDir = env.pythonSitePackagesDir
        return when (sub) {
            "list" -> {
                onPrint("Package         Version\n--------------- -------\n")
                onPrint("pip             24.2\n")
                siteDir.listFiles()?.filter { it.name.endsWith(".py") }?.forEach {
                    onPrint(String.format(Locale.US, "%-15s %s\n", it.nameWithoutExtension, "1.0.0"))
                }
                0
            }
            "install" -> {
                val pkg = args.getOrNull(1) ?: run { onPrint("ERROR: You must give at least one requirement to install\n"); return 1 }
                onPrint("Collecting $pkg\n")
                withContext(Dispatchers.IO) {
                    try {
                        val req = Request.Builder().url("https://pypi.org/pypi/$pkg/json").build()
                        http.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val json = JSONObject(resp.body!!.string())
                                val info = json.getJSONObject("info")
                                val ver = info.getString("version")
                                val summary = info.optString("summary", "")
                                onPrint("  Downloading $pkg-$ver (PyPI metadata verified)\n")
                                File(siteDir, "${pkg.replace('-', '_')}.py").writeText(
                                    "# Installed via pip on Termux++ ($pkg $ver)\n# $summary\n__version__ = '$ver'\n"
                                )
                                onPrint("\u001B[32mSuccessfully installed $pkg-$ver\u001B[0m\n")
                                0
                            } else {
                                onPrint("\u001B[31mERROR: Could not find a version that satisfies the requirement $pkg\u001B[0m\n")
                                1
                            }
                        }
                    } catch (e: Exception) {
                        onPrint("WARNING: Network unavailable (${e.message}), creating offline stub...\n")
                        File(siteDir, "${pkg.replace('-', '_')}.py").writeText("# Offline stub for $pkg\n__version__ = '0.1.0'\n")
                        onPrint("Successfully installed $pkg-0.1.0\n")
                        0
                    }
                }
            }
            "uninstall" -> {
                val pkg = args.getOrNull(1) ?: return 1
                val f = File(siteDir, "${pkg.replace('-', '_')}.py")
                if (f.delete()) onPrint("Successfully uninstalled $pkg\n") else onPrint("WARNING: Skipping $pkg as it is not installed.\n")
                0
            }
            else -> { onPrint("pip: unknown command '$sub'\n"); 1 }
        }
    }

    suspend fun runSqlite3(
        args: List<String>,
        cwd: File,
        onPrint: (String) -> Unit,
        onReadLine: suspend () -> String?
    ): Int {
        val dbArg = args.firstOrNull { !it.startsWith("-") } ?: ":memory:"
        val db = if (dbArg == ":memory:") SQLiteDatabase.create(null)
        else SQLiteDatabase.openOrCreateDatabase(env.resolvePath(cwd, dbArg), null)

        fun execOne(sql: String) {
            val trimmed = sql.trim().trimEnd(';')
            if (trimmed.isEmpty()) return
            if (trimmed == ".tables") {
                val c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null)
                c.use { while (it.moveToNext()) onPrint(it.getString(0) + "  ") }
                onPrint("\n")
                return
            }
            if (trimmed.uppercase().startsWith("SELECT") || trimmed.uppercase().startsWith("PRAGMA")) {
                val c = db.rawQuery(trimmed, null)
                c.use {
                    while (it.moveToNext()) {
                        val row = (0 until it.columnCount).joinToString("|") { col -> it.getString(col) ?: "" }
                        onPrint("$row\n")
                    }
                }
            } else {
                db.execSQL(trimmed)
            }
        }

        return try {
            val inlineSql = args.drop(1).filter { !it.startsWith("-") }
            if (inlineSql.isNotEmpty()) {
                for (q in inlineSql.joinToString(" ").split(";")) execOne(q)
            } else {
                onPrint("SQLite version 3.46.1 (Termux++)\nEnter \".quit\" to exit.\n")
                while (true) {
                    onPrint("sqlite> ")
                    val line = onReadLine() ?: break
                    val t = line.trim()
                    if (t == ".quit" || t == ".exit") break
                    try {
                        for (q in t.split(";")) execOne(q)
                    } catch (e: Exception) {
                        onPrint("Error: ${e.message}\n")
                    }
                }
            }
            0
        } catch (e: Exception) {
            onPrint("Error: ${e.message}\n")
            1
        } finally {
            db.close()
        }
    }

    fun runNeofetch(onPrint: (String) -> Unit): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMb = mi.totalMem / (1024 * 1024)
        val usedMb = (mi.totalMem - mi.availMem) / (1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()
        val pkgCount = env.getInstalledPackages().size
        val stat = StatFs(env.homeDir.absolutePath)
        val freeGb = (stat.availableBytes / (1024.0 * 1024 * 1024))
        val infoLines = listOf(
            "\u001B[1;32mu0_a100@termux-cpp\u001B[0m",
            "-------------------",
            "\u001B[1;32mOS\u001B[0m: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "\u001B[1;32mHost\u001B[0m: ${Build.MANUFACTURER} ${Build.MODEL}",
            "\u001B[1;32mKernel\u001B[0m: Linux ${System.getProperty("os.version") ?: "6.1"} ${Build.SUPPORTED_ABIS.firstOrNull() ?: "aarch64"}",
            "\u001B[1;32mPackages\u001B[0m: $pkgCount (dpkg)",
            "\u001B[1;32mShell\u001B[0m: bash 5.2.32",
            "\u001B[1;32mCPU\u001B[0m: $cores cores (${Build.HARDWARE})",
            "\u001B[1;32mMemory\u001B[0m: ${usedMb}MiB / ${totalMb}MiB",
            "\u001B[1;32mStorage\u001B[0m: ${String.format(Locale.US, "%.1f", freeGb)} GiB free"
        )
        val logo = listOf(
            "   \u001B[1;32m.-/+oossssoo+/-.\u001B[0m      ",
            " \u001B[1;32m:+ssssssssssssssss+:\u001B[0m    ",
            "\u001B[1;32m-+sssssss+:\u001B[0m    \u001B[1;32m:+ssss+-\u001B[0m  ",
            "\u001B[1;32m+sssss+:\u001B[0m   \u001B[1;36m>_++\u001B[0m  \u001B[1;32m:ssss+\u001B[0m  ",
            "\u001B[1;32m+sssss+:\u001B[0m         \u001B[1;32m:ssss+\u001B[0m  ",
            "\u001B[1;32m-+sssssss+:  :+sssssss+-\u001B[0m ",
            " \u001B[1;32m:+ssssssssssssssss+:\u001B[0m    ",
            "   \u001B[1;32m.-/+oossssoo+/-.\u001B[0m      ",
            "                         ",
            "                         "
        )
        for (i in infoLines.indices) {
            onPrint("${logo[i]}${infoLines[i]}\n")
        }
        onPrint("\n")
        return 0
    }

    fun runTermuxApi(cmd: String, args: List<String>, onPrint: (String) -> Unit): Int {
        when (cmd) {
            "termux-battery-status" -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                onPrint(JSONObject().apply {
                    put("percentage", pct)
                    put("status", if (charging) "CHARGING" else "DISCHARGING")
                }.toString(2) + "\n")
            }
            "termux-toast" -> {
                val msg = args.joinToString(" ").ifEmpty { "Termux++" }
                bridge.showToast(msg)
            }
            "termux-vibrate" -> {
                val ms = args.indexOf("-d").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toLongOrNull() } ?: 250L
                val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(ms.coerceIn(20, 2000), VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
            "termux-clipboard-get" -> onPrint(bridge.getClipboard() + "\n")
            "termux-clipboard-set" -> bridge.setClipboard(args.joinToString(" "))
            "termux-open-url" -> args.firstOrNull()?.let { bridge.openUrl(it) }
            "termux-setup-storage" -> {
                bridge.openStorageSetup()
                onPrint("Opened Storage Access Framework picker. Granted files are placed in ~/storage/shared/\n")
            }
            "termux-device-info" -> {
                onPrint(JSONObject().apply {
                    put("manufacturer", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    put("android", Build.VERSION.RELEASE)
                    put("sdk", Build.VERSION.SDK_INT)
                    put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
                }.toString(2) + "\n")
            }
        }
        return 0
    }

    fun runJq(args: List<String>, stdin: String, cwd: File, onPrint: (String) -> Unit): Int {
        val filter = args.firstOrNull { !it.startsWith("-") } ?: "."
        val fileArg = args.drop(1).firstOrNull { !it.startsWith("-") }
        val input = if (fileArg != null) env.resolvePath(cwd, fileArg).readText() else stdin
        return try {
            val trimmed = input.trim()
            val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
            var current: Any? = root
            if (filter != ".") {
                for (part in filter.trimStart('.').split('.').filter { it.isNotEmpty() }) {
                    current = when (current) {
                        is JSONObject -> current.opt(part)
                        is JSONArray -> part.toIntOrNull()?.let { current.opt(it) }
                        else -> null
                    }
                }
            }
            val out = when (current) {
                is JSONObject -> current.toString(2)
                is JSONArray -> current.toString(2)
                else -> current.toString()
            }
            onPrint("$out\n")
            0
        } catch (e: Exception) {
            onPrint("jq: error: ${e.message}\n")
            1
        }
    }

    fun runBc(args: List<String>, stdin: String, onPrint: (String) -> Unit): Int {
        val exprText = (if (args.isNotEmpty()) args.joinToString(" ") else stdin).trim()
        for (line in exprText.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (line == "quit") break
            try {
                val res = evalSimpleMath(line)
                onPrint(if (res % 1.0 == 0.0) "${res.toLong()}\n" else "$res\n")
            } catch (e: Exception) {
                onPrint("bc: syntax error (${e.message})\n")
            }
        }
        return 0
    }

    private fun evalSimpleMath(expr: String): Double {
        var i = 0
        fun skipWs() { while (i < expr.length && expr[i].isWhitespace()) i++ }
        lateinit var parseExpr: () -> Double
        fun parseFactor(): Double {
            skipWs()
            if (i < expr.length && expr[i] == '(') {
                i++; val v = parseExpr(); skipWs(); if (i < expr.length && expr[i] == ')') i++; return v
            }
            if (i < expr.length && expr[i] == '-') { i++; return -parseFactor() }
            val start = i
            while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
            return expr.substring(start, i).toDouble()
        }
        fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                skipWs()
                v = when {
                    i < expr.length && expr[i] == '*' -> { i++; v * parseFactor() }
                    i < expr.length && expr[i] == '/' -> { i++; v / parseFactor() }
                    i < expr.length && expr[i] == '%' -> { i++; v % parseFactor() }
                    else -> return v
                }
            }
        }
        parseExpr = {
            var v = parseTerm()
            var cont = true
            while (cont) {
                skipWs()
                when {
                    i < expr.length && expr[i] == '+' -> { i++; v += parseTerm() }
                    i < expr.length && expr[i] == '-' -> { i++; v -= parseTerm() }
                    else -> cont = false
                }
            }
            v
        }
        return parseExpr()
    }

    fun runTree(args: List<String>, cwd: File, onPrint: (String) -> Unit): Int {
        val target = env.resolvePath(cwd, args.firstOrNull { !it.startsWith("-") } ?: ".")
        if (!target.exists()) { onPrint("tree: '$target': No such file or directory\n"); return 1 }
        var dirs = 0; var files = 0
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            children.forEachIndexed { idx, f ->
                val last = idx == children.lastIndex
                val conn = if (last) "└── " else "├── "
                val label = if (f.isDirectory) "\u001B[1;34m${f.name}\u001B[0m" else f.name
                onPrint("$prefix$conn$label\n")
                if (f.isDirectory) {
                    dirs++
                    walk(f, prefix + if (last) "    " else "│   ")
                } else files++
            }
        }
        onPrint("\u001B[1;34m${target.name}\u001B[0m\n")
        walk(target, "")
        onPrint("\n$dirs directories, $files files\n")
        return 0
    }

    fun runFiglet(args: List<String>, onPrint: (String) -> Unit): Int {
        val text = args.joinToString(" ").ifEmpty { "TERMUX++" }
        val border = "=".repeat(text.length * 2 + 4)
        onPrint("\u001B[1;36m$border\n  ${text.uppercase().toCharArray().joinToString(" ")}\n$border\u001B[0m\n")
        return 0
    }

    fun runZip(args: List<String>, cwd: File, onPrint: (String) -> Unit): Int {
        val archiveName = args.firstOrNull { !it.startsWith("-") } ?: return 1
        val inputs = args.drop(1).filter { !it.startsWith("-") }
        val zipFile = env.resolvePath(cwd, archiveName)
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (inp in inputs) {
                val f = env.resolvePath(cwd, inp)
                val list = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.toList() else listOf(f)
                for (item in list) {
                    if (!item.exists()) continue
                    val entryName = if (f.isDirectory) item.relativeTo(f.parentFile ?: cwd).path else item.name
                    zos.putNextEntry(ZipEntry(entryName))
                    item.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    onPrint("  adding: $entryName\n")
                }
            }
        }
        return 0
    }

    fun runUnzip(args: List<String>, cwd: File, onPrint: (String) -> Unit): Int {
        val zipFile = env.resolvePath(cwd, args.firstOrNull { !it.startsWith("-") } ?: return 1)
        if (!zipFile.exists()) { onPrint("unzip: cannot find ${zipFile.name}\n"); return 1 }
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var e: ZipEntry? = zis.nextEntry
            while (e != null) {
                val out = File(cwd, e.name)
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                    onPrint("  inflating: ${e.name}\n")
                }
                e = zis.nextEntry
            }
        }
        return 0
    }

    fun runGzip(args: List<String>, cwd: File, decompress: Boolean, onPrint: (String) -> Unit): Int {
        val target = env.resolvePath(cwd, args.firstOrNull { !it.startsWith("-") } ?: return 1)
        if (!target.exists()) { onPrint("gzip: ${target.name}: No such file\n"); return 1 }
        if (decompress || args.contains("-d")) {
            val out = File(target.parentFile, target.name.removeSuffix(".gz"))
            GZIPInputStream(FileInputStream(target)).use { gis -> FileOutputStream(out).use { gis.copyTo(it) } }
        } else {
            val out = File(target.parentFile, "${target.name}.gz")
            FileInputStream(target).use { fis -> GZIPOutputStream(FileOutputStream(out)).use { fis.copyTo(it) } }
        }
        return 0
    }

    fun runSshKeygen(args: List<String>, onPrint: (String) -> Unit): Int {
        val sshDir = File(env.homeDir, ".ssh").apply { mkdirs() }
        val keyFile = File(sshDir, "id_rsa")
        val pubFile = File(sshDir, "id_rsa.pub")
        onPrint("Generating public/private rsa key pair.\n")
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = kpg.generateKeyPair()
        val privB64 = Base64.getEncoder().encodeToString(kp.private.encoded)
        val pubB64 = Base64.getEncoder().encodeToString(kp.public.encoded)
        keyFile.writeText("-----BEGIN PRIVATE KEY-----\n$privB64\n-----END PRIVATE KEY-----\n")
        pubFile.writeText("ssh-rsa $pubB64 u0_a100@termux-cpp\n")
        onPrint("Your identification has been saved in ${keyFile.absolutePath}\n")
        onPrint("Your public key has been saved in ${pubFile.absolutePath}\n")
        return 0
    }

    fun runXxd(args: List<String>, stdin: String, cwd: File, onPrint: (String) -> Unit): Int {
        val bytes = args.firstOrNull { !it.startsWith("-") }?.let {
            val f = env.resolvePath(cwd, it)
            if (f.exists()) f.readBytes() else return 1
        } ?: stdin.toByteArray()
        var offset = 0
        while (offset < bytes.size) {
            val slice = bytes.sliceArray(offset until minOf(offset + 16, bytes.size))
            val hex = slice.joinToString(" ") { "%02x".format(it) }.padEnd(47)
            val ascii = slice.map { b -> val c = b.toInt() and 0xFF; if (c in 32..126) c.toChar() else '.' }.joinToString("")
            onPrint(String.format(Locale.US, "%08x: %s  %s\n", offset, hex, ascii))
            offset += 16
        }
        return 0
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
