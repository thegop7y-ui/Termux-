package com.example.core.fs

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TermuxEnvironment(val context: Context) {
    val rootFsDir: File = File(context.filesDir, "rootfs")
    val homeDir: File = File(context.filesDir, "home")
    val prefixDir: File = File(context.filesDir, "usr")
    val binDir: File = File(prefixDir, "bin")
    val libDir: File = File(prefixDir, "lib")
    val includeDir: File = File(prefixDir, "include")
    val etcDir: File = File(prefixDir, "etc")
    val shareDir: File = File(prefixDir, "share")
    val tmpDir: File = File(prefixDir, "tmp")
    val dpkgDir: File = File(prefixDir, "var/lib/dpkg")
    val dpkgStatusFile: File = File(dpkgDir, "status")
    val pythonSitePackagesDir: File = File(libDir, "python3.12/site-packages")

    private val lock = Any()

    init {
        ensureBootstrapped()
    }

    fun ensureBootstrapped() {
        synchronized(lock) {
            listOf(
                homeDir,
                File(homeDir, ".termux"),
                File(homeDir, "examples"),
                prefixDir,
                binDir,
                libDir,
                includeDir,
                File(includeDir, "c++/v1"),
                etcDir,
                File(etcDir, "apt"),
                shareDir,
                File(shareDir, "man"),
                tmpDir,
                dpkgDir,
                pythonSitePackagesDir
            ).forEach { if (!it.exists()) it.mkdirs() }

            val bashrc = File(homeDir, ".bashrc")
            if (!bashrc.exists()) {
                bashrc.writeText(
                    """
                    # Termux++ ~/.bashrc
                    export TERM=xterm-256color
                    export LANG=en_US.UTF-8
                    export EDITOR=nano
                    alias ll='ls -la'
                    alias la='ls -A'
                    alias l='ls -CF'
                    alias cls='clear'
                    alias py='python3'
                    alias cpp='g++'
                    """.trimIndent() + "\n"
                )
            }

            val profile = File(homeDir, ".profile")
            if (!profile.exists()) {
                profile.writeText("if [ -f ~/.bashrc ]; then . ~/.bashrc; fi\n")
            }

            val sourcesList = File(etcDir, "apt/sources.list")
            if (!sourcesList.exists()) {
                sourcesList.writeText(
                    "# Termux++ package repository\ndeb https://packages.termux-cpp.local/apt/termux-main stable main\n"
                )
            }

            val motd = File(etcDir, "motd")
            if (!motd.exists()) {
                motd.writeText(defaultMotdText())
            }

            val helloCpp = File(homeDir, "examples/hello.cpp")
            if (!helloCpp.exists()) {
                helloCpp.writeText(
                    """
                    #include <iostream>
                    #include <vector>
                    #include <algorithm>
                    #include <cmath>

                    using namespace std;

                    int main() {
                        cout << "=== Termux++ C++20 Runtime ===" << endl;
                        vector<int> nums = {42, 7, 19, 3, 99, 12};
                        sort(nums.begin(), nums.end());
                        cout << "Sorted: ";
                        for (auto n : nums) {
                            cout << n << " ";
                        }
                        cout << endl;
                        cout << "sqrt(144) = " << sqrt(144) << endl;
                        return 0;
                    }
                    """.trimIndent() + "\n"
                )
            }

            val fibPy = File(homeDir, "examples/fibonacci.py")
            if (!fibPy.exists()) {
                fibPy.writeText(
                    """
                    import math
                    import sys

                    def fib(n):
                        a, b = 0, 1
                        res = []
                        for _ in range(n):
                            res.append(a)
                            a, b = b, a + b
                        return res

                    print(f"Python {sys.version} on Termux++")
                    print("Fibonacci(10):", fib(10))
                    print("Squares:", [x * x for x in range(1, 8)])
                    print(f"pi = {math.pi:.5f}")
                    """.trimIndent() + "\n"
                )
            }

            if (!dpkgStatusFile.exists()) {
                dpkgStatusFile.writeText("bash\ncoreutils\ntermux-tools\npkg\napt\nnano\n")
            }

            // Ensure base commands appear in $PREFIX/bin
            listOf("bash", "sh", "pkg", "apt", "dpkg", "nano", "ls", "cat", "cp", "mv", "rm", "mkdir", "grep", "clear", "termux-info")
                .forEach { cmd ->
                    val bin = File(binDir, cmd)
                    if (!bin.exists()) {
                        bin.writeText("#!/system/bin/sh\n# Termux++ builtin: $cmd\n")
                        bin.setExecutable(true)
                    }
                }
        }
    }

    fun defaultMotdText(): String =
        "\u001B[1;32mWelcome to Termux++!\u001B[0m\n\n" +
            "\u001B[36mDocs:\u001B[0m       help | man <cmd>\n" +
            "\u001B[36mCommunity:\u001B[0m  POSIX + C++20 + Python 3\n\n" +
            "\u001B[1mWorking with packages:\u001B[0m\n" +
            " \u001B[32m*\u001B[0m Search:   pkg search <query>\n" +
            " \u001B[32m*\u001B[0m Install:  pkg install <package>\n" +
            " \u001B[32m*\u001B[0m Upgrade:  pkg upgrade\n\n" +
            "\u001B[2mTry:\u001B[0m pkg install python clang git curl neofetch\n"

    fun baseEnvMap(): MutableMap<String, String> = mutableMapOf(
        "HOME" to homeDir.absolutePath,
        "PREFIX" to prefixDir.absolutePath,
        "TMPDIR" to tmpDir.absolutePath,
        "PATH" to "${binDir.absolutePath}:/system/bin:/system/xbin",
        "TERM" to "xterm-256color",
        "COLORTERM" to "truecolor",
        "LANG" to "en_US.UTF-8",
        "SHELL" to "${binDir.absolutePath}/bash",
        "USER" to "u0_a100",
        "HOSTNAME" to "termux-cpp",
        "EDITOR" to "nano"
    )

    fun resolvePath(cwd: File, rawPath: String): File {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty() || trimmed == "~") return homeDir

        val mapped = when {
            trimmed.startsWith("~/") -> "${homeDir.absolutePath}/${trimmed.removePrefix("~/")}"
            trimmed == "/home" || trimmed.startsWith("/home/") -> {
                val sub = trimmed.removePrefix("/home").removePrefix("/")
                if (sub.isEmpty()) homeDir.absolutePath else "${homeDir.absolutePath}/$sub"
            }
            trimmed == "/usr" || trimmed.startsWith("/usr/") -> {
                val sub = trimmed.removePrefix("/usr").removePrefix("/")
                if (sub.isEmpty()) prefixDir.absolutePath else "${prefixDir.absolutePath}/$sub"
            }
            trimmed == "/tmp" || trimmed.startsWith("/tmp/") -> {
                val sub = trimmed.removePrefix("/tmp").removePrefix("/")
                if (sub.isEmpty()) tmpDir.absolutePath else "${tmpDir.absolutePath}/$sub"
            }
            trimmed == "/bin" || trimmed.startsWith("/bin/") -> {
                val sub = trimmed.removePrefix("/bin").removePrefix("/")
                if (sub.isEmpty()) binDir.absolutePath else "${binDir.absolutePath}/$sub"
            }
            trimmed == "/etc" || trimmed.startsWith("/etc/") -> {
                val sub = trimmed.removePrefix("/etc").removePrefix("/")
                if (sub.isEmpty()) etcDir.absolutePath else "${etcDir.absolutePath}/$sub"
            }
            else -> trimmed
        }

        val target = if (mapped.startsWith("/")) File(mapped) else File(cwd, mapped)
        return try {
            target.canonicalFile
        } catch (_: Exception) {
            target.absoluteFile
        }
    }

    fun toDisplayPath(file: File): String {
        val abs = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
        val homeAbs = try { homeDir.canonicalPath } catch (_: Exception) { homeDir.absolutePath }
        val prefixAbs = try { prefixDir.canonicalPath } catch (_: Exception) { prefixDir.absolutePath }
        return when {
            abs == homeAbs -> "~"
            abs.startsWith("$homeAbs/") -> "~/" + abs.removePrefix("$homeAbs/")
            abs == prefixAbs -> "\$PREFIX"
            abs.startsWith("$prefixAbs/") -> "\$PREFIX/" + abs.removePrefix("$prefixAbs/")
            else -> abs
        }
    }

    fun expandGlob(cwd: File, token: String): List<String> {
        if (!token.contains('*') && !token.contains('?')) return listOf(token)
        val hasSlash = token.contains('/')
        val dirPart = if (hasSlash) token.substringBeforeLast('/', "") else ""
        val namePattern = if (hasSlash) token.substringAfterLast('/') else token
        val searchDir = if (dirPart.isEmpty()) cwd else resolvePath(cwd, dirPart)
        if (!searchDir.isDirectory) return listOf(token)

        val regexStr = buildString {
            append('^')
            for (ch in namePattern) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> append("\\$ch")
                    else -> append(ch)
                }
            }
            append('$')
        }
        val regex = Regex(regexStr)
        val matches = searchDir.listFiles()
            ?.filter {
                if (!namePattern.startsWith(".") && it.name.startsWith(".")) false
                else regex.matches(it.name)
            }
            ?.sortedBy { it.name }
            ?.map { if (dirPart.isEmpty()) it.name else "$dirPart/${it.name}" }
            .orEmpty()
        return matches.ifEmpty { listOf(token) }
    }

    fun getInstalledPackages(): Set<String> = synchronized(lock) {
        if (!dpkgStatusFile.exists()) return emptySet()
        dpkgStatusFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun markPackageInstalled(pkgId: String) {
        synchronized(lock) {
            val current = getInstalledPackages().toMutableSet()
            current.add(pkgId)
            dpkgStatusFile.writeText(current.sorted().joinToString("\n") + "\n")
        }
    }

    fun markPackageRemoved(pkgId: String) {
        synchronized(lock) {
            val current = getInstalledPackages().toMutableSet()
            current.remove(pkgId)
            dpkgStatusFile.writeText(current.sorted().joinToString("\n") + "\n")
        }
    }

    fun formatLongEntry(file: File): String {
        val typeChar = if (file.isDirectory) 'd' else '-'
        val r = if (file.canRead()) 'r' else '-'
        val w = if (file.canWrite()) 'w' else '-'
        val x = if (file.canExecute() || file.isDirectory) 'x' else '-'
        val perms = "$typeChar$r$w$x$r-$x$r-$x"
        val size = if (file.isDirectory) 4096L else file.length()
        val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.US)
        val dateStr = sdf.format(Date(file.lastModified()))
        val displayName = when {
            file.isDirectory -> "\u001B[1;34m${file.name}/\u001B[0m"
            file.canExecute() || file.name.endsWith(".sh") -> "\u001B[1;32m${file.name}\u001B[0m"
            else -> file.name
        }
        return String.format(Locale.US, "%s  1 u0_a100 u0_a100 %8d %s %s", perms, size, dateStr, displayName)
    }

    fun homeUsageSummary(): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        fun walk(f: File) {
            val children = f.listFiles() ?: return
            for (c in children) {
                if (c.isDirectory) walk(c) else {
                    count++
                    bytes += c.length()
                }
            }
        }
        walk(homeDir)
        return count to bytes
    }
}
