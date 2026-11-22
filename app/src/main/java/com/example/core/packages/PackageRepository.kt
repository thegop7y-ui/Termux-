package com.example.core.packages

import com.example.core.fs.TermuxEnvironment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class TermuxPackage(
    val id: String,
    val version: String,
    val category: String,
    val description: String,
    val binaries: List<String>,
    val sizeKb: Int,
    val dependencies: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val essential: Boolean = false
)

class PackageRepository(private val env: TermuxEnvironment) {

    val catalog: List<TermuxPackage> = listOf(
        TermuxPackage("bash", "5.2.32", "Shell", "GNU Bourne Again SHell (POSIX)", listOf("bash", "sh"), 1120, essential = true),
        TermuxPackage("coreutils", "9.5", "Base", "GNU file, shell and text utilities", listOf("ls", "cat", "cp", "mv", "rm", "mkdir", "touch", "head", "tail", "wc", "sort", "uniq", "cut", "tr", "base64", "sha256sum", "md5sum"), 2480, essential = true),
        TermuxPackage("termux-tools", "1.43.0", "Base", "Basic system utilities for Termux++", listOf("termux-info", "clear", "pkg"), 180, essential = true),
        TermuxPackage("apt", "2.8.0", "Base", "Commandline package manager", listOf("apt", "apt-get", "dpkg"), 1420, essential = true),
        TermuxPackage("nano", "8.1", "Editor", "Small, friendly interactive terminal text editor", listOf("nano"), 540, essential = true),

        TermuxPackage(
            id = "python",
            version = "3.12.4",
            category = "Languages",
            description = "Python 3 interpreter, REPL, standard library & pip package installer",
            binaries = listOf("python", "python3", "pip", "pip3"),
            sizeKb = 18400,
            aliases = listOf("python3", "pip")
        ),
        TermuxPackage(
            id = "clang",
            version = "18.1.8",
            category = "C / C++",
            description = "C/C++20 compiler toolchain (clang++, g++, gcc, make) and STL headers",
            binaries = listOf("clang", "clang++", "g++", "gcc", "cpp", "c++", "make"),
            sizeKb = 42600,
            aliases = listOf("g++", "gcc", "clang++", "build-essential", "c++", "make")
        ),
        TermuxPackage(
            id = "git",
            version = "2.46.0",
            category = "Dev Tools",
            description = "Fast distributed version control system (init, add, commit, log, clone)",
            binaries = listOf("git"),
            sizeKb = 6800,
            dependencies = listOf("curl")
        ),
        TermuxPackage(
            id = "curl",
            version = "8.9.1",
            category = "Network",
            description = "Command line tool for transferring data with URLs (HTTP/HTTPS)",
            binaries = listOf("curl"),
            sizeKb = 1240
        ),
        TermuxPackage(
            id = "wget",
            version = "1.24.5",
            category = "Network",
            description = "Network utility to retrieve files from the Web",
            binaries = listOf("wget"),
            sizeKb = 980
        ),
        TermuxPackage(
            id = "sqlite",
            version = "3.46.1",
            category = "Database",
            description = "Self-contained SQL database engine CLI and interactive shell",
            binaries = listOf("sqlite3"),
            sizeKb = 1650,
            aliases = listOf("sqlite3")
        ),
        TermuxPackage(
            id = "htop",
            version = "3.3.0",
            category = "System",
            description = "Interactive process & memory viewer for terminal",
            binaries = listOf("htop", "top"),
            sizeKb = 420
        ),
        TermuxPackage(
            id = "neofetch",
            version = "7.1.0",
            category = "System",
            description = "Fast, highly customizable system info script with ASCII art",
            binaries = listOf("neofetch", "fastfetch"),
            sizeKb = 240,
            aliases = listOf("fastfetch")
        ),
        TermuxPackage(
            id = "jq",
            version = "1.7.1",
            category = "Dev Tools",
            description = "Lightweight and flexible command-line JSON processor",
            binaries = listOf("jq"),
            sizeKb = 610
        ),
        TermuxPackage(
            id = "bc",
            version = "1.07.1",
            category = "Math",
            description = "Arbitrary precision numeric processing language",
            binaries = listOf("bc"),
            sizeKb = 290
        ),
        TermuxPackage(
            id = "tree",
            version = "2.1.3",
            category = "Utilities",
            description = "Recursive directory listing command producing depth-indented tree",
            binaries = listOf("tree"),
            sizeKb = 160
        ),
        TermuxPackage(
            id = "figlet",
            version = "2.2.5",
            category = "Utilities",
            description = "Program for making large letters out of ordinary text",
            binaries = listOf("figlet"),
            sizeKb = 310
        ),
        TermuxPackage(
            id = "zip",
            version = "3.0",
            category = "Archives",
            description = "Compressor and extractor for .zip archives",
            binaries = listOf("zip", "unzip"),
            sizeKb = 480,
            aliases = listOf("unzip")
        ),
        TermuxPackage(
            id = "tar",
            version = "1.35",
            category = "Archives",
            description = "GNU tar & gzip archive utilities",
            binaries = listOf("tar", "gzip", "gunzip"),
            sizeKb = 820,
            aliases = listOf("gzip")
        ),
        TermuxPackage(
            id = "openssh",
            version = "9.8p1",
            category = "Network",
            description = "OpenSSH key generation (ssh-keygen) & secure shell tools",
            binaries = listOf("ssh-keygen", "ssh"),
            sizeKb = 2950,
            aliases = listOf("ssh", "ssh-keygen")
        ),
        TermuxPackage(
            id = "termux-api",
            version = "0.59.0",
            category = "Android API",
            description = "Access Android battery, clipboard, vibrate, toast, device info & storage",
            binaries = listOf(
                "termux-battery-status",
                "termux-toast",
                "termux-vibrate",
                "termux-clipboard-get",
                "termux-clipboard-set",
                "termux-device-info",
                "termux-open-url",
                "termux-setup-storage"
            ),
            sizeKb = 360
        ),
        TermuxPackage(
            id = "lua",
            version = "5.4.7",
            category = "Languages",
            description = "Powerful, lightweight embeddable scripting language",
            binaries = listOf("lua"),
            sizeKb = 740
        ),
        TermuxPackage(
            id = "nodejs",
            version = "20.16.0",
            category = "Languages",
            description = "JavaScript runtime evaluator & JSON scripting CLI",
            binaries = listOf("node"),
            sizeKb = 9800,
            aliases = listOf("node")
        ),
        TermuxPackage(
            id = "vim",
            version = "9.1.0",
            category = "Editor",
            description = "Vi IMproved terminal text editor",
            binaries = listOf("vim", "vi"),
            sizeKb = 3200,
            aliases = listOf("vi")
        ),
        TermuxPackage(
            id = "xxd",
            version = "9.1.0",
            category = "Dev Tools",
            description = "Make a hexdump or do the reverse",
            binaries = listOf("xxd", "hexdump"),
            sizeKb = 140,
            aliases = listOf("hexdump")
        ),
        TermuxPackage(
            id = "man",
            version = "1.14.6",
            category = "Docs",
            description = "On-line manual pager for installed Termux++ commands",
            binaries = listOf("man"),
            sizeKb = 520
        )
    )

    private val _installedIds = MutableStateFlow(env.getInstalledPackages())
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    fun refreshInstalled() {
        _installedIds.value = env.getInstalledPackages()
    }

    fun findPackage(query: String): TermuxPackage? {
        val q = query.trim().lowercase()
        return catalog.find { it.id == q }
            ?: catalog.find { q in it.aliases }
            ?: catalog.find { q in it.binaries }
    }

    fun findProviderForCommand(cmd: String): TermuxPackage? {
        val c = cmd.trim().lowercase()
        return catalog.find { c in it.binaries || c in it.aliases || c == it.id }
    }

    fun isCommandAvailable(cmd: String): Boolean {
        val provider = findProviderForCommand(cmd) ?: return false
        return provider.id in _installedIds.value
    }

    suspend fun installPackage(
        pkgName: String,
        onOutput: (String) -> Unit
    ): Boolean {
        val pkg = findPackage(pkgName)
        if (pkg == null) {
            onOutput("\u001B[1;31mE:\u001B[0m Unable to locate package $pkgName\n")
            return false
        }

        val current = env.getInstalledPackages()
        if (pkg.id in current) {
            onOutput("${pkg.id} is already the newest version (${pkg.version}).\n")
            return true
        }

        val toInstall = mutableListOf<TermuxPackage>()
        for (depId in pkg.dependencies) {
            val dep = findPackage(depId)
            if (dep != null && dep.id !in current) {
                toInstall.add(dep)
            }
        }
        toInstall.add(pkg)

        val totalKb = toInstall.sumOf { it.sizeKb }
        onOutput("Reading package lists... Done\n")
        onOutput("Building dependency tree... Done\n")
        if (toInstall.size > 1) {
            onOutput("The following additional packages will be installed:\n  ${toInstall.dropLast(1).joinToString(" ") { it.id }}\n")
        }
        onOutput("The following NEW packages will be installed:\n  ${toInstall.joinToString(" ") { it.id }}\n")
        onOutput("Need to get ${totalKb} kB of archives.\n")
        onOutput("After this operation, ${(totalKb * 1.4).toInt()} kB of additional disk space will be used.\n")

        for ((index, item) in toInstall.withIndex()) {
            onOutput("\u001B[36mGet:${index + 1}\u001B[0m termux-main/stable ${item.id} ${item.version} [${item.sizeKb} kB]\n")
            delay(180)
            materializePackageFiles(item)
            env.markPackageInstalled(item.id)
            onOutput("Unpacking ${item.id} (${item.version}) ...\n")
            delay(120)
        }
        for (item in toInstall) {
            onOutput("Setting up ${item.id} (${item.version}) ...\n")
            delay(80)
        }
        refreshInstalled()
        onOutput("\u001B[1;32m✓ Done.\u001B[0m Installed: ${toInstall.joinToString(", ") { it.id }}\n")
        return true
    }

    suspend fun removePackage(pkgName: String, onOutput: (String) -> Unit): Boolean {
        val pkg = findPackage(pkgName)
        if (pkg == null) {
            onOutput("\u001B[1;31mE:\u001B[0m Unable to locate package $pkgName\n")
            return false
        }
        if (pkg.essential) {
            onOutput("\u001B[1;31mE:\u001B[0m ${pkg.id} is an essential system package and cannot be removed.\n")
            return false
        }
        if (pkg.id !in env.getInstalledPackages()) {
            onOutput("Package '${pkg.id}' is not installed, so not removed.\n")
            return true
        }
        onOutput("Removing ${pkg.id} (${pkg.version}) ...\n")
        delay(150)
        for (bin in pkg.binaries) {
            File(env.binDir, bin).delete()
        }
        env.markPackageRemoved(pkg.id)
        refreshInstalled()
        onOutput("\u001B[1;32m✓ Removed ${pkg.id}.\u001B[0m\n")
        return true
    }

    private fun materializePackageFiles(pkg: TermuxPackage) {
        for (bin in pkg.binaries) {
            val f = File(env.binDir, bin)
            f.writeText("#!/system/bin/sh\n# Termux++ runtime binary: $bin (${pkg.id} ${pkg.version})\n")
            f.setExecutable(true)
        }
        when (pkg.id) {
            "clang" -> {
                val inc = File(env.includeDir, "c++/v1")
                inc.mkdirs()
                mapOf(
                    "iostream" to "// Termux++ C++20 <iostream>\n#pragma once\n",
                    "vector" to "// Termux++ C++20 <vector>\n#pragma once\n",
                    "string" to "// Termux++ C++20 <string>\n#pragma once\n",
                    "cmath" to "// Termux++ C++20 <cmath>\n#pragma once\n",
                    "algorithm" to "// Termux++ C++20 <algorithm>\n#pragma once\n",
                    "fstream" to "// Termux++ C++20 <fstream>\n#pragma once\n",
                    "map" to "// Termux++ C++20 <map>\n#pragma once\n",
                    "iomanip" to "// Termux++ C++20 <iomanip>\n#pragma once\n",
                    "sstream" to "// Termux++ C++20 <sstream>\n#pragma once\n"
                ).forEach { (name, content) ->
                    File(inc, name).writeText(content)
                }
            }
            "python" -> {
                env.pythonSitePackagesDir.mkdirs()
                File(env.pythonSitePackagesDir, "README.txt").writeText(
                    "Termux++ Python 3.12 site-packages directory.\nUse `pip install <package>` or place .py modules here.\n"
                )
            }
            "man" -> {
                val man1 = File(env.shareDir, "man/man1")
                man1.mkdirs()
                File(man1, "pkg.1").writeText("PKG(1) - Termux++ package manager\nCommands: search, install, uninstall, list-installed, list-all, show, update, upgrade\n")
                File(man1, "clang++.1").writeText("CLANG++(1) - C++20 compiler and runtime\nUsage: g++ [options] file.cpp -o output\n")
                File(man1, "python3.1").writeText("PYTHON3(1) - Python 3.12 interpreter & REPL\nUsage: python3 [script.py] | python3 -c \"code\"\n")
            }
        }
    }
}
